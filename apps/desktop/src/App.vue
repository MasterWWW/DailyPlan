<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { api, onAppEvent } from "./api";
import SettingsPanel from "./components/SettingsPanel.vue";
import TaskDetail from "./components/TaskDetail.vue";
import TaskRow from "./components/TaskRow.vue";
import type {
  AppSettings,
  ReminderPresentation,
  TaskView,
  TodayView,
} from "./types";

const today = ref<TodayView | null>(null);
const settings = ref<AppSettings | null>(null);
const reminder = ref<ReminderPresentation | null>(null);
const selected = ref<TaskView | null>(null);
const showSettings = ref(false);
const quickMode = ref(false);
const quickTitle = ref("");
const quickInput = ref<HTMLInputElement | null>(null);
const adding = ref(false);
const recording = ref(false);
const recordingBusy = ref(false);
const syncing = ref(false);
const busyTask = ref("");
const showCompleted = ref(false);
const toast = ref<{ message: string; tone: "plain" | "error" } | null>(null);
const unlisteners: Array<() => void> = [];
let toastTimer: number | undefined;

const dayLabel = computed(() => {
  const timeZone = today.value?.settings.timeZone ?? "Asia/Shanghai";
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone,
    month: "long",
    day: "numeric",
    weekday: "long",
  }).format(new Date());
});

const taskCount = computed(() => today.value?.tasks.length ?? 0);
const syncLabel = computed(() => {
  if (!today.value) return "";
  if (!today.value.serverUrl) return "本机保存";
  if (!today.value.hasToken) return "尚未设置令牌";
  if (today.value.lastError) return "离线，待同步";
  if (today.value.pendingCount > 0) return `${today.value.pendingCount} 项待同步`;
  return "已同步";
});
const syncTone = computed(() => {
  if (!today.value) return "";
  if (today.value.lastError) return "warning";
  if (today.value.pendingCount > 0) return "pending";
  return today.value.serverUrl && today.value.hasToken ? "online" : "";
});
const reminderTitle = computed(() =>
  reminder.value?.period === "evening" ? "下班前，回看今天" : "上班了，先看这些",
);
const reminderTimeLabel = computed(() =>
  reminder.value?.period === "evening" ? "下班前回顾" : "上班提醒",
);
const reminderTaskCount = computed(() => reminder.value?.tasks.length ?? 0);

onMounted(async () => {
  await refresh();
  await loadReminder();
  unlisteners.push(
    await onAppEvent("data-changed", () => {
      void refresh();
      if (reminder.value) void loadReminder();
    }),
    await onAppEvent("quick-capture", () => void enterQuickMode()),
    await onAppEvent("reminder-shown", () => {
      showSettings.value = false;
      void loadReminder();
    }),
    await onAppEvent("reminder-dismissed", () => {
      reminder.value = null;
    }),
    await onAppEvent("sticky-open-task", (payload) => {
      void openTaskFromSticky(String(payload ?? ""));
    }),
    await onAppEvent("action-error", (payload) => {
      showToast(String(payload ?? "操作失败"), "error");
    }),
    await onAppEvent("sync-error", () => void refresh()),
  );
});

onBeforeUnmount(() => {
  for (const unlisten of unlisteners) unlisten();
  if (toastTimer) window.clearTimeout(toastTimer);
});

async function refresh() {
  try {
    const value = await api.today();
    today.value = value;
    if (selected.value) {
      selected.value =
        [...value.tasks, ...value.completed].find(
          (task) => task.taskId === selected.value?.taskId,
        ) ?? null;
    }
  } catch (error) {
    showToast(String(error), "error");
  }
}

async function loadSettings() {
  try {
    settings.value = await api.settings();
  } catch (error) {
    showToast(String(error), "error");
  }
}

async function loadReminder() {
  try {
    reminder.value = await api.reminder();
  } catch (error) {
    showToast(String(error), "error");
  }
}

async function enterQuickMode() {
  quickMode.value = true;
  quickTitle.value = "";
  await nextTick();
  quickInput.value?.focus();
}

async function addTask() {
  const title = quickTitle.value.trim();
  if (!title || adding.value) return;
  adding.value = true;
  try {
    await api.createTask({ title });
    quickTitle.value = "";
    quickMode.value = false;
    await refresh();
    if (quickInput.value) quickInput.value.blur();
    await api.restorePreviousApp();
  } catch (error) {
    showToast(String(error), "error");
  } finally {
    adding.value = false;
  }
}

