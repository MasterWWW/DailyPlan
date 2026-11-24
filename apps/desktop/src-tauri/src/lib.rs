use std::path::PathBuf;
use std::time::Duration;

use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, LogicalPosition, Manager, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};

use crate::db::CreateAttachment;
use crate::models::{
    AppSettings, CreateTaskInput, ReminderPresentation, SettingsInput, Status, Task,
    TaskUpdateInput, TitleSource, TodayView,
};
use crate::rules::ReminderPeriod;
use crate::state::AppState;

mod audio;
mod db;
mod focus;
mod keychain;
mod models;
mod rules;
mod screenshot;
mod state;
mod sync;

type CommandResult<T> = Result<T, String>;

#[tauri::command]
fn get_today(state: tauri::State<'_, AppState>) -> CommandResult<TodayView> {
    let has_token = keychain::token().unwrap_or(None).is_some();
    state.db.today_view(has_token).map_err(message)
}

#[tauri::command]
fn create_task(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    input: CreateTaskInput,
) -> CommandResult<Task> {
    let task = state
        .db
        .create_task(input, None, TitleSource::Manual)
        .map_err(message)?;
    notify_data_changed(&app, &state);
    Ok(task)
}

#[tauri::command]
fn update_task(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    input: TaskUpdateInput,
) -> CommandResult<Task> {
    let task = state.db.update_task(input).map_err(message)?;
    notify_data_changed(&app, &state);
    Ok(task)
}

#[tauri::command]
fn get_settings(state: tauri::State<'_, AppState>) -> CommandResult<AppSettings> {
    let has_token = keychain::token().unwrap_or(None).is_some();
    state.db.app_settings(has_token).map_err(message)
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct SaveSettingsInput {
    #[serde(flatten)]
    settings: SettingsInput,
    token: Option<String>,
    clear_token: bool,
}

#[tauri::command]
fn save_settings(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    input: SaveSettingsInput,
) -> CommandResult<AppSettings> {
    let previous = state.db.app_settings(false).map_err(message)?;
    let next_text = input.settings.text_shortcut.trim().to_string();
    let next_screenshot = input.settings.screenshot_shortcut.trim().to_string();
    let next_autostart = input.settings.autostart;

    if let Err(error) = register_shortcuts(app.clone(), &next_text, &next_screenshot) {
        let _ = register_shortcuts(
            app.clone(),
            &previous.text_shortcut,
            &previous.screenshot_shortcut,
        );
        return Err(error);
    }
    let autostart_result = if next_autostart {
        app.autolaunch().enable().map_err(message)
    } else {
        app.autolaunch().disable().map_err(message)
    };
    if let Err(error) = autostart_result {
        let _ = register_shortcuts(
            app.clone(),
            &previous.text_shortcut,
            &previous.screenshot_shortcut,
        );
        return Err(error);
    }
    if let Err(error) = state.db.save_settings(input.settings) {
        let _ = register_shortcuts(
            app.clone(),
            &previous.text_shortcut,
            &previous.screenshot_shortcut,
        );
        if previous.autostart {
            let _ = app.autolaunch().enable();
        } else {
            let _ = app.autolaunch().disable();
        }
        return Err(message(error));
    }
    if let Some(token) = input
        .token
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        keychain::set_token(token).map_err(message)?;
    } else if input.clear_token {
        keychain::delete_token().map_err(message)?;
    }

    let settings = state
        .db
        .app_settings(keychain::token().unwrap_or(None).is_some())
        .map_err(message)?;
    notify_data_changed(&app, &state);
    Ok(settings)
}

#[tauri::command]
async fn test_connection(server_url: String, token: String) -> CommandResult<Status> {
    let token = if token.trim().is_empty() {
        keychain::token()
            .map_err(message)?
            .ok_or_else(|| "请先填写访问令牌".to_string())?
    } else {
        token
    };
    sync::test_connection(&server_url, &token)
        .await
        .map_err(message)
}

#[tauri::command]
async fn sync_now(app: AppHandle, state: tauri::State<'_, AppState>) -> CommandResult<()> {
    sync::sync_once(&state.db).await.map_err(message)?;
    let _ = app.emit("data-changed", ());
    check_reminder(&app);
    Ok(())
}

#[tauri::command]
fn restore_previous_app(app: AppHandle, state: tauri::State<'_, AppState>) -> CommandResult<()> {
    hide_main_window(&app);
    restore_focus(&state);
    Ok(())
}

#[tauri::command]
fn hide_main(app: AppHandle) {
    hide_main_window(&app);
}

