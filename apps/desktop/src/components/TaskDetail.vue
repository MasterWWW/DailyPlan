<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api } from "../api";
import type { Priority, TaskUpdateInput, TaskView } from "../types";

const props = defineProps<{
  task: TaskView;
  busy: boolean;
}>();

const emit = defineEmits<{
  close: [];
  saved: [];
  refreshed: [];
  error: [message: string];
}>();

const title = ref("");
const dueDate = ref("");
const dueTime = ref("");
const priority = ref<Priority>("normal");
const preview = ref<string | null>(null);
const attachmentData = ref<Record<string, string>>({});
const retrying = ref("");

watch(
  () => props.task,
  (task) => {
    title.value = task.title;
    dueDate.value = task.dueDate;
    dueTime.value = task.dueTime ?? "";
    priority.value = task.priority;
    loadAttachments(task);
  },
  { immediate: true },
);

onMounted(() => {
  loadAttachments(props.task);
});

async function loadAttachments(task: TaskView) {
  for (const attachment of task.attachments) {
    if (attachmentData.value[attachment.attachmentId]) continue;
    try {
      attachmentData.value[attachment.attachmentId] = await api.attachmentData(
        attachment.attachmentId,
      );
    } catch {
      // The record remains usable while its original is still being downloaded.
    }
  }
}

async function save() {
  if (!title.value.trim()) {
    emit("error", "标题不能为空");
    return;
  }
  const input: TaskUpdateInput = {
    taskId: props.task.taskId,
    title: title.value,
    dueDate: dueDate.value,
    priority: priority.value,
  };
  if (dueTime.value) {
    input.dueTime = dueTime.value;
  } else {
    input.clearDueTime = true;
  }
  try {
    await api.updateTask(input);
    emit("saved");
  } catch (error) {
    emit("error", String(error));
  }
}

async function complete() {
  try {
    await api.updateTask({ taskId: props.task.taskId, completed: !props.task.completed });
    emit("saved");
  } catch (error) {
    emit("error", String(error));
  }
}

async function retryTranscription(attachmentId: string) {
  if (retrying.value) return;
  retrying.value = attachmentId;
  try {
    await api.retryTranscription(attachmentId);
    emit("refreshed");
  } catch (error) {
    emit("error", String(error));
  } finally {
    retrying.value = "";
  }
}
</script>

<template>
  <div class="sheet-backdrop" @click.self="emit('close')">
    <section class="detail-sheet" aria-label="待办详情">
      <header class="sheet-head">
        <button class="quiet-button" @click="emit('close')">收起</button>
        <span class="eyebrow">待办详情</span>
        <button class="text-action" :disabled="busy" @click="save">保存</button>
      </header>

      <label class="field title-field">
        <span>标题</span>
        <textarea v-model="title" rows="2" maxlength="500" />
      </label>

      <div class="field-grid">
        <label class="field">
          <span>截止日</span>
          <input v-model="dueDate" type="date" />
        </label>
        <label class="field">
          <span>具体时刻</span>
          <input v-model="dueTime" type="time" />
        </label>
      </div>

      <div class="field">
        <span>紧急程度</span>
        <div class="segmented">
          <button
            v-for="option in [
              ['normal', '普通'],
              ['urgent', '紧急'],
              ['very_urgent', '非常紧急'],
            ] as const"
            :key="option[0]"
            :class="{ active: priority === option[0] }"
            @click="priority = option[0]"
          >
            {{ option[1] }}
          </button>
        </div>
      </div>

      <div v-if="task.attachments.length" class="attachment-stack">
        <span class="field-label">原件</span>
        <div
          v-for="attachment in task.attachments"
          :key="attachment.attachmentId"
          class="attachment-block"
        >
          <template v-if="attachment.kind === 'screenshot'">
            <button
              v-if="attachmentData[attachment.attachmentId]"
              class="image-button"
              @click="preview = attachmentData[attachment.attachmentId]"
            >
              <img :src="attachmentData[attachment.attachmentId]" alt="待办截图" />
            </button>
            <div v-else class="attachment-loading">正在准备原图…</div>
          </template>
          <template v-else>
            <audio
              v-if="attachmentData[attachment.attachmentId]"
              :src="attachmentData[attachment.attachmentId]"
              controls
              preload="metadata"
            />
            <div v-else class="attachment-loading">正在准备录音…</div>
            <p v-if="attachment.transcript" class="transcript">{{ attachment.transcript }}</p>
            <p v-else-if="attachment.transcriptionStatus === 'failed'" class="capture-error">
              转写失败，录音已保留。
            </p>
            <p v-else class="transcript muted">录音已保存，等待转写。</p>
            <p v-if="attachment.transcriptionError" class="capture-error">
              {{ attachment.transcriptionError }}
            </p>
            <button
              v-if="attachment.transcriptionStatus === 'failed'"
              class="text-action"
              :disabled="retrying === attachment.attachmentId"
              @click="retryTranscription(attachment.attachmentId)"
            >
              {{ retrying === attachment.attachmentId ? "正在重试…" : "重试转写" }}
            </button>
          </template>
        </div>
      </div>

      <footer class="sheet-foot">
        <button class="danger-link" @click="complete">
          {{ task.completed ? "恢复为未完成" : "标记为已完成" }}
        </button>
        <span class="created-note">创建于 {{ task.createdOn }}</span>
      </footer>
    </section>
  </div>

  <div v-if="preview" class="image-preview" @click="preview = null">
    <img :src="preview" alt="待办截图原图" />
  </div>
</template>