function onQuickKeydown(event: KeyboardEvent) {
  if (event.isComposing) return;
  if (event.key === "Enter") {
    event.preventDefault();
    void addTask();
  }
  if (event.key === "Escape") {
    void exitQuickMode();
  }
}

async function exitQuickMode() {
  quickMode.value = false;
  quickTitle.value = "";
  await api.restorePreviousApp();
}

async function toggleTask(task: TaskView) {
  if (busyTask.value) return;
  busyTask.value = task.taskId;
  try {
    await api.updateTask({ taskId: task.taskId, completed: !task.completed });
    await refresh();
    if (reminder.value) {
      await loadReminder();
      if (!reminder.value) {
        showToast("提醒里的待办已全部完成");
      }
    }
  } catch (error) {
    showToast(String(error), "error");
  } finally {
    busyTask.value = "";
  }
}

async function openDetail(task: TaskView) {
  selected.value = task;
  if (!settings.value) await loadSettings();
}

async function openTaskFromSticky(taskId: string) {
  if (!taskId) return;
  if (!today.value) await refresh();
  const task =
    [...(today.value?.tasks ?? []), ...(today.value?.completed ?? [])].find(
      (value) => value.taskId === taskId,
    ) ?? null;
  if (task) await openDetail(task);
}

async function markCompletedFromDetail() {
  selected.value = null;
  await refresh();
  if (reminder.value) await loadReminder();
}

async function openSettings() {
  showSettings.value = true;
  await loadSettings();
}

async function settingsSaved() {
  showSettings.value = false;
  await refresh();
  await loadSettings();
  showToast("设置已保存");
}

async function sync() {
  if (syncing.value) return;
  syncing.value = true;
  try {
    await api.syncNow();
    await refresh();
    showToast("已与公司服务同步");
  } catch (error) {
    showToast(String(error), "error");
  } finally {
    syncing.value = false;
  }
}

async function startScreenshot() {
  try {
    await api.beginScreenshot();
  } catch (error) {
    showToast(String(error), "error");
  }
}

async function toggleRecording() {
  if (recordingBusy.value) return;
  recordingBusy.value = true;
  try {
    if (!recording.value) {
      await api.startRecording();
      recording.value = true;
      showToast("正在录音，再点一次即保存");
    } else {
      await api.stopRecording();
      recording.value = false;
      await refresh();
      showToast("语音已存本机");
    }
  } catch (error) {
    recording.value = false;
    showToast(String(error), "error");
  } finally {
    recordingBusy.value = false;
  }
}

async function acknowledgeReminder() {
  try {
    await api.acknowledgeReminder();
    reminder.value = null;
  } catch (error) {
    showToast(String(error), "error");
  }
}

function showToast(message: string, tone: "plain" | "error" = "plain") {
  toast.value = { message, tone };
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toast.value = null;
  }, 4200);
}
</script>

