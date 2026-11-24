import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  AppSettings,
  CreateTaskInput,
  RecordingStatus,
  ReminderPresentation,
  SettingsInput,
  Status,
  Task,
  TaskUpdateInput,
  TodayView,
} from "./types";

export const api = {
  today: () => invoke<TodayView>("get_today"),
  createTask: (input: CreateTaskInput) => invoke<Task>("create_task", { input }),
  updateTask: (input: TaskUpdateInput) => invoke<Task>("update_task", { input }),
  settings: () => invoke<AppSettings>("get_settings"),
  saveSettings: (
    input: SettingsInput,
    token: string | null,
    clearToken = false,
  ) => invoke<AppSettings>("save_settings", { input: { ...input, token, clearToken } }),
  testConnection: (serverUrl: string, token: string) =>
    invoke<Status>("test_connection", { serverUrl, token }),
  syncNow: () => invoke<void>("sync_now"),
  restorePreviousApp: () => invoke<void>("restore_previous_app"),
  hideMain: () => invoke<void>("hide_main"),
  revealMain: () => invoke<void>("reveal_main"),
  hideSticky: () => invoke<void>("hide_sticky"),
  openTask: (taskId: string) => invoke<void>("open_task", { taskId }),
  beginScreenshot: () => invoke<void>("begin_screenshot"),
  selectionImage: () =>
    invoke<{ dataUrl: string; width: number; height: number }>("selection_image"),
  finishSelection: (rect: { x: number; y: number; width: number; height: number }) =>
    invoke<Task>("finish_selection", { rect }),
  cancelSelection: () => invoke<void>("cancel_selection"),
  startRecording: () => invoke<RecordingStatus>("start_recording"),
  stopRecording: () => invoke<Task>("stop_recording"),
  reminder: () => invoke<ReminderPresentation | null>("get_reminder"),
  acknowledgeReminder: () => invoke<void>("acknowledge_reminder"),
  attachmentData: (attachmentId: string) =>
    invoke<string>("attachment_data", { attachmentId }),
  retryTranscription: (attachmentId: string) =>
    invoke<void>("retry_transcription", { attachmentId }),
};

export function onAppEvent(
  event: string,
  handler: (payload: unknown) => void,
): Promise<UnlistenFn> {
  return listen(event, (message) => handler(message.payload));
}
