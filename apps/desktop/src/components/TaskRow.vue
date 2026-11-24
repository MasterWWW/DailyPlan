<script setup lang="ts">
import type { TaskView } from "../types";

defineProps<{
  task: TaskView;
  busy: boolean;
}>();

const emit = defineEmits<{
  toggle: [task: TaskView];
  open: [task: TaskView];
}>();

function dueLabel(task: TaskView): string {
  const [, month, day] = task.dueDate.split("-").map(Number);
  const today = new Date();
  const date = new Date(`${task.dueDate}T12:00:00`);
  const sameYear = date.getFullYear() === today.getFullYear();
  const dateText = sameYear ? `${month}月${day}日` : `${date.getFullYear()}年${month}月${day}日`;
  return task.dueTime ? `${dateText} ${task.dueTime}` : dateText;
}

function attentionLabel(task: TaskView): string {
  if (task.overdue) return "逾期";
  if (task.priority === "very_urgent") return "非常紧急";
  if (task.priority === "urgent") return "紧急";
  if (task.dueSoon) return "临期";
  return "";
}
</script>

<template>
  <article class="task-row" :class="{ completed: task.completed }">
    <button
      class="task-check"
      :class="{ checked: task.completed }"
      :aria-label="task.completed ? '恢复待办' : '完成待办'"
      :disabled="busy"
      @click="emit('toggle', task)"
    >
      <svg v-if="task.completed" viewBox="0 0 16 16" aria-hidden="true">
        <path d="m3.1 8.2 3.1 3.1 6.7-6.7" />
      </svg>
    </button>
    <button class="task-body" @click="emit('open', task)">
      <span class="task-title">{{ task.title }}</span>
      <span class="task-note">
        <span v-if="attentionLabel(task)" class="attention" :class="{ overdue: task.overdue }">
          {{ attentionLabel(task) }}
        </span>
        <span v-if="task.attachments.length" class="attachment-count">
          {{ task.attachments.some((item) => item.kind === "recording") ? "语音" : "图片" }}
          {{ task.attachments.length }}
        </span>
      </span>
    </button>
    <time class="task-due">{{ dueLabel(task) }}</time>
  </article>
</template>