#[tauri::command]
fn hide_sticky(app: AppHandle) {
    hide_sticky_window(&app);
}

#[tauri::command]
fn reveal_main(app: AppHandle) {
    reveal_main_window(&app);
}

#[tauri::command]
fn open_task(app: AppHandle, task_id: String) -> CommandResult<()> {
    reveal_main_window(&app);
    let _ = app.emit_to("main", "sticky-open-task", task_id);
    Ok(())
}

#[tauri::command]
fn begin_screenshot(app: AppHandle, state: tauri::State<'_, AppState>) -> CommandResult<()> {
    screenshot::begin(&app, &state).map_err(message)
}

#[tauri::command]
fn selection_image(state: tauri::State<'_, AppState>) -> CommandResult<screenshot::SelectionImage> {
    screenshot::image(&state).map_err(message)
}

#[tauri::command]
fn finish_selection(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    rect: screenshot::SelectionRect,
) -> CommandResult<Task> {
    let task = screenshot::finish(&app, &state, rect).map_err(message)?;
    notify_data_changed(&app, &state);
    Ok(task)
}

#[tauri::command]
fn cancel_selection(app: AppHandle, state: tauri::State<'_, AppState>) {
    screenshot::cancel(&app, &state);
}

#[tauri::command]
fn start_recording(state: tauri::State<'_, AppState>) -> CommandResult<audio::RecordingStatus> {
    state.audio.start().map_err(message)
}

#[tauri::command]
fn stop_recording(app: AppHandle, state: tauri::State<'_, AppState>) -> CommandResult<Task> {
    let captured = state.audio.stop(state.db.root()).map_err(message)?;
    let settings = state.db.settings().map_err(message)?;
    let timezone: chrono_tz::Tz = settings.time_zone.parse().map_err(message)?;
    let title = format!(
        "语音待办 · {}",
        captured.started_at.with_timezone(&timezone).format("%H:%M")
    );
    let result = state.db.create_task(
        CreateTaskInput {
            title,
            due_date: None,
            due_time: None,
            priority: None,
        },
        Some(CreateAttachment {
            attachment_id: captured.attachment_id,
            kind: crate::models::AttachmentKind::Recording,
            media_type: captured.media_type,
            byte_size: captured.byte_size,
            sha256: captured.sha256,
            local_path: captured.path.to_string_lossy().to_string(),
            created_at: captured.started_at,
        }),
        TitleSource::VoicePlaceholder,
    );
    if result.is_err() {
        let _ = std::fs::remove_file(&captured.path);
    }
    let task = result.map_err(message)?;
    notify_data_changed(&app, &state);
    Ok(task)
}

#[tauri::command]
fn get_reminder(state: tauri::State<'_, AppState>) -> CommandResult<Option<ReminderPresentation>> {
    let evaluation = state
        .db
        .evaluation(chrono::Utc::now().fixed_offset())
        .map_err(message)?;
    let Some(reminder) = evaluation.reminder else {
        clear_all_reminders(&state);
        return Ok(None);
    };
    let views = state.db.task_views(&reminder.task_ids).map_err(message)?;
    if views.is_empty() {
        clear_all_reminders(&state);
        return Ok(None);
    }
    let changed = {
        let mut slot = state
            .current_reminder
            .lock()
            .map_err(|_| "提醒状态不可用".to_string())?;
        let changed = slot.as_ref() != Some(&reminder);
        if changed {
            *slot = Some(reminder.clone());
        }
        changed
    };
    if changed {
        state.db.mark_reminder_shown(&reminder).map_err(message)?;
    }
    Ok(Some(ReminderPresentation {
        work_date: reminder.work_date,
        period: period_name(reminder.period).to_string(),
        tasks: views,
    }))
}

#[tauri::command]
fn acknowledge_reminder(app: AppHandle, state: tauri::State<'_, AppState>) -> CommandResult<()> {
    let reminder = state
        .current_reminder
        .lock()
        .map_err(|_| "提醒状态不可用".to_string())?
        .take();
    if let Some(reminder) = reminder {
        state.db.acknowledge_reminder(&reminder).map_err(message)?;
    }
    hide_main_window(&app);
    let _ = app.emit("reminder-dismissed", ());
    Ok(())
}

