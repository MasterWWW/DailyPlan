use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use base64::Engine;
use image::{ImageFormat, RgbaImage};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Manager, PhysicalPosition, WebviewUrl, WebviewWindowBuilder};
use uuid::Uuid;

use crate::focus;
use crate::models::{CreateTaskInput, TitleSource};
use crate::state::AppState;

pub struct PendingCapture {
    pub png: Vec<u8>,
    pub image: RgbaImage,
    pub previous_pid: Option<i32>,
}

type MonitorCapture = (Vec<u8>, RgbaImage, (f64, f64, f64, f64));

#[derive(Debug, Clone, Copy, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectionRect {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectionImage {
    pub data_url: String,
    pub width: u32,
    pub height: u32,
}

pub fn begin(app: &AppHandle, state: &AppState) -> Result<()> {
    if app.get_webview_window("selection").is_some() {
        return Ok(());
    }
    let previous_pid = focus::frontmost_pid();
    if let Some(main) = app.get_webview_window("main") {
        let _ = main.hide();
    }
    std::thread::sleep(Duration::from_millis(120));
    let (png, image, monitor) = match capture_current_monitor(app) {
        Ok(capture) => capture,
        Err(error) => {
            if let Some(main) = app.get_webview_window("main") {
                let _ = main.show();
                let _ = main.set_focus();
            }
            return Err(error);
        }
    };
    *state
        .pending_capture
        .lock()
        .map_err(|_| anyhow!("截图状态不可用"))? = Some(PendingCapture {
        png,
        image,
        previous_pid,
    });
    let (x, y, width, height) = monitor;
    let window = WebviewWindowBuilder::new(
        app,
        "selection",
        WebviewUrl::App("index.html?view=selection".into()),
    )
    .title("选择截图区域")
    .position(x, y)
    .inner_size(width, height)
    .decorations(false)
    .resizable(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .shadow(false)
    .visible(true)
    .focused(true)
    .build()
    .context("create screenshot selection window")?;
    let _ = window.set_focus();
    Ok(())
}

pub fn image(state: &AppState) -> Result<SelectionImage> {
    let guard = state
        .pending_capture
        .lock()
        .map_err(|_| anyhow!("截图状态不可用"))?;
    let capture = guard.as_ref().ok_or_else(|| anyhow!("没有待选择的截图"))?;
    Ok(SelectionImage {
        data_url: format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(&capture.png)
        ),
        width: capture.image.width(),
        height: capture.image.height(),
    })
}

pub fn finish(
    app: &AppHandle,
    state: &AppState,
    rect: SelectionRect,
) -> Result<crate::models::Task> {
    let capture = state
        .pending_capture
        .lock()
        .map_err(|_| anyhow!("截图状态不可用"))?
        .take()
        .ok_or_else(|| anyhow!("截图已取消"))?;
    let previous_pid = capture.previous_pid;
    let result = save_selection(app, state, capture, rect);
    close_selection(app);
    std::thread::sleep(Duration::from_millis(40));
    if let Some(pid) = previous_pid {
        focus::activate_process(pid);
    }
    result
}

pub fn cancel(app: &AppHandle, state: &AppState) {
    let previous = state
        .pending_capture
        .lock()
        .ok()
        .and_then(|mut guard| guard.take())
        .and_then(|capture| capture.previous_pid);
    close_selection(app);
    if let Some(pid) = previous {
        focus::activate_process(pid);
    }
}

