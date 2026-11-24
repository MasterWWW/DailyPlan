export type Priority = "normal" | "urgent" | "very_urgent";

export interface Task {
  taskId: string;
  title: string;
  titleSource: "manual" | "voice_placeholder" | "screenshot_placeholder";
  createdOn: string;
  dueDate: string;
  dueTime: string | null;
  priority: Priority;
  completed: boolean;
  completedAt: string | null;
  updatedAt: string;
}

export interface Attachment {
  attachmentId: string;
  taskId: string;
  kind: "screenshot" | "recording";
  mediaType: string;
  byteSize: number;
  sha256: string;
  createdAt: string;
  transcriptionStatus: string | null;
  transcript: string | null;
  transcriptionError: string | null;
}

export interface LocalAttachment extends Attachment {
  localPath: string | null;
  remoteReady: boolean;
}

export interface TaskView extends Task {
  overdue: boolean;
  dueSoon: boolean;
  attachments: LocalAttachment[];
}

export interface WorkSettings {
  workdays: number[];
  timeZone: string;
  workStart: string;
  workEnd: string;
  reminderLeadMinutes: number;
}

export interface TodayView {
  tasks: TaskView[];
  completed: TaskView[];
  settings: WorkSettings;
  serverUrl: string;
  pendingCount: number;
  lastSuccessAt: string | null;
  lastError: string | null;
  hasToken: boolean;
  syncConfigured: boolean;
}

export interface AppSettings extends WorkSettings {
  serverUrl: string;
  textShortcut: string;
  screenshotShortcut: string;
  autostart: boolean;
  hasToken: boolean;
}

export interface ReminderPresentation {
  workDate: string;
  period: "morning" | "evening";
  tasks: TaskView[];
}

export interface Status {
  protocolVersion: number;
  serverTime: string;
  datasetId: string;
  restoreVersion: number;
  cursor: number;
  transcriptionEnabled: boolean;
}

export interface CreateTaskInput {
  title: string;
  dueDate?: string | null;
  dueTime?: string | null;
  priority?: Priority;
}

export interface TaskUpdateInput {
  taskId: string;
  title?: string;
  dueDate?: string;
  clearDueTime?: boolean;
  dueTime?: string | null;
  priority?: Priority;
  completed?: boolean;
}

export interface SettingsInput {
  workdays: number[];
  timeZone: string;
  workStart: string;
  workEnd: string;
  reminderLeadMinutes: number;
  serverUrl: string;
  textShortcut: string;
  screenshotShortcut: string;
  autostart: boolean;
}

export interface RecordingStatus {
  startedAt: string;
}
