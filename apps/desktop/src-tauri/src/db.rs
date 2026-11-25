use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{anyhow, bail, Context, Result};
use chrono::{DateTime, NaiveDate, NaiveTime, SecondsFormat, Utc};
use chrono_tz::Tz;
use rusqlite::{params, Connection, OptionalExtension, Row, Transaction};
use serde_json::{json, Value};
use uuid::Uuid;

use crate::models::{
    AppSettings, Attachment, AttachmentKind, CreateTaskInput, LocalAttachment, Priority,
    SettingsInput, Snapshot, Status, Task, TaskUpdateInput, TaskView, TitleSource, TodayView,
    WorkSettings,
};
use crate::rules::{self, AcknowledgedReminder, Evaluation, Reminder};

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS work_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  workdays TEXT NOT NULL,
  time_zone TEXT NOT NULL,
  work_start TEXT NOT NULL,
  work_end TEXT NOT NULL,
  reminder_lead_minutes INTEGER NOT NULL,
  server_url TEXT NOT NULL DEFAULT '',
  text_shortcut TEXT NOT NULL,
  screenshot_shortcut TEXT NOT NULL,
  autostart INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tasks (
  task_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  title_source TEXT NOT NULL,
  created_on TEXT NOT NULL,
  due_date TEXT NOT NULL,
  due_time TEXT,
  priority TEXT NOT NULL,
  completed INTEGER NOT NULL DEFAULT 0,
  completed_at TEXT,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS attachments (
  attachment_id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL REFERENCES tasks(task_id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  media_type TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  local_path TEXT,
  remote_ready INTEGER NOT NULL DEFAULT 0,
  transcription_status TEXT,
  transcript TEXT,
  transcription_error TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pending_operations (
  local_order INTEGER PRIMARY KEY AUTOINCREMENT,
  op_id TEXT NOT NULL UNIQUE,
  target_id TEXT NOT NULL,
  dirty_fields TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  cursor INTEGER NOT NULL DEFAULT 0,
  dataset_id TEXT,
  restore_version INTEGER,
  last_success_at TEXT,
  last_error TEXT
);

CREATE TABLE IF NOT EXISTS reminder_events (
  work_date TEXT NOT NULL,
  period TEXT NOT NULL,
  shown_at TEXT NOT NULL,
  acknowledged_at TEXT,
  PRIMARY KEY (work_date, period)
);

CREATE INDEX IF NOT EXISTS idx_tasks_due ON tasks(completed, due_date, due_time);
CREATE INDEX IF NOT EXISTS idx_attachments_task ON attachments(task_id);
CREATE INDEX IF NOT EXISTS idx_pending_target ON pending_operations(target_id);
"#;

pub struct Db {
    conn: Mutex<Connection>,
    root: PathBuf,
}

pub struct CreateAttachment {
    pub attachment_id: String,
    pub kind: AttachmentKind,
    pub media_type: String,
    pub byte_size: i64,
    pub sha256: String,
    pub local_path: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone)]
pub struct PendingOperation {
    pub op_id: String,
    pub target_id: String,
    pub dirty_fields: Vec<String>,
    pub payload: Value,
}

#[derive(Debug, Clone)]
pub struct SyncMeta {
    pub cursor: i64,
    pub dataset_id: Option<String>,
    pub restore_version: Option<i64>,
}

impl Db {
    pub fn open(root: &Path) -> Result<Self> {
        std::fs::create_dir_all(root).context("create app data directory")?;
        std::fs::create_dir_all(root.join("attachments")).context("create attachment directory")?;
        let conn =
            Connection::open(root.join("dailyplan.sqlite3")).context("open local database")?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.execute_batch(SCHEMA)?;
        conn.execute(
            "INSERT OR IGNORE INTO meta(key, value) VALUES ('device_id', ?1)",
            [Uuid::new_v4().to_string()],
        )?;
        conn.execute(
            "INSERT OR IGNORE INTO work_settings
              (id, workdays, time_zone, work_start, work_end, reminder_lead_minutes,
               server_url, text_shortcut, screenshot_shortcut, autostart)
             VALUES (1, '[1,2,3,4,5]', 'Asia/Shanghai', '09:00', '18:00', 15, '',
                     'CommandOrControl+Shift+Space', 'CommandOrControl+Shift+Enter', 0)",
            [],
        )?;
        conn.execute(
            "INSERT OR IGNORE INTO sync_state(id, cursor) VALUES (1, 0)",
            [],
        )?;
        conn.pragma_update(None, "user_version", 1)?;
        Ok(Self {
            conn: Mutex::new(conn),
            root: root.to_path_buf(),
        })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn settings(&self) -> Result<WorkSettings> {
        let conn = self.conn.lock().map_err(poisoned)?;
        read_work_settings(&conn)
    }

    pub fn app_settings(&self, has_token: bool) -> Result<AppSettings> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let work = read_work_settings(&conn)?;
        let (server_url, text_shortcut, screenshot_shortcut, autostart) = conn
            .query_row(
                "SELECT server_url, text_shortcut, screenshot_shortcut, autostart
                 FROM work_settings WHERE id = 1",
                [],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, bool>(3)?,
                    ))
                },
            )
            .context("read app settings")?;
        Ok(AppSettings {
            work,
            server_url,
            text_shortcut,
            screenshot_shortcut,
            autostart,
            has_token,
        })
    }

    pub fn today_view(&self, has_token: bool) -> Result<TodayView> {
        let now = Utc::now().fixed_offset();
        let conn = self.conn.lock().map_err(poisoned)?;
        let settings = read_work_settings(&conn)?;
        let tasks = read_tasks(&conn, false)?;
        let acknowledged = acknowledged_reminders(&conn)?;
        let evaluation = rules::evaluate(now, &tasks, &settings, &acknowledged)?;
        let mut visible = Vec::with_capacity(evaluation.visible.len());
        for (task, attention) in evaluation.visible.iter().zip(evaluation.attention.iter()) {
            visible.push(TaskView {
                task: task.clone(),
                overdue: attention.overdue,
                due_soon: attention.due_soon,
                attachments: read_attachments_for(&conn, &task.task_id)?,
            });
        }
        let mut completed = Vec::new();
        for task in read_tasks(&conn, true)?
            .into_iter()
            .filter(|task| task.completed)
            .take(100)
        {
            completed.push(TaskView {
                task: task.clone(),
                overdue: false,
                due_soon: false,
                attachments: read_attachments_for(&conn, &task.task_id)?,
            });
        }
        let (server_url, pending_count, last_success_at, last_error) = conn.query_row(
            "SELECT
               (SELECT server_url FROM work_settings WHERE id = 1),
               (SELECT COUNT(*) FROM pending_operations) +
                 (SELECT COUNT(*) FROM attachments WHERE remote_ready = 0),
               (SELECT last_success_at FROM sync_state WHERE id = 1),
               (SELECT last_error FROM sync_state WHERE id = 1)",
            [],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, Option<String>>(2)?,
                    row.get::<_, Option<String>>(3)?,
                ))
            },
        )?;
        let last_success_at = last_success_at.as_deref().and_then(parse_utc_optional);
        Ok(TodayView {
            tasks: visible,
            completed,
            settings: settings.clone(),
            sync_configured: !server_url.trim().is_empty() && has_token,
            server_url,
            pending_count,
            last_success_at,
            last_error,
            has_token,
        })
    }

    pub fn evaluation(&self, now: DateTime<chrono::FixedOffset>) -> Result<Evaluation> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let settings = read_work_settings(&conn)?;
        let tasks = read_tasks(&conn, false)?;
        let acknowledged = acknowledged_reminders(&conn)?;
        Ok(rules::evaluate(now, &tasks, &settings, &acknowledged)?)
    }

    pub fn create_task(
        &self,
        input: CreateTaskInput,
        attachment: Option<CreateAttachment>,
        title_source: TitleSource,
    ) -> Result<Task> {
        let title = input.title.trim().to_string();
        if title.is_empty() || title.chars().count() > 500 {
            bail!("标题需要 1 到 500 个字符");
        }
        let now = Utc::now();
        let mut conn = self.conn.lock().map_err(poisoned)?;
        let settings = read_work_settings(&conn)?;
        let timezone: Tz = settings
            .time_zone
            .parse()
            .map_err(|_| anyhow!("时区无效"))?;
        let today = now.with_timezone(&timezone).date_naive();
        let created_on = today.format("%Y-%m-%d").to_string();
        let due_date = input.due_date.unwrap_or_else(|| created_on.clone());
        validate_date(&due_date)?;
        validate_optional_clock(input.due_time.as_deref())?;
        let task = Task {
            task_id: Uuid::new_v4().to_string(),
            title,
            title_source,
            created_on,
            due_date,
            due_time: input.due_time,
            priority: input.priority.unwrap_or_default(),
            completed: false,
            completed_at: None,
            updated_at: now,
        };
        let tx = conn.transaction()?;
        insert_task(&tx, &task)?;
        queue_operation(
            &tx,
            &task.task_id,
            &["task"],
            json!({
                "opId": Uuid::new_v4().to_string(),
                "deviceId": read_device_id(&tx)?,
                "type": "task.create",
                "occurredAt": format_utc(now),
                "task": task.clone(),
            }),
        )?;
        if let Some(attachment) = attachment {
            insert_attachment(&tx, &task.task_id, &attachment, false)?;
        }
        tx.commit()?;
        Ok(task)
    }

    pub fn update_task(&self, input: TaskUpdateInput) -> Result<Task> {
        let now = Utc::now();
        let mut conn = self.conn.lock().map_err(poisoned)?;
        let tx = conn.transaction()?;
        let mut task = read_task_tx(&tx, &input.task_id)?.ok_or_else(|| anyhow!("待办不存在"))?;
        let mut set = serde_json::Map::new();
        let mut dirty = Vec::new();
        if let Some(title) = input.title {
            let title = title.trim().to_string();
            if title.is_empty() || title.chars().count() > 500 {
                bail!("标题需要 1 到 500 个字符");
            }
            task.title = title.clone();
            task.title_source = TitleSource::Manual;
            set.insert("title".to_string(), Value::String(title));
            set.insert(
                "titleSource".to_string(),
                Value::String("manual".to_string()),
            );
            dirty.push("title");
            dirty.push("titleSource");
        }
        if let Some(due_date) = input.due_date {
            validate_date(&due_date)?;
            task.due_date = due_date.clone();
            set.insert("dueDate".to_string(), Value::String(due_date));
            dirty.push("dueDate");
        }
        if input.clear_due_time {
            task.due_time = None;
            set.insert("dueTime".to_string(), Value::Null);
            dirty.push("dueTime");
        } else if let Some(due_time) = input.due_time {
            validate_optional_clock(Some(&due_time))?;
            task.due_time = Some(due_time.clone());
            set.insert("dueTime".to_string(), Value::String(due_time));
            dirty.push("dueTime");
        }
        if let Some(priority) = input.priority {
            task.priority = priority;
            set.insert("priority".to_string(), json!(priority));
            dirty.push("priority");
        }
        if let Some(completed) = input.completed {
            task.completed = completed;
            if completed {
                task.completed_at = Some(now);
            } else {
                task.completed_at = None;
            }
            set.insert("completed".to_string(), Value::Bool(completed));
            dirty.push("completed");
        }
        if set.is_empty() {
            return Ok(task);
        }
        task.updated_at = now;
        update_task_row(&tx, &task)?;
        queue_operation(
            &tx,
            &task.task_id,
            &dirty,
            json!({
                "opId": Uuid::new_v4().to_string(),
                "deviceId": read_device_id(&tx)?,
                "type": "task.update",
                "occurredAt": format_utc(now),
                "taskId": task.task_id,
                "set": Value::Object(set),
                "fallback": task.clone(),
            }),
        )?;
        tx.commit()?;
        Ok(task)
    }

    pub fn save_settings(&self, input: SettingsInput) -> Result<()> {
        let work = WorkSettings {
            workdays: input.workdays,
            time_zone: input.time_zone,
            work_start: input.work_start,
            work_end: input.work_end,
            reminder_lead_minutes: input.reminder_lead_minutes,
        };
        validate_work_settings(&work)?;
        validate_server_url(&input.server_url)?;
        let server_url = input.server_url.trim().trim_end_matches('/').to_string();
        let now = Utc::now();
        let mut conn = self.conn.lock().map_err(poisoned)?;
        let tx = conn.transaction()?;
        tx.execute(
            "UPDATE work_settings SET workdays = ?1, time_zone = ?2, work_start = ?3,
               work_end = ?4, reminder_lead_minutes = ?5, server_url = ?6,
               text_shortcut = ?7, screenshot_shortcut = ?8, autostart = ?9 WHERE id = 1",
            params![
                serde_json::to_string(&work.workdays)?,
                work.time_zone,
                work.work_start,
                work.work_end,
                work.reminder_lead_minutes,
                server_url,
                input.text_shortcut.trim(),
                input.screenshot_shortcut.trim(),
                input.autostart,
            ],
        )?;
        queue_operation(
            &tx,
            "settings",
            &["settings"],
            json!({
                "opId": Uuid::new_v4().to_string(),
                "deviceId": read_device_id(&tx)?,
                "type": "settings.update",
                "occurredAt": format_utc(now),
                "settings": work,
            }),
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn server_config(&self) -> Result<(String, String)> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let server_url = conn
            .query_row(
                "SELECT server_url FROM work_settings WHERE id = 1",
                [],
                |row| row.get::<_, String>(0),
            )
            .context("read server url")?;
        let device_id = conn
            .query_row(
                "SELECT value FROM meta WHERE key = 'device_id'",
                [],
                |row| row.get::<_, String>(0),
            )
            .context("read device id")?;
        Ok((server_url, device_id))
    }

    pub fn shortcut_settings(&self) -> Result<(String, String)> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.query_row(
            "SELECT text_shortcut, screenshot_shortcut FROM work_settings WHERE id = 1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .context("read shortcuts")
    }

    pub fn pending_operations(&self) -> Result<Vec<PendingOperation>> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let mut statement = conn.prepare(
            "SELECT op_id, target_id, dirty_fields, payload_json
             FROM pending_operations ORDER BY local_order",
        )?;
        let rows = statement.query_map([], read_pending_row)?;
        rows.collect::<rusqlite::Result<Vec<_>>>()
            .map_err(Into::into)
    }

    #[cfg(test)]
    pub fn pending_count(&self) -> Result<i64> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.query_row(
            "SELECT (SELECT COUNT(*) FROM pending_operations) +
                    (SELECT COUNT(*) FROM attachments WHERE remote_ready = 0)",
            [],
            |row| row.get(0),
        )
        .map_err(Into::into)
    }

    pub fn complete_operation(&self, op_id: &str) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.execute("DELETE FROM pending_operations WHERE op_id = ?1", [op_id])?;
        Ok(())
    }

    pub fn sync_meta(&self) -> Result<SyncMeta> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.query_row(
            "SELECT cursor, dataset_id, restore_version, last_success_at, last_error
             FROM sync_state WHERE id = 1",
            [],
            |row| {
                Ok(SyncMeta {
                    cursor: row.get(0)?,
                    dataset_id: row.get(1)?,
                    restore_version: row.get(2)?,
                })
            },
        )
        .map_err(Into::into)
    }

    pub fn mark_sync_success(&self, status: &Status, cursor: i64) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.execute(
            "UPDATE sync_state SET cursor = ?1, dataset_id = ?2, restore_version = ?3,
               last_success_at = ?4, last_error = NULL WHERE id = 1",
            params![
                cursor,
                status.dataset_id,
                status.restore_version,
                format_utc(Utc::now())
            ],
        )?;
        Ok(())
    }

    pub fn mark_sync_cursor(&self, status: &Status, cursor: i64) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.execute(
            "UPDATE sync_state SET cursor = ?1, dataset_id = ?2, restore_version = ?3
             WHERE id = 1",
            params![cursor, status.dataset_id, status.restore_version],
        )?;
        Ok(())
    }

    pub fn mark_sync_error(&self, message: &str) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.execute(
            "UPDATE sync_state SET last_error = ?1 WHERE id = 1",
            [message.chars().take(500).collect::<String>()],
        )?;
        Ok(())
    }

    pub fn apply_snapshot(&self, snapshot: Snapshot, dataset_id: &str) -> Result<()> {
        let now = format_utc(Utc::now());
        let mut conn = self.conn.lock().map_err(poisoned)?;
        let tx = conn.transaction()?;
        let pending = read_pending(&tx)?;
        let local_tasks = read_tasks_tx(&tx)?
            .into_iter()
            .map(|task| (task.task_id.clone(), task))
            .collect::<HashMap<_, _>>();
        let local_attachments = read_all_attachments(&tx)?;
        let local_paths = local_attachments
            .iter()
            .filter_map(|attachment| {
                attachment
                    .local_path
                    .as_ref()
                    .map(|path| (attachment.attachment.attachment_id.clone(), path.clone()))
            })
            .collect::<HashMap<_, _>>();
        tx.execute("DELETE FROM pending_operations", [])?;
        tx.execute("DELETE FROM attachments", [])?;
        tx.execute("DELETE FROM tasks", [])?;
        for task in &snapshot.tasks {
            insert_task(&tx, task)?;
        }
        write_work_settings(&tx, &snapshot.settings)?;
        for attachment in &snapshot.attachments {
            let local_path = local_paths.get(&attachment.attachment_id).cloned();
            insert_remote_attachment(&tx, attachment, local_path.as_deref())?;
        }
        for task in local_tasks.values() {
            let has_pending_create = pending.iter().any(|operation| {
                operation.target_id == task.task_id
                    && operation.payload.get("type") == Some(&json!("task.create"))
            });
            if has_pending_create && read_task_tx(&tx, &task.task_id)?.is_none() {
                insert_task(&tx, task)?;
                for attachment in local_attachments
                    .iter()
                    .filter(|attachment| attachment.attachment.task_id == task.task_id)
                {
                    insert_local_attachment(&tx, attachment)?;
                }
            }
        }
        for operation in &pending {
            reapply_pending(&tx, operation, &local_tasks)?;
            queue_operation_raw(&tx, operation)?;
        }
        for attachment in &local_attachments {
            if attachment.local_path.is_none() {
                continue;
            }
            let exists: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM attachments WHERE attachment_id = ?1)",
                [&attachment.attachment.attachment_id],
                |row| row.get(0),
            )?;
            if !exists && read_task_tx(&tx, &attachment.attachment.task_id)?.is_some() {
                insert_local_attachment(&tx, attachment)?;
            }
        }
        tx.execute(
            "UPDATE sync_state SET cursor = ?1, dataset_id = ?2, restore_version = ?3,
               last_success_at = ?4, last_error = NULL WHERE id = 1",
            params![snapshot.cursor, dataset_id, snapshot.restore_version, now],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn apply_changes_page(
        &self,
        changes: &[crate::models::Change],
        next_cursor: i64,
    ) -> Result<()> {
        let mut conn = self.conn.lock().map_err(poisoned)?;
        let tx = conn.transaction()?;
        let pending = read_pending(&tx)?;
        for change in changes {
            if change.change_type == "task.deleted" {
                if let Some(task) = &change.task {
                    tx.execute(
                        "DELETE FROM pending_operations WHERE target_id = ?1",
                        [task.task_id.as_str()],
                    )?;
                    tx.execute(
                        "DELETE FROM tasks WHERE task_id = ?1",
                        [task.task_id.as_str()],
                    )?;
                }
                continue;
            }
            if let Some(task) = &change.task {
                let dirty = dirty_fields_for(&pending, &task.task_id);
                let local = read_task_tx(&tx, &task.task_id)?;
                let merged = merge_remote_task(local, task, &dirty);
                insert_task(&tx, &merged)?;
            }
            if let Some(settings) = &change.settings {
                if !pending
                    .iter()
                    .any(|operation| operation.target_id == "settings")
                {
                    write_work_settings(&tx, settings)?;
                }
            }
            if let Some(attachment) = &change.attachment {
                let local_path = read_attachment_path(&tx, &attachment.attachment_id)?;
                insert_remote_attachment(&tx, attachment, local_path.as_deref())?;
            }
        }
        tx.execute(
            "UPDATE sync_state SET cursor = ?1 WHERE id = 1",
            [next_cursor],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn pending_upload_attachments(&self) -> Result<Vec<LocalAttachment>> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let mut statement = conn.prepare(
            "SELECT a.attachment_id, a.task_id, a.kind, a.media_type, a.byte_size,
                    a.sha256, a.created_at, a.transcription_status, a.transcript,
                    a.transcription_error, a.local_path, a.remote_ready
             FROM attachments a
             JOIN tasks t ON t.task_id = a.task_id
             WHERE a.remote_ready = 0
             ORDER BY a.created_at",
        )?;
        let rows = statement.query_map([], read_local_attachment_row)?;
        rows.collect::<rusqlite::Result<Vec<_>>>()
            .map_err(Into::into)
    }

    pub fn mark_attachment_remote(&self, attachment: &Attachment) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let local_path = read_attachment_path(&conn, &attachment.attachment_id)?;
        insert_remote_attachment(&conn, attachment, local_path.as_deref())
    }

    pub fn attachment(&self, attachment_id: &str) -> Result<Option<LocalAttachment>> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.query_row(
            "SELECT attachment_id, task_id, kind, media_type, byte_size, sha256, created_at,
                    transcription_status, transcript, transcription_error, local_path, remote_ready
             FROM attachments WHERE attachment_id = ?1",
            [attachment_id],
            read_local_attachment_row,
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn set_attachment_local_path(&self, attachment_id: &str, path: &str) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.execute(
            "UPDATE attachments SET local_path = ?1 WHERE attachment_id = ?2",
            params![path, attachment_id],
        )?;
        Ok(())
    }

    pub fn acknowledge_reminder(&self, reminder: &Reminder) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let period = match reminder.period {
            rules::ReminderPeriod::Morning => "morning",
            rules::ReminderPeriod::Evening => "evening",
        };
        conn.execute(
            "INSERT INTO reminder_events(work_date, period, shown_at, acknowledged_at)
             VALUES (?1, ?2, ?3, ?3)
             ON CONFLICT(work_date, period) DO UPDATE SET acknowledged_at = excluded.acknowledged_at",
            params![reminder.work_date, period, format_utc(Utc::now())],
        )?;
        Ok(())
    }

    pub fn mark_reminder_shown(&self, reminder: &Reminder) -> Result<()> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let period = match reminder.period {
            rules::ReminderPeriod::Morning => "morning",
            rules::ReminderPeriod::Evening => "evening",
        };
        conn.execute(
            "INSERT OR IGNORE INTO reminder_events(work_date, period, shown_at)
             VALUES (?1, ?2, ?3)",
            params![reminder.work_date, period, format_utc(Utc::now())],
        )?;
        Ok(())
    }

    pub fn reminder_was_shown(&self, work_date: &str, period: &str) -> Result<bool> {
        let conn = self.conn.lock().map_err(poisoned)?;
        conn.query_row(
            "SELECT EXISTS(
               SELECT 1 FROM reminder_events
               WHERE work_date = ?1 AND period = ?2 AND shown_at IS NOT NULL
             )",
            params![work_date, period],
            |row| row.get(0),
        )
        .map_err(Into::into)
    }

    pub fn task_views(&self, task_ids: &[String]) -> Result<Vec<TaskView>> {
        let conn = self.conn.lock().map_err(poisoned)?;
        let now = Utc::now().fixed_offset();
        let settings = read_work_settings(&conn)?;
        let acknowledged = acknowledged_reminders(&conn)?;
        let tasks = read_tasks(&conn, false)?;
        let evaluation = rules::evaluate(now, &tasks, &settings, &acknowledged)?;
        let wanted = task_ids.iter().collect::<HashSet<_>>();
        let mut result = Vec::new();
        for (task, attention) in evaluation.visible.iter().zip(evaluation.attention.iter()) {
            if wanted.contains(&task.task_id) {
                result.push(TaskView {
                    task: task.clone(),
                    overdue: attention.overdue,
                    due_soon: attention.due_soon,
                    attachments: read_attachments_for(&conn, &task.task_id)?,
                });
            }
        }
        Ok(result)
    }

    pub fn attachment_file_path(&self, attachment_id: &str) -> Result<Option<PathBuf>> {
        let Some(attachment) = self.attachment(attachment_id)? else {
            return Ok(None);
        };
        Ok(attachment.local_path.map(PathBuf::from))
    }
}