fn save_selection(
    _app: &AppHandle,
    state: &AppState,
    capture: PendingCapture,
    rect: SelectionRect,
) -> Result<crate::models::Task> {
    if rect.width < 0.004 || rect.height < 0.004 {
        bail!("选区太小，未创建待办");
    }
    let normalized_x = rect.x.clamp(0.0, 1.0);
    let normalized_y = rect.y.clamp(0.0, 1.0);
    let normalized_width = rect.width.clamp(0.0, 1.0 - normalized_x);
    let normalized_height = rect.height.clamp(0.0, 1.0 - normalized_y);
    let image_width = capture.image.width() as f64;
    let image_height = capture.image.height() as f64;
    let x = (normalized_x * image_width).floor() as u32;
    let y = (normalized_y * image_height).floor() as u32;
    let width = (normalized_width * image_width).round() as u32;
    let height = (normalized_height * image_height).round() as u32;
    if width < 4
        || height < 4
        || x + width > capture.image.width()
        || y + height > capture.image.height()
    {
        bail!("选区无效");
    }
    let cropped = image::imageops::crop_imm(&capture.image, x, y, width, height).to_image();
    let mut png = Vec::new();
    cropped
        .write_to(&mut std::io::Cursor::new(&mut png), ImageFormat::Png)
        .context("encode screenshot")?;
    let attachment_id = Uuid::new_v4().to_string();
    let directory = state.db.root().join("attachments");
    std::fs::create_dir_all(&directory)?;
    let path = directory.join(format!("{attachment_id}.png"));
    let temp = directory.join(format!("{attachment_id}.png.tmp"));
    std::fs::write(&temp, &png)?;
    std::fs::rename(&temp, &path)?;
    let digest = Sha256::digest(&png);
    let settings = state.db.settings()?;
    let timezone: chrono_tz::Tz = settings
        .time_zone
        .parse()
        .map_err(|_| anyhow!("时区无效"))?;
    let now = chrono::Utc::now();
    let title = format!("截图 · {}", now.with_timezone(&timezone).format("%H:%M"));
    let task = state.db.create_task(
        CreateTaskInput {
            title,
            due_date: None,
            due_time: None,
            priority: None,
        },
        Some(crate::db::CreateAttachment {
            attachment_id,
            kind: crate::models::AttachmentKind::Screenshot,
            media_type: "image/png".to_string(),
            byte_size: png.len() as i64,
            sha256: digest.iter().map(|byte| format!("{byte:02x}")).collect(),
            local_path: path.to_string_lossy().to_string(),
            created_at: now,
        }),
        TitleSource::ScreenshotPlaceholder,
    );
    if task.is_err() {
        let _ = std::fs::remove_file(path);
    }
    task
}

fn close_selection(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("selection") {
        let _ = window.close();
    }
}

#[cfg(target_os = "macos")]
fn capture_current_monitor(app: &AppHandle) -> Result<MonitorCapture> {
    use std::process::Command;

    let cursor = app
        .cursor_position()
        .unwrap_or(PhysicalPosition::new(0.0, 0.0));
    let monitors = app.available_monitors().context("list monitors")?;
    let monitor_index = monitors
        .iter()
        .position(|monitor| {
            let position = monitor.position();
            let size = monitor.size();
            cursor.x >= f64::from(position.x)
                && cursor.x < f64::from(position.x) + f64::from(size.width)
                && cursor.y >= f64::from(position.y)
                && cursor.y < f64::from(position.y) + f64::from(size.height)
        })
        .unwrap_or(0);
    let monitor = monitors
        .get(monitor_index)
        .ok_or_else(|| anyhow!("没有可用显示器"))?;
    let scale = monitor.scale_factor();
    let position = monitor.position().to_logical::<f64>(scale);
    let size = monitor.size().to_logical::<f64>(scale);
    let path = std::env::temp_dir().join(format!("dailyplan-capture-{}.png", Uuid::new_v4()));
    let output = Command::new("/usr/sbin/screencapture")
        .args([
            "-x",
            "-D",
            &(monitor_index + 1).to_string(),
            path.to_string_lossy().as_ref(),
        ])
        .output()
        .context("运行系统截图工具")?;
    if !output.status.success() {
        let _ = std::fs::remove_file(&path);
        let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if detail.is_empty() {
            bail!(
                "屏幕捕捉失败；请在“系统设置 > 隐私与安全性 > 屏幕与系统音频录制”中允许 DailyPlan"
            );
        }
        bail!("屏幕捕捉失败：{detail}");
    }
    let png = std::fs::read(&path).context("读取系统截图")?;
    let _ = std::fs::remove_file(&path);
    let image = image::load_from_memory_with_format(&png, ImageFormat::Png)
        .context("解码系统截图")?
        .into_rgba8();
    Ok((
        png,
        image,
        (position.x, position.y, size.width, size.height),
    ))
}

#[cfg(not(target_os = "macos"))]
fn capture_current_monitor(_app: &AppHandle) -> Result<MonitorCapture> {
    bail!("当前平台暂不支持截图")
}
