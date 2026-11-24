use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use base64::Engine;
use reqwest::{Client, StatusCode};
use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter};
use tokio::sync::Notify;

use crate::db::{Db, PendingOperation, SyncMeta};
use crate::keychain;
use crate::models::{Attachment, ChangesPage, OperationReceipt, Snapshot, Status};

static CLIENT: OnceLock<Client> = OnceLock::new();

pub fn trigger() -> Arc<Notify> {
    Arc::new(Notify::new())
}

pub fn notify(trigger: &Arc<Notify>) {
    trigger.notify_one();
}

pub fn start(app: AppHandle, db: Arc<Db>, trigger: Arc<Notify>) {
    tauri::async_runtime::spawn(async move {
        let mut failures = 0u32;
        loop {
            let result = sync_once(&db).await;
            match &result {
                Ok(_) => {
                    failures = 0;
                    let _ = app.emit("sync-complete", ());
                    let _ = app.emit("data-changed", ());
                    crate::check_reminder(&app);
                }
                Err(error) => {
                    failures = failures.saturating_add(1);
                    let message = format!("{error:#}");
                    let _ = db.mark_sync_error(&message);
                    let _ = app.emit("sync-error", message);
                    log::warn!("sync failed: {error:#}");
                }
            }
            let delay = if result.is_ok() {
                Duration::from_secs(15)
            } else {
                retry_delay(failures)
            };
            tokio::select! {
                _ = trigger.notified() => {}
                _ = tokio::time::sleep(delay) => {}
            }
        }
    });
}

fn retry_delay(failures: u32) -> Duration {
    let exponent = failures.min(5);
    let seconds = 15u64.saturating_mul(1u64 << exponent);
    Duration::from_secs(seconds.min(300))
}

pub async fn sync_once(db: &Db) -> Result<()> {
    let (server_url, _) = db.server_config()?;
    let server_url = server_url.trim().trim_end_matches('/');
    if server_url.is_empty() {
        bail!("尚未配置服务器");
    }
    let token = keychain::token()?.ok_or_else(|| anyhow!("尚未设置访问令牌"))?;
    let status: Status = get_json(server_url, "/v1/status", &token).await?;
    if status.protocol_version != 1 {
        bail!("服务器协议版本 {} 不受支持", status.protocol_version);
    }
    let meta = db.sync_meta()?;
    if needs_snapshot(&meta, &status) {
        let snapshot: Snapshot = get_json(server_url, "/v1/snapshot", &token).await?;
        db.apply_snapshot(snapshot, &status.dataset_id)?;
    }

    for operation in db.pending_operations()? {
        submit_operation(db, server_url, &token, &operation).await?;
    }
    let attachment_error = upload_pending_attachments(db, server_url, &token)
        .await
        .err();

    let mut cursor = db.sync_meta()?.cursor;
    for _ in 0..50 {
        let page: ChangesPage = get_json(
            server_url,
            &format!("/v1/changes?after={cursor}&limit=100"),
            &token,
        )
        .await?;
        if !page.changes.is_empty() {
            db.apply_changes_page(&page.changes, page.next_cursor)?;
        }
        cursor = page.next_cursor;
        if !page.has_more {
            break;
        }
    }
    if let Some(error) = attachment_error {
        let message = format!("{error:#}");
        db.mark_sync_cursor(&status, cursor)?;
        db.mark_sync_error(&message)?;
        return Err(anyhow!(message));
    }
    db.mark_sync_success(&status, cursor)?;
    Ok(())
}

fn needs_snapshot(meta: &SyncMeta, status: &Status) -> bool {
    meta.dataset_id.as_deref() != Some(status.dataset_id.as_str())
        || meta.restore_version != Some(status.restore_version)
}