fn poisoned<T>(_error: std::sync::PoisonError<T>) -> anyhow::Error {
    anyhow!("本地数据库锁已损坏")
}

fn read_work_settings(conn: &Connection) -> Result<WorkSettings> {
    conn.query_row(
        "SELECT workdays, time_zone, work_start, work_end, reminder_lead_minutes
         FROM work_settings WHERE id = 1",
        [],
        |row| {
            let workdays: String = row.get(0)?;
            Ok(WorkSettings {
                workdays: serde_json::from_str(&workdays).unwrap_or_else(|_| vec![1, 2, 3, 4, 5]),
                time_zone: row.get(1)?,
                work_start: row.get(2)?,
                work_end: row.get(3)?,
                reminder_lead_minutes: row.get(4)?,
            })
        },
    )
    .context("read work settings")
}

fn write_work_settings(tx: &Transaction<'_>, settings: &WorkSettings) -> Result<()> {
    tx.execute(
        "UPDATE work_settings SET workdays = ?1, time_zone = ?2, work_start = ?3,
           work_end = ?4, reminder_lead_minutes = ?5 WHERE id = 1",
        params![
            serde_json::to_string(&settings.workdays)?,
            settings.time_zone,
            settings.work_start,
            settings.work_end,
            settings.reminder_lead_minutes
        ],
    )?;
    Ok(())
}