#[tauri::command]
async fn attachment_data(
    state: tauri::State<'_, AppState>,
    attachment_id: String,
) -> CommandResult<String> {
    let local = state
        .db
        .attachment_file_path(&attachment_id)
        .map_err(message)?;
    let path = match local.filter(|path| path.is_file()) {
        Some(path) => path,
        None => sync::download_attachment(&state.db, &attachment_id)
            .await
            .map_err(message)?
            .ok_or_else(|| "附件原件不可用".to_string())?,
    };
    sync::data_url(&path).map_err(message)
}

#[tauri::command]
async fn retry_transcription(
    app: AppHandle,
    state: tauri::State<'_, AppState>,
    attachment_id: String,
) -> CommandResult<()> {
    sync::retry_transcription(&state.db, &attachment_id)
        .await
        .map_err(message)?;
    notify_data_changed(&app, &state);
    Ok(())
}

fn notify_data_changed(app: &AppHandle, state: &AppState) {
    let _ = app.emit("data-changed", ());
    check_reminder(app);
    sync::notify(&state.sync_trigger);
}

fn remember_previous_app(state: &AppState) {
    if let Ok(mut slot) = state.previous_app_pid.lock() {
        *slot = focus::frontmost_pid().filter(|pid| *pid != std::process::id() as i32);
    }
}

fn restore_focus(state: &AppState) {
    let pid = state
        .previous_app_pid
        .lock()
        .ok()
        .and_then(|mut slot| slot.take());
    if let Some(pid) = pid {
        focus::activate_process(pid);
    }
}

fn hide_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
}

fn reveal_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn show_sticky_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("sticky") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn hide_sticky_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("sticky") {
        let _ = window.hide();
    }
}

fn position_sticky_window(window: &tauri::WebviewWindow) -> tauri::Result<()> {
    let Some(monitor) = window.primary_monitor()? else {
        return Ok(());
    };
    let scale = monitor.scale_factor();
    let work_area = monitor.work_area();
    let origin = work_area.position.to_logical::<f64>(scale);
    let area = work_area.size.to_logical::<f64>(scale);
    let (window_width, _) = window
        .outer_size()
        .map(|size| {
            let size = size.to_logical::<f64>(scale);
            (size.width, size.height)
        })
        .ok()
        .filter(|(width, height)| *width > 0.0 && *height > 0.0)
        .unwrap_or((300.0, 460.0));
    let margin = 24.0;
    let x = (origin.x + area.width - window_width - margin).max(origin.x);
    let y = origin.y + margin;
    window.set_position(LogicalPosition::new(x, y))
}

fn start_screenshot(app: &AppHandle) {
    let app = app.clone();
    std::thread::spawn(move || {
        let state = app.state::<AppState>();
        if let Err(error) = screenshot::begin(&app, &state) {
            let _ = app.emit("action-error", error.to_string());
            reveal_main_window(&app);
        }
    });
}

fn register_shortcuts(
    app: AppHandle,
    text_shortcut: &str,
    screenshot_shortcut: &str,
) -> CommandResult<()> {
    let manager = app.global_shortcut();
    let _ = manager.unregister_all();
    manager
        .register(text_shortcut)
        .map_err(|error| format!("文字快捷键无效：{error}"))?;
    manager
        .register(screenshot_shortcut)
        .map_err(|error| format!("截图快捷键无效：{error}"))?;
    Ok(())
}

pub(crate) fn check_reminder(app: &AppHandle) {
    let state = app.state::<AppState>();
    let now = chrono::Utc::now().fixed_offset();
    let Ok(evaluation) = state.db.evaluation(now) else {
        return;
    };
    let Some(reminder) = evaluation.reminder else {
        let had_current = state
            .current_reminder
            .lock()
            .map(|current| current.is_some())
            .unwrap_or(false);
        clear_all_reminders(&state);
        if had_current {
            let _ = app.emit("reminder-dismissed", ());
        }
        return;
    };
    let current = state
        .current_reminder
        .lock()
        .ok()
        .and_then(|value| value.clone());
    if current.as_ref() == Some(&reminder) {
        return;
    }
    let was_shown = state
        .db
        .reminder_was_shown(&reminder.work_date, period_name(reminder.period))
        .unwrap_or(false);
    if !was_shown && state.db.mark_reminder_shown(&reminder).is_err() {
        return;
    }
    if let Ok(mut slot) = state.current_reminder.lock() {
        *slot = Some(reminder);
    }
    if !was_shown {
        reveal_main_window(app);
    }
    let _ = app.emit("reminder-shown", ());
}

fn clear_all_reminders(state: &AppState) {
    if let Ok(mut slot) = state.current_reminder.lock() {
        *slot = None;
    }
}