async fn submit_operation(
    db: &Db,
    server_url: &str,
    token: &str,
    operation: &PendingOperation,
) -> Result<()> {
    let url = format!("{server_url}/v1/operations");
    let response = client()
        .post(url)
        .bearer_auth(token)
        .json(&operation.payload)
        .send()
        .await
        .context("submit operation")?;
    let status = response.status();
    let body = response.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(api_error(status, &body));
    }
    let receipt: OperationReceipt =
        serde_json::from_str(&body).context("decode operation receipt")?;
    if receipt.op_id != operation.op_id {
        bail!("服务器确认了错误的操作");
    }
    db.complete_operation(&operation.op_id)?;
    Ok(())
}

async fn upload_pending_attachments(db: &Db, server_url: &str, token: &str) -> Result<()> {
    let mut failures = Vec::new();
    for local in db.pending_upload_attachments()? {
        let result = async {
            let Some(path) = local.local_path.as_ref().map(PathBuf::from) else {
                bail!(
                    "本地附件 {} 缺少文件，无法上传",
                    local.attachment.attachment_id
                );
            };
            let bytes = tokio::fs::read(&path)
                .await
                .with_context(|| format!("read attachment {}", path.display()))?;
            let digest = hex_digest(&bytes);
            if digest != local.attachment.sha256 || bytes.len() as i64 != local.attachment.byte_size
            {
                bail!(
                    "本地附件 {} 已损坏，拒绝上传",
                    local.attachment.attachment_id
                );
            }
            let url = format!(
                "{server_url}/v1/attachments/{}",
                local.attachment.attachment_id
            );
            let response = client()
                .put(url)
                .bearer_auth(token)
                .query(&[
                    ("taskId", local.attachment.task_id.as_str()),
                    ("kind", attachment_kind(&local.attachment)),
                    ("mediaType", local.attachment.media_type.as_str()),
                ])
                .header("X-Content-SHA256", &local.attachment.sha256)
                .header("Content-Type", &local.attachment.media_type)
                .body(bytes)
                .send()
                .await
                .context("upload attachment")?;
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            if status == StatusCode::CONFLICT {
                bail!("附件等待所属待办同步：{body}");
            }
            if !status.is_success() {
                return Err(api_error(status, &body));
            }
            let remote: Attachment =
                serde_json::from_str(&body).context("decode attachment response")?;
            db.mark_attachment_remote(&remote)?;
            Ok(())
        }
        .await;
        if let Err(error) = result {
            failures.push(format!(
                "附件 {}: {error:#}",
                local.attachment.attachment_id
            ));
        }
    }
    if !failures.is_empty() {
        bail!("{}", failures.join("; "));
    }
    Ok(())
}

pub async fn download_attachment(db: &Db, attachment_id: &str) -> Result<Option<PathBuf>> {
    if let Some(path) = db.attachment_file_path(attachment_id)? {
        if path.is_file() {
            return Ok(Some(path));
        }
    }
    let attachment = db
        .attachment(attachment_id)?
        .ok_or_else(|| anyhow!("附件不存在"))?
        .attachment;
    let (server_url, _) = db.server_config()?;
    let server_url = server_url.trim().trim_end_matches('/');
    if server_url.is_empty() {
        bail!("服务器未配置");
    }
    let token = keychain::token()?.ok_or_else(|| anyhow!("访问令牌未设置"))?;
    let response = client()
        .get(format!(
            "{server_url}/v1/attachments/{}",
            attachment.attachment_id
        ))
        .bearer_auth(token)
        .send()
        .await
        .context("download attachment")?;
    let status = response.status();
    if !status.is_success() {
        let body = response.text().await.unwrap_or_default();
        return Err(api_error(status, &body));
    }
    let bytes = response.bytes().await.context("read attachment response")?;
    if bytes.len() as i64 != attachment.byte_size || hex_digest(&bytes) != attachment.sha256 {
        bail!("下载的附件校验失败");
    }
    let extension = match attachment.media_type.as_str() {
        "image/png" => "png",
        "image/jpeg" => "jpg",
        "audio/wav" => "wav",
        _ => "bin",
    };
    let path = db
        .root()
        .join("attachments")
        .join(format!("{}.{}", attachment.attachment_id, extension));
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let temp = path.with_extension(format!("{extension}.tmp"));
    tokio::fs::write(&temp, &bytes).await?;
    tokio::fs::rename(&temp, &path).await?;
    db.set_attachment_local_path(&attachment.attachment_id, &path.to_string_lossy())?;
    Ok(Some(path))
}