fn read_tasks(conn: &Connection, completed: bool) -> Result<Vec<Task>> {
    let mut statement = conn.prepare(
        "SELECT task_id, title, title_source, created_on, due_date, due_time, priority,
                completed, completed_at, updated_at
         FROM tasks WHERE completed = ?1
         ORDER BY CASE WHEN completed = 0 THEN due_date END, due_time, updated_at DESC",
    )?;
    let rows = statement.query_map([completed], read_task_row)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(Into::into)
}

fn read_tasks_tx(tx: &Transaction<'_>) -> Result<Vec<Task>> {
    let mut statement = tx.prepare(
        "SELECT task_id, title, title_source, created_on, due_date, due_time, priority,
                completed, completed_at, updated_at FROM tasks",
    )?;
    let rows = statement.query_map([], read_task_row)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(Into::into)
}

fn read_task_row(row: &Row<'_>) -> rusqlite::Result<Task> {
    let title_source: String = row.get(2)?;
    let priority: String = row.get(6)?;
    let completed_at: Option<String> = row.get(8)?;
    let updated_at: String = row.get(9)?;
    Ok(Task {
        task_id: row.get(0)?,
        title: row.get(1)?,
        title_source: parse_title_source(&title_source),
        created_on: row.get(3)?,
        due_date: row.get(4)?,
        due_time: row.get(5)?,
        priority: parse_priority(&priority),
        completed: row.get(7)?,
        completed_at: completed_at.as_deref().and_then(parse_utc_optional),
        updated_at: parse_utc_optional(&updated_at)
            .unwrap_or_else(|| DateTime::from_timestamp(0, 0).expect("epoch")),
    })
}