fn period_name(period: ReminderPeriod) -> &'static str {
    match period {
        ReminderPeriod::Morning => "morning",
        ReminderPeriod::Evening => "evening",
    }
}

fn message(error: impl std::fmt::Display) -> String {
    error.to_string()
}

fn tray_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let quick = MenuItem::with_id(
        app,
        "quick",
        "快速记录",
        true,
        Some("CmdOrCtrl+Shift+Space"),
    )?;
    let screenshot = MenuItem::with_id(app, "screenshot", "框选截图", true, None::<&str>)?;
    let sticky = MenuItem::with_id(app, "sticky", "显示便签", true, None::<&str>)?;
    let show = MenuItem::with_id(app, "show", "打开清单", true, None::<&str>)?;
    let sync = MenuItem::with_id(app, "sync", "立即同步", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出 DailyPlan", true, None::<&str>)?;
    let separator = PredefinedMenuItem::separator(app)?;
    Menu::with_items(
        app,
        &[
            &quick,
            &screenshot,
            &sticky,
            &show,
            &sync,
            &separator,
            &quit,
        ],
    )
}

fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let menu = tray_menu(app.handle())?;
    let mut builder = TrayIconBuilder::new()
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "quick" => {
                let state = app.state::<AppState>();
                remember_previous_app(&state);
                reveal_main_window(app);
                let _ = app.emit("quick-capture", ());
            }
            "screenshot" => start_screenshot(app),
            "sticky" => show_sticky_window(app),
            "show" => reveal_main_window(app),
            "sync" => {
                let state = app.state::<AppState>();
                sync::notify(&state.sync_trigger);
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                reveal_main_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            reveal_main_window(app);
        }))
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, shortcut, event| {
                    if event.state() != ShortcutState::Pressed {
                        return;
                    }
                    let state = app.state::<AppState>();
                    let Ok((text, screenshot_shortcut)) = state.db.shortcut_settings() else {
                        return;
                    };
                    let pressed = shortcut.to_string();
                    if pressed.eq_ignore_ascii_case(&text) {
                        remember_previous_app(&state);
                        reveal_main_window(app);
                        let _ = app.emit("quick-capture", ());
                    } else if pressed.eq_ignore_ascii_case(&screenshot_shortcut) {
                        start_screenshot(app);
                    }
                })
                .build(),
        )
        .setup(|app| {
            let root = app
                .path()
                .app_data_dir()
                .unwrap_or_else(|_| PathBuf::from("."));
            let state = AppState::new(&root)?;
            let bootstrap = state
                .db
                .app_settings(keychain::token().unwrap_or(None).is_some())?;
            app.manage(state);
            setup_tray(app)?;

            if let Some(main) = app.get_webview_window("main") {
                focus::configure_sticky_window(&main)?;
                let window = main.clone();
                main.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = window.hide();
                    }
                });
            }
            if let Some(sticky) = app.get_webview_window("sticky") {
                focus::configure_sticky_window(&sticky)?;
                if let Err(error) = position_sticky_window(&sticky) {
                    log::warn!("position sticky window: {error}");
                }
                let window = sticky.clone();
                sticky.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        api.prevent_close();
                        let _ = window.hide();
                    }
                });
                let _ = sticky.show();
            }

            let app_handle = app.handle().clone();
            if let Err(error) = register_shortcuts(
                app_handle.clone(),
                &bootstrap.text_shortcut,
                &bootstrap.screenshot_shortcut,
            ) {
                log::warn!("{error}");
                let _ = app_handle.emit("action-error", error);
            }
            if bootstrap.autostart {
                let _ = app_handle.autolaunch().enable();
            }

            let data_state = app.state::<AppState>();
            sync::start(
                app_handle.clone(),
                data_state.db.clone(),
                data_state.sync_trigger.clone(),
            );
            tauri::async_runtime::spawn(async move {
                tokio::time::sleep(Duration::from_secs(3)).await;
                loop {
                    check_reminder(&app_handle);
                    tokio::time::sleep(Duration::from_secs(20)).await;
                }
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_today,
            create_task,
            update_task,
            get_settings,
            save_settings,
            test_connection,
            sync_now,
            restore_previous_app,
            hide_main,
            hide_sticky,
            reveal_main,
            open_task,
            begin_screenshot,
            selection_image,
            finish_selection,
            cancel_selection,
            start_recording,
            stop_recording,
            get_reminder,
            acknowledge_reminder,
            attachment_data,
            retry_transcription
        ])
        .run(tauri::generate_context!())
        .expect("error while running DailyPlan");
}
