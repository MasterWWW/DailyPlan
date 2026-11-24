#[cfg(target_os = "macos")]
use objc2_app_kit::{
    NSApplicationActivationOptions, NSRunningApplication, NSWindowCollectionBehavior, NSWorkspace,
};

#[cfg(target_os = "macos")]
pub fn frontmost_pid() -> Option<i32> {
    NSWorkspace::sharedWorkspace()
        .frontmostApplication()
        .map(|application| application.processIdentifier())
}

#[cfg(not(target_os = "macos"))]
pub fn frontmost_pid() -> Option<i32> {
    None
}

#[cfg(target_os = "macos")]
pub fn activate_process(pid: i32) {
    if pid == std::process::id() as i32 {
        return;
    }
    if let Some(application) = NSRunningApplication::runningApplicationWithProcessIdentifier(pid) {
        let _ = application.activateWithOptions(NSApplicationActivationOptions::ActivateAllWindows);
    }
}

#[cfg(not(target_os = "macos"))]
pub fn activate_process(_pid: i32) {}

#[cfg(target_os = "macos")]
pub fn configure_sticky_window(window: &tauri::WebviewWindow) -> tauri::Result<()> {
    use objc2_app_kit::NSWindow;
    let pointer = window.ns_window()?;
    if pointer.is_null() {
        return Ok(());
    }
    let ns_window = unsafe { &*(pointer as *mut NSWindow) };
    ns_window.setCollectionBehavior(
        NSWindowCollectionBehavior::CanJoinAllSpaces
            | NSWindowCollectionBehavior::FullScreenAuxiliary
            | NSWindowCollectionBehavior::Stationary,
    );
    Ok(())
}

#[cfg(not(target_os = "macos"))]
pub fn configure_sticky_window(_window: &tauri::WebviewWindow) -> tauri::Result<()> {
    Ok(())
}