fn read_task_tx(tx: &Transaction<'_>, task_id: &str) -> Result<Option<Task>> {
    tx.query_row(
        "SELECT task_id, title, title_source, created_on, due_date, due_time, priority,
                completed, completed_at, updated_at FROM tasks WHERE task_id = ?1",
        [task_id],
        read_task_row,
    )
    .optional()
    .map_err(Into::into)
}

fn insert_task(tx: &Transaction<'_>, task: &Task) -> Result<()> {
    tx.execute(
        "INSERT INTO tasks(task_id, title, title_source, created_on, due_date, due_time,
           priority, completed, completed_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
         ON CONFLICT(task_id) DO UPDATE SET title = excluded.title,
           title_source = excluded.title_source, created_on = excluded.created_on,
           due_date = excluded.due_date, due_time = excluded.due_time,
           priority = excluded.priority, completed = excluded.completed,
           completed_at = excluded.completed_at, updated_at = excluded.updated_at",
        params![
            task.task_id,
            task.title,
            title_source_value(task.title_source),
            task.created_on,
            task.due_date,
            task.due_time,
            priority_value(task.priority),
            task.completed,
            task.completed_at.map(format_utc),
            format_utc(task.updated_at)
        ],
    )?;
    Ok(())
}

fn update_task_row(tx: &Transaction<'_>, task: &Task) -> Result<()> {
    tx.execute(
        "UPDATE tasks SET title = ?1, title_source = ?2, created_on = ?3, due_date = ?4,
           due_time = ?5, priority = ?6, completed = ?7, completed_at = ?8, updated_at = ?9
         WHERE task_id = ?10",
        params![
            task.title,
            title_source_value(task.title_source),
            task.created_on,
            task.due_date,
            task.due_time,
            priority_value(task.priority),
            task.completed,
            task.completed_at.map(format_utc),
            format_utc(task.updated_at),
            task.task_id
        ],
    )?;
    Ok(())
}

