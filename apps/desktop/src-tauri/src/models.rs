use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum Priority {
    #[default]
    Normal,
    Urgent,
    VeryUrgent,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum TitleSource {
    #[default]
    Manual,
    VoicePlaceholder,
    ScreenshotPlaceholder,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub task_id: String,
    pub title: String,
    #[serde(default)]
    pub title_source: TitleSource,
    pub created_on: String,
    pub due_date: String,
    #[serde(default)]
    pub due_time: Option<String>,
    #[serde(default)]
    pub priority: Priority,
    #[serde(default)]
    pub completed: bool,
    #[serde(default)]
    pub completed_at: Option<DateTime<Utc>>,
    #[serde(default = "epoch")]
    pub updated_at: DateTime<Utc>,
}

fn epoch() -> DateTime<Utc> {
    DateTime::from_timestamp(0, 0).expect("epoch")
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WorkSettings {
    pub workdays: Vec<u32>,
    pub time_zone: String,
    pub work_start: String,
    pub work_end: String,
    pub reminder_lead_minutes: i32,
}

impl Default for WorkSettings {
    fn default() -> Self {
        Self {
            workdays: vec![1, 2, 3, 4, 5],
            time_zone: "Asia/Shanghai".to_string(),
            work_start: "09:00".to_string(),
            work_end: "18:00".to_string(),
            reminder_lead_minutes: 15,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AttachmentKind {
    Screenshot,
    Recording,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Attachment {
    pub attachment_id: String,
    pub task_id: String,
    pub kind: AttachmentKind,
    pub media_type: String,
    pub byte_size: i64,
    pub sha256: String,
    pub created_at: DateTime<Utc>,
    #[serde(default)]
    pub transcription_status: Option<String>,
    #[serde(default)]
    pub transcript: Option<String>,
    #[serde(default)]
    pub transcription_error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalAttachment {
    #[serde(flatten)]
    pub attachment: Attachment,
    pub local_path: Option<String>,
    pub remote_ready: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub protocol_version: i32,
    pub server_time: DateTime<Utc>,
    pub dataset_id: String,
    pub restore_version: i64,
    pub cursor: i64,
    pub transcription_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OperationReceipt {
    pub op_id: String,
    pub cursor: i64,
    #[serde(default)]
    pub task: Option<Task>,
    #[serde(default)]
    pub settings: Option<WorkSettings>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Change {
    pub seq: i64,
    #[serde(rename = "type")]
    pub change_type: String,
    #[serde(default)]
    pub task: Option<Task>,
    #[serde(default)]
    pub settings: Option<WorkSettings>,
    #[serde(default)]
    pub attachment: Option<Attachment>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChangesPage {
    pub changes: Vec<Change>,
    pub next_cursor: i64,
    pub has_more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub cursor: i64,
    pub restore_version: i64,
    pub tasks: Vec<Task>,
    pub settings: WorkSettings,
    pub attachments: Vec<Attachment>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskView {
    #[serde(flatten)]
    pub task: Task,
    pub overdue: bool,
    pub due_soon: bool,
    pub attachments: Vec<LocalAttachment>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TodayView {
    pub tasks: Vec<TaskView>,
    pub completed: Vec<TaskView>,
    pub settings: WorkSettings,
    pub server_url: String,
    pub pending_count: i64,
    pub last_success_at: Option<DateTime<Utc>>,
    pub last_error: Option<String>,
    pub has_token: bool,
    pub sync_configured: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReminderPresentation {
    pub work_date: String,
    pub period: String,
    pub tasks: Vec<TaskView>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppSettings {
    #[serde(flatten)]
    pub work: WorkSettings,
    pub server_url: String,
    pub text_shortcut: String,
    pub screenshot_shortcut: String,
    pub autostart: bool,
    pub has_token: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateTaskInput {
    pub title: String,
    #[serde(default)]
    pub due_date: Option<String>,
    #[serde(default)]
    pub due_time: Option<String>,
    #[serde(default)]
    pub priority: Option<Priority>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskUpdateInput {
    pub task_id: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub due_date: Option<String>,
    #[serde(default)]
    pub clear_due_time: bool,
    #[serde(default)]
    pub due_time: Option<String>,
    #[serde(default)]
    pub priority: Option<Priority>,
    #[serde(default)]
    pub completed: Option<bool>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsInput {
    pub workdays: Vec<u32>,
    pub time_zone: String,
    pub work_start: String,
    pub work_end: String,
    pub reminder_lead_minutes: i32,
    pub server_url: String,
    pub text_shortcut: String,
    pub screenshot_shortcut: String,
    #[serde(default)]
    pub autostart: bool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn load<T: for<'de> Deserialize<'de>>(name: &str) -> T {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/protocol/samples/server")
            .join(name);
        let content = std::fs::read_to_string(&path)
            .unwrap_or_else(|error| panic!("read {}: {error}", path.display()));
        serde_json::from_str(&content)
            .unwrap_or_else(|error| panic!("decode {}: {error}", path.display()))
    }

    #[test]
    fn protocol_samples_decode() {
        let status: Status = load("status.json");
        assert_eq!(status.protocol_version, 1);
        assert_eq!(status.cursor, 12);
        assert!(status.transcription_enabled);

        let receipt: OperationReceipt = load("operation-receipt.json");
        assert_eq!(receipt.cursor, 1);
        assert!(receipt.task.is_some());

        let changes: ChangesPage = load("changes-page.json");
        assert_eq!(changes.next_cursor, 12);
        assert_eq!(changes.changes.len(), 1);
        assert!(changes.changes[0]
            .task
            .as_ref()
            .is_some_and(|task| task.completed));

        let snapshot: Snapshot = load("snapshot.json");
        assert_eq!(snapshot.cursor, 12);
        assert_eq!(snapshot.tasks.len(), 1);
        assert!(snapshot.attachments.is_empty());

        let attachment: Attachment = load("attachment.json");
        assert_eq!(attachment.kind, AttachmentKind::Recording);
        assert_eq!(attachment.transcription_status.as_deref(), Some("pending"));
    }
}