<template>
  <div v-if="today" class="app-frame" :class="{ 'quick-mode': quickMode }">
    <header class="window-bar" data-tauri-drag-region>
      <div class="brand" data-tauri-drag-region>
        <span class="brand-mark" />
        <span>DailyPlan</span>
      </div>
      <div class="window-actions">
        <button class="icon-button" aria-label="打开设置" @click="openSettings">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="10" cy="10" r="2.6" />
            <path d="M10 2.4v2M10 15.6v2M2.4 10h2M15.6 10h2M4.6 4.6l1.4 1.4M14 14l1.4 1.4M15.4 4.6 14 6M6 14l-1.4 1.4" />
          </svg>
        </button>
        <button class="icon-button" aria-label="收起主窗口" @click="api.hideMain()">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M5 5l10 10M15 5 5 15" />
          </svg>
        </button>
      </div>
    </header>

    <main class="app-body">
      <section class="day-header">
        <div>
          <p class="eyebrow">{{ dayLabel }}</p>
          <h1>{{ quickMode ? "记下，就继续。" : "今天，只做要紧的事。" }}</h1>
        </div>
        <div class="count-block">
          <strong>{{ taskCount }}</strong>
          <span>件未完成</span>
        </div>
      </section>

      <form class="quick-add" @submit.prevent="addTask">
        <input
          ref="quickInput"
          v-model="quickTitle"
          :placeholder="quickMode ? '一句话就够了，回车收起' : '写下现在要做的事…'"
          maxlength="500"
          autocomplete="off"
          @keydown="onQuickKeydown"
        />
        <button
          v-if="quickTitle.trim()"
          class="submit-note"
          type="submit"
          :disabled="adding"
        >
          {{ adding ? "保存中" : "记下" }}
        </button>
        <div v-else class="quick-tools">
          <button
            type="button"
            class="tool-button"
            :class="{ recording }"
            :disabled="recordingBusy"
            :aria-label="recording ? '停止并保存录音' : '开始录音'"
            @click="toggleRecording"
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <rect x="7" y="2.5" width="6" height="10" rx="3" />
              <path d="M4.5 9.5a5.5 5.5 0 0 0 11 0M10 15v2.5" />
            </svg>
          </button>
          <button
            type="button"
            class="tool-button"
            aria-label="框选截图并保存"
            @click="startScreenshot"
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path d="M3 7V4.5A1.5 1.5 0 0 1 4.5 3H7M13 3h2.5A1.5 1.5 0 0 1 17 4.5V7M17 13v2.5a1.5 1.5 0 0 1-1.5 1.5H13M7 17H4.5A1.5 1.5 0 0 1 3 15.5V13" />
              <circle cx="10" cy="10" r="2.2" />
            </svg>
          </button>
        </div>
      </form>

      <div class="sync-strip">
        <span class="sync-state" :class="syncTone">
          <i />
          {{ recording ? "正在录音" : syncLabel }}
        </span>
        <button
          class="sync-action"
          :disabled="syncing || !today.serverUrl || !today.hasToken"
          @click="sync"
        >
          {{ syncing ? "同步中…" : "现在同步" }}
        </button>
      </div>

      <div v-if="toast" class="toast" :class="toast.tone">{{ toast.message }}</div>

      <section v-if="today.tasks.length" class="task-list" aria-label="未完成待办">
        <TaskRow
          v-for="task in today.tasks"
          :key="task.taskId"
          :task="task"
          :busy="busyTask === task.taskId"
          @toggle="toggleTask"
          @open="openDetail"
        />
      </section>
      <section v-else class="empty-state">
        <span class="empty-line" />
        <h2>清单是空的</h2>
        <p>想到什么，就在上面留一句。截图和录音也可以直接变成待办。</p>
      </section>

      <section v-if="today.completed.length" class="completed-section">
        <button class="completed-toggle" @click="showCompleted = !showCompleted">
          <span>已完成 {{ today.completed.length }}</span>
          <span>{{ showCompleted ? "收起" : "展开" }}</span>
        </button>
        <div v-if="showCompleted" class="task-list completed-list">
          <TaskRow
            v-for="task in today.completed.slice(0, 20)"
            :key="task.taskId"
            :task="task"
            :busy="busyTask === task.taskId"
            @toggle="toggleTask"
            @open="openDetail"
          />
        </div>
      </section>
    </main>

    <footer class="app-foot">
      <span>离线先存本机</span>
      <span>{{ today.settings.workStart }} — {{ today.settings.workEnd }}</span>
    </footer>

    <div v-if="reminder" class="reminder-layer">
      <section class="reminder-panel" aria-label="上下班提醒">
        <header>
          <div>
            <p class="eyebrow">{{ reminderTimeLabel }}</p>
            <h2>{{ reminderTitle }}</h2>
          </div>
          <span class="reminder-count">{{ reminderTaskCount }}</span>
        </header>
        <p class="reminder-copy">勾选已经完成的，剩下的事会继续留在今天的清单里。</p>
        <div class="reminder-list">
          <TaskRow
            v-for="task in reminder.tasks"
            :key="task.taskId"
            :task="task"
            :busy="busyTask === task.taskId"
            @toggle="toggleTask"
            @open="openDetail"
          />
        </div>
        <button class="primary-button" @click="acknowledgeReminder">已查看</button>
      </section>
    </div>

    <TaskDetail
      v-if="selected"
      :task="selected"
      :busy="busyTask === selected.taskId"
      @close="selected = null"
      @saved="markCompletedFromDetail"
      @refreshed="refresh"
      @error="(message) => showToast(message, 'error')"
    />

    <SettingsPanel
      v-if="showSettings"
      :settings="settings"
      :today="today"
      @close="showSettings = false"
      @saved="settingsSaved"
      @error="(message) => showToast(message, 'error')"
    />
  </div>
</template>