fn insert_attachment(
    tx: &Transaction<'_>,
    task_id: &str,
    attachment: &CreateAttachment,
    remote_ready: bool,
) -> Result<()> {
    tx.execute(
        "INSERT INTO attachments(attachment_id, task_id, kind, media_type, byte_size, sha256,
           local_path, remote_ready, transcription_status, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
        params![
            attachment.attachment_id,
            task_id,
            attachment_kind_value(attachment.kind),
            attachment.media_type,
            attachment.byte_size,
            attachment.sha256,
            attachment.local_path,
            remote_ready,
            if attachment.kind == AttachmentKind::Recording {
                Some("pending")
            } else {
                None
            },
            format_utc(attachment.created_at)
        ],
    )?;
    Ok(())
}

fn insert_local_attachment(tx: &Transaction<'_>, attachment: &LocalAttachment) -> Result<()> {
    tx.execute(
        "INSERT OR REPLACE INTO attachments(attachment_id, task_id, kind, media_type, byte_size,
           sha256, local_path, remote_ready, transcription_status, transcript,
           transcription_error, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
        params![
            attachment.attachment.attachment_id,
            attachment.attachment.task_id,
            attachment_kind_value(attachment.attachment.kind),
            attachment.attachment.media_type,
            attachment.attachment.byte_size,
            attachment.attachment.sha256,
            attachment.local_path,
            attachment.remote_ready,
            attachment.attachment.transcription_status,
            attachment.attachment.transcript,
            attachment.attachment.transcription_error,
            format_utc(attachment.attachment.created_at)
        ],
    )?;
    Ok(())
}

