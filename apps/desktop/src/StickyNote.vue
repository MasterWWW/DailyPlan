<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { api, onAppEvent } from "./api";
import TaskRow from "./components/TaskRow.vue";
import type { TaskView, TodayView } from "./types";

const today = ref<TodayView | null>(null);
const busyTask = ref("");
const error = ref("");
const unlisteners: Array<() => void> = [];

const tasks = computed(() => today.value?.tasks ?? []);
const syncLabel = computed(() => {
  const value = today.value;
  if (!value) return "读取本机…";
  if (value.lastError) return "离线，待同步";
  if (value.pendingCount > 0) return `${value.pendingCount} 项待同步`;
  return value.serverUrl && value.hasToken ? "已同步" : "本机保存";
});

onMounted(async () => {
  await refresh();
  unlisteners.push(
    await onAppEvent("data-changed", () => void refresh()),
    await onAppEvent("sync-error", () => void refresh()),
  );
});

onBeforeUnmount(() => {
  for (const unlisten of unlisteners) unlisten();
});

async function refresh() {
  try {
    today.value = await api.today();
    error.value = "";
  } catch (failure) {
    error.value = String(failure);
  }
}

async function toggleTask(task: TaskView) {
  if (busyTask.value) return;
  busyTask.value = task.taskId;
  try {
    await api.updateTask({ taskId: task.taskId, completed: !task.completed });
    await refresh();
  } catch (failure) {
    error.value = String(failure);
  } finally {
    busyTask.value = "";
  }
}

async function openTask(task: TaskView) {
  try {
    await api.openTask(task.taskId);
  } catch (failure) {
    error.value = String(failure);
  }
}

async function openMain() {
  try {
    await api.revealMain();
  } catch (failure) {
    error.value = String(failure);
  }
}

async function hideSticky() {
  try {
    await api.hideSticky();
  } catch (failure) {
    error.value = String(failure);
  }
}
</script>

<template>
  <div class="app-frame sticky-frame">
    <header class="sticky-head" data-tauri-drag-region>
      <div class="sticky-identity" data-tauri-drag-region>
        <span class="brand-mark" />
        <div class="sticky-heading" data-tauri-drag-region>
          <strong data-tauri-drag-region>今日便签</strong>
          <span data-tauri-drag-region>{{ tasks.length }} 件未完成</span>
        </div>
      </div>
      <div class="sticky-actions">
        <button class="icon-button" aria-label="打开完整清单" @click="openMain">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M7 3h10v10M17 3 9 11M12 16H4V8" />
          </svg>
        </button>
        <button class="icon-button" aria-label="收起便签" @click="hideSticky">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path d="M5 5l10 10M15 5 5 15" />
          </svg>
        </button>
      </div>
    </header>

    <div class="sticky-body">
      <div v-if="error" class="sticky-error">{{ error }}</div>
      <div v-if="tasks.length" class="task-list sticky-list" aria-label="桌面便签待办">
        <TaskRow
          v-for="task in tasks"
          :key="task.taskId"
          :task="task"
          :busy="busyTask === task.taskId"
          @toggle="toggleTask"
          @open="openTask"
        />
      </div>
      <div v-else class="sticky-empty">
        <span class="empty-line" />
        <strong>今天清空了</strong>
        <button class="text-action" @click="openMain">打开完整清单</button>
      </div>
    </div>

    <footer class="sticky-foot">
      <span>{{ syncLabel }}</span>
      <button class="text-action" @click="openMain">完整清单</button>
    </footer>
  </div>
</template>