pub async fn test_connection(server_url: &str, token: &str) -> Result<Status> {
    let server_url = server_url.trim().trim_end_matches('/');
    if server_url.is_empty() {
        bail!("请输入服务器地址");
    }
    let status: Status = get_json(server_url, "/v1/status", token).await?;
    if status.protocol_version != 1 {
        bail!("服务器协议版本 {} 不受支持", status.protocol_version);
    }
    Ok(status)
}

pub async fn retry_transcription(db: &Db, attachment_id: &str) -> Result<()> {
    let (server_url, _) = db.server_config()?;
    let server_url = server_url.trim().trim_end_matches('/');
    if server_url.is_empty() {
        bail!("尚未配置服务器");
    }
    let token = keychain::token()?.ok_or_else(|| anyhow!("尚未设置访问令牌"))?;
    let response = client()
        .post(format!(
            "{server_url}/v1/attachments/{attachment_id}/transcription/retry"
        ))
        .bearer_auth(token)
        .send()
        .await
        .context("retry transcription")?;
    let status = response.status();
    if !status.is_success() {
        let body = response.text().await.unwrap_or_default();
        return Err(api_error(status, &body));
    }
    Ok(())
}

pub fn data_url(path: &PathBuf) -> Result<String> {
    let bytes = std::fs::read(path).context("read local attachment")?;
    let media = match path
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .to_ascii_lowercase()
        .as_str()
    {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "wav" => "audio/wav",
        _ => "application/octet-stream",
    };
    let encoded = base64::engine::general_purpose::STANDARD.encode(bytes);
    Ok(format!("data:{media};base64,{encoded}"))
}

async fn get_json<T: for<'de> Deserialize<'de>>(
    server_url: &str,
    path: &str,
    token: &str,
) -> Result<T> {
    let response = client()
        .get(format!("{server_url}{path}"))
        .bearer_auth(token)
        .send()
        .await
        .with_context(|| format!("request {path}"))?;
    let status = response.status();
    let body = response.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(api_error(status, &body));
    }
    serde_json::from_str(&body).with_context(|| format!("decode {path} response"))
}

fn client() -> &'static Client {
    CLIENT.get_or_init(|| {
        Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(120))
            .user_agent("DailyPlan-Desktop/0.1")
            .build()
            .expect("reqwest client")
    })
}

fn api_error(status: StatusCode, body: &str) -> anyhow::Error {
    let message = serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|value| {
            value
                .get("error")
                .and_then(|error| error.get("message"))
                .and_then(Value::as_str)
                .map(ToString::to_string)
        })
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| body.chars().take(300).collect());
    anyhow!("服务器返回 {status}: {message}")
}

fn hex_digest(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn attachment_kind(attachment: &Attachment) -> &'static str {
    match attachment.kind {
        crate::models::AttachmentKind::Screenshot => "screenshot",
        crate::models::AttachmentKind::Recording => "recording",
    }
}

#[cfg(test)]
mod tests {
    use super::retry_delay;
    use std::time::Duration;

    #[test]
    fn retry_delay_doubles_and_stops_at_five_minutes() {
        assert_eq!(retry_delay(1), Duration::from_secs(30));
        assert_eq!(retry_delay(2), Duration::from_secs(60));
        assert_eq!(retry_delay(3), Duration::from_secs(120));
        assert_eq!(retry_delay(4), Duration::from_secs(240));
        assert_eq!(retry_delay(5), Duration::from_secs(300));
        assert_eq!(retry_delay(9), Duration::from_secs(300));
    }
}