fn insert_remote_attachment(
    conn: &Connection,
    attachment: &Attachment,
    local_path: Option<&str>,
) -> Result<()> {
    conn.execute(
        "INSERT INTO attachments(attachment_id, task_id, kind, media_type, byte_size, sha256,
           local_path, remote_ready, transcription_status, transcript, transcription_error, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 1, ?8, ?9, ?10, ?11)
         ON CONFLICT(attachment_id) DO UPDATE SET task_id = excluded.task_id,
           kind = excluded.kind, media_type = excluded.media_type,
           byte_size = excluded.byte_size, sha256 = excluded.sha256,
           local_path = COALESCE(attachments.local_path, excluded.local_path),
           remote_ready = 1, transcription_status = excluded.transcription_status,
           transcript = excluded.transcript, transcription_error = excluded.transcription_error,
           created_at = excluded.created_at",
        params![
            attachment.attachment_id,
            attachment.task_id,
            attachment_kind_value(attachment.kind),
            attachment.media_type,
            attachment.byte_size,
            attachment.sha256,
            local_path,
            attachment.transcription_status,
            attachment.transcript,
            attachment.transcription_error,
            format_utc(attachment.created_at)
        ],
    )?;
    Ok(())
}

fn read_attachments_for(conn: &Connection, task_id: &str) -> Result<Vec<LocalAttachment>> {
    let mut statement = conn.prepare(
        "SELECT attachment_id, task_id, kind, media_type, byte_size, sha256, created_at,
                transcription_status, transcript, transcription_error, local_path, remote_ready
         FROM attachments WHERE task_id = ?1 ORDER BY created_at",
    )?;
    let rows = statement.query_map([task_id], read_local_attachment_row)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(Into::into)
}

fn read_all_attachments(conn: &Connection) -> Result<Vec<LocalAttachment>> {
    let mut statement = conn.prepare(
        "SELECT attachment_id, task_id, kind, media_type, byte_size, sha256, created_at,
                transcription_status, transcript, transcription_error, local_path, remote_ready
         FROM attachments ORDER BY created_at",
    )?;
    let rows = statement.query_map([], read_local_attachment_row)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(Into::into)
}

fn read_local_attachment_row(row: &Row<'_>) -> rusqlite::Result<LocalAttachment> {
    let kind: String = row.get(2)?;
    let created_at: String = row.get(6)?;
    Ok(LocalAttachment {
        attachment: Attachment {
            attachment_id: row.get(0)?,
            task_id: row.get(1)?,
            kind: parse_attachment_kind(&kind),
            media_type: row.get(3)?,
            byte_size: row.get(4)?,
            sha256: row.get(5)?,
            created_at: parse_utc_optional(&created_at)
                .unwrap_or_else(|| DateTime::from_timestamp(0, 0).expect("epoch")),
            transcription_status: row.get(7)?,
            transcript: row.get(8)?,
            transcription_error: row.get(9)?,
        },
        local_path: row.get(10)?,
        remote_ready: row.get(11)?,
    })
}

fn read_attachment_path(conn: &Connection, attachment_id: &str) -> Result<Option<String>> {
    conn.query_row(
        "SELECT local_path FROM attachments WHERE attachment_id = ?1",
        [attachment_id],
        |row| row.get(0),
    )
    .optional()
    .map(|value| value.flatten())
    .map_err(Into::into)
}

