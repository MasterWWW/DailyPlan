use std::path::Path;
use std::sync::{Arc, Mutex};

use anyhow::Result;
use tokio::sync::Notify;

use crate::audio::AudioManager;
use crate::db::Db;
use crate::rules::Reminder;
use crate::screenshot::PendingCapture;

pub struct AppState {
    pub db: Arc<Db>,
    pub audio: AudioManager,
    pub sync_trigger: Arc<Notify>,
    pub pending_capture: Mutex<Option<PendingCapture>>,
    pub previous_app_pid: Mutex<Option<i32>>,
    pub current_reminder: Mutex<Option<Reminder>>,
}

impl AppState {
    pub fn new(root: &Path) -> Result<Self> {
        let db = Arc::new(Db::open(root)?);
        let sync_trigger = crate::sync::trigger();
        Ok(Self {
            db,
            audio: AudioManager::new(),
            sync_trigger,
            pending_capture: Mutex::new(None),
            previous_app_pid: Mutex::new(None),
            current_reminder: Mutex::new(None),
        })
    }
}