fn read_pending(tx: &Transaction<'_>) -> Result<Vec<PendingOperation>> {
    let mut statement = tx.prepare(
        "SELECT op_id, target_id, dirty_fields, payload_json
         FROM pending_operations ORDER BY local_order",
    )?;
    let rows = statement.query_map([], read_pending_row)?;
    rows.collect::<rusqlite::Result<Vec<_>>>()
        .map_err(Into::into)
}

fn read_pending_row(row: &Row<'_>) -> rusqlite::Result<PendingOperation> {
    let dirty: String = row.get(2)?;
    let payload: String = row.get(3)?;
    Ok(PendingOperation {
        op_id: row.get(0)?,
        target_id: row.get(1)?,
        dirty_fields: serde_json::from_str(&dirty).unwrap_or_default(),
        payload: serde_json::from_str(&payload).unwrap_or(Value::Null),
    })
}

fn queue_operation(
    tx: &Transaction<'_>,
    target_id: &str,
    dirty_fields: &[&str],
    payload: Value,
) -> Result<()> {
    let op_id = payload
        .get("opId")
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("operation is missing opId"))?;
    tx.execute(
        "INSERT INTO pending_operations(op_id, target_id, dirty_fields, payload_json, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            op_id,
            target_id,
            serde_json::to_string(dirty_fields)?,
            serde_json::to_string(&payload)?,
            format_utc(Utc::now())
        ],
    )?;
    Ok(())
}

fn queue_operation_raw(tx: &Transaction<'_>, operation: &PendingOperation) -> Result<()> {
    tx.execute(
        "INSERT INTO pending_operations(op_id, target_id, dirty_fields, payload_json, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            operation.op_id,
            operation.target_id,
            serde_json::to_string(&operation.dirty_fields)?,
            serde_json::to_string(&operation.payload)?,
            format_utc(Utc::now())
        ],
    )?;
    Ok(())
}

fn read_device_id(tx: &Transaction<'_>) -> Result<String> {
    tx.query_row(
        "SELECT value FROM meta WHERE key = 'device_id'",
        [],
        |row| row.get(0),
    )
    .context("read device id")
}

fn dirty_fields_for(operations: &[PendingOperation], target_id: &str) -> HashSet<String> {
    operations
        .iter()
        .filter(|operation| operation.target_id == target_id)
        .flat_map(|operation| operation.dirty_fields.iter().cloned())
        .collect()
}

fn merge_remote_task(local: Option<Task>, remote: &Task, dirty: &HashSet<String>) -> Task {
    let Some(local) = local else {
        return remote.clone();
    };
    Task {
        task_id: remote.task_id.clone(),
        title: if dirty.contains("title") {
            local.title
        } else {
            remote.title.clone()
        },
        title_source: if dirty.contains("titleSource") {
            local.title_source
        } else {
            remote.title_source
        },
        created_on: remote.created_on.clone(),
        due_date: if dirty.contains("dueDate") {
            local.due_date
        } else {
            remote.due_date.clone()
        },
        due_time: if dirty.contains("dueTime") {
            local.due_time
        } else {
            remote.due_time.clone()
        },
        priority: if dirty.contains("priority") {
            local.priority
        } else {
            remote.priority
        },
        completed: if dirty.contains("completed") {
            local.completed
        } else {
            remote.completed
        },
        completed_at: if dirty.contains("completed") {
            local.completed_at
        } else {
            remote.completed_at
        },
        updated_at: remote.updated_at,
    }
}

fn reapply_pending(
    tx: &Transaction<'_>,
    operation: &PendingOperation,
    local_tasks: &HashMap<String, Task>,
) -> Result<()> {
    let kind = operation.payload.get("type").and_then(Value::as_str);
    match kind {
        Some("task.create") => {
            if let Some(task) = operation
                .payload
                .get("task")
                .cloned()
                .and_then(|value| serde_json::from_value::<Task>(value).ok())
            {
                if read_task_tx(tx, &task.task_id)?.is_none() {
                    insert_task(tx, &task)?;
                }
            }
        }
        Some("task.update") => {
            let Some(task_id) = operation.payload.get("taskId").and_then(Value::as_str) else {
                return Ok(());
            };
            let mut task = read_task_tx(tx, task_id)?.or_else(|| {
                operation
                    .payload
                    .get("fallback")
                    .cloned()
                    .and_then(|value| serde_json::from_value::<Task>(value).ok())
                    .or_else(|| local_tasks.get(task_id).cloned())
            });
            if let Some(current) = task.as_mut() {
                if let Some(local) = local_tasks.get(task_id) {
                    apply_dirty_values(current, local, &operation.dirty_fields);
                }
                if let Some(set) = operation.payload.get("set").and_then(Value::as_object) {
                    apply_json_set(current, set);
                }
                insert_task(tx, current)?;
            }
        }
        Some("settings.update") => {
            if let Some(settings) = operation
                .payload
                .get("settings")
                .cloned()
                .and_then(|value| serde_json::from_value::<WorkSettings>(value).ok())
            {
                write_work_settings(tx, &settings)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn apply_dirty_values(target: &mut Task, source: &Task, dirty: &[String]) {
    for field in dirty {
        match field.as_str() {
            "title" => target.title = source.title.clone(),
            "titleSource" => target.title_source = source.title_source,
            "dueDate" => target.due_date = source.due_date.clone(),
            "dueTime" => target.due_time = source.due_time.clone(),
            "priority" => target.priority = source.priority,
            "completed" => {
                target.completed = source.completed;
                target.completed_at = source.completed_at;
            }
            _ => {}
        }
    }
}

fn apply_json_set(target: &mut Task, set: &serde_json::Map<String, Value>) {
    if let Some(value) = set.get("title").and_then(Value::as_str) {
        target.title = value.to_string();
    }
    if let Some(value) = set.get("titleSource").and_then(Value::as_str) {
        target.title_source = parse_title_source(value);
    }
    if let Some(value) = set.get("dueDate").and_then(Value::as_str) {
        target.due_date = value.to_string();
    }
    if set.get("dueTime").is_some() {
        target.due_time = set
            .get("dueTime")
            .and_then(Value::as_str)
            .map(ToString::to_string);
    }
    if let Some(value) = set.get("priority").and_then(Value::as_str) {
        target.priority = parse_priority(value);
    }
    if let Some(value) = set.get("completed").and_then(Value::as_bool) {
        target.completed = value;
        target.completed_at = if value { Some(Utc::now()) } else { None };
    }
}

fn acknowledged_reminders(conn: &Connection) -> Result<Vec<AcknowledgedReminder>> {
    let mut statement = conn.prepare(
        "SELECT work_date, period FROM reminder_events
         WHERE acknowledged_at IS NOT NULL ORDER BY work_date DESC LIMIT 60",
    )?;
    let rows = statement.query_map([], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    })?;
    let pairs = rows.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rules::acknowledged_set(&pairs))
}

fn validate_work_settings(settings: &WorkSettings) -> Result<()> {
    if settings.workdays.is_empty()
        || settings.workdays.iter().any(|day| !(1..=7).contains(day))
        || !(0..=600).contains(&settings.reminder_lead_minutes)
    {
        bail!("作息设置无效");
    }
    settings
        .time_zone
        .parse::<Tz>()
        .map_err(|_| anyhow!("时区无效"))?;
    let start = parse_clock(&settings.work_start)?;
    let end = parse_clock(&settings.work_end)?;
    if start >= end {
        bail!("上班时间必须早于下班时间");
    }
    Ok(())
}

fn validate_date(value: &str) -> Result<()> {
    NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| anyhow!("截止日期格式无效"))?;
    Ok(())
}

fn validate_optional_clock(value: Option<&str>) -> Result<()> {
    if let Some(value) = value {
        parse_clock(value)?;
    }
    Ok(())
}

fn parse_clock(value: &str) -> Result<NaiveTime> {
    NaiveTime::parse_from_str(value, "%H:%M").map_err(|_| anyhow!("时间格式无效"))
}

fn validate_server_url(value: &str) -> Result<()> {
    let value = value.trim();
    if value.is_empty() {
        return Ok(());
    }
    let url = url::Url::parse(value).map_err(|_| anyhow!("服务器地址无效"))?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        bail!("服务器地址需要以 http:// 或 https:// 开头");
    }
    Ok(())
}

fn parse_utc_optional(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .map(|value| value.with_timezone(&Utc))
}

fn format_utc(value: DateTime<Utc>) -> String {
    value.to_rfc3339_opts(SecondsFormat::Millis, true)
}

fn parse_priority(value: &str) -> Priority {
    match value {
        "urgent" => Priority::Urgent,
        "very_urgent" => Priority::VeryUrgent,
        _ => Priority::Normal,
    }
}

fn priority_value(value: Priority) -> &'static str {
    match value {
        Priority::Normal => "normal",
        Priority::Urgent => "urgent",
        Priority::VeryUrgent => "very_urgent",
    }
}

fn parse_title_source(value: &str) -> TitleSource {
    match value {
        "voice_placeholder" => TitleSource::VoicePlaceholder,
        "screenshot_placeholder" => TitleSource::ScreenshotPlaceholder,
        _ => TitleSource::Manual,
    }
}

fn title_source_value(value: TitleSource) -> &'static str {
    match value {
        TitleSource::Manual => "manual",
        TitleSource::VoicePlaceholder => "voice_placeholder",
        TitleSource::ScreenshotPlaceholder => "screenshot_placeholder",
    }
}

fn parse_attachment_kind(value: &str) -> AttachmentKind {
    match value {
        "recording" => AttachmentKind::Recording,
        _ => AttachmentKind::Screenshot,
    }
}

fn attachment_kind_value(value: AttachmentKind) -> &'static str {
    match value {
        AttachmentKind::Screenshot => "screenshot",
        AttachmentKind::Recording => "recording",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn task_attachment_and_pending_operation_survive_reopen() {
        let root = tempfile::tempdir().expect("temp dir");
        let attachment_path = root.path().join("attachments").join("capture.png");

        let task_id = {
            let db = Db::open(root.path()).expect("open database");
            std::fs::write(&attachment_path, b"\x89PNG\r\n\x1a\n").expect("write attachment");
            let task = db
                .create_task(
                    CreateTaskInput {
                        title: "核对运费".to_string(),
                        due_date: None,
                        due_time: None,
                        priority: None,
                    },
                    Some(CreateAttachment {
                        attachment_id: "attachment-1".to_string(),
                        kind: AttachmentKind::Screenshot,
                        media_type: "image/png".to_string(),
                        byte_size: 8,
                        sha256: "test-sha256".to_string(),
                        local_path: attachment_path.to_string_lossy().into_owned(),
                        created_at: Utc::now(),
                    }),
                    TitleSource::ScreenshotPlaceholder,
                )
                .expect("create task");
            assert_eq!(db.pending_count().unwrap(), 2);
            task.task_id
        };

        let db = Db::open(root.path()).expect("reopen database");
        let views = db.task_views(&[task_id]).expect("read task");
        assert_eq!(views.len(), 1);
        assert_eq!(views[0].task.title, "核对运费");
        assert_eq!(views[0].attachments.len(), 1);
        assert_eq!(
            views[0].attachments[0].attachment.attachment_id,
            "attachment-1"
        );
        assert_eq!(
            views[0].attachments[0].local_path.as_deref(),
            Some(attachment_path.to_string_lossy().as_ref())
        );
        assert!(!views[0].attachments[0].remote_ready);
        assert_eq!(db.pending_count().unwrap(), 2);
        assert!(attachment_path.exists());
    }
}
