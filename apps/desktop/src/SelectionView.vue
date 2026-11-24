<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { api } from "./api";

const image = ref("");
const failed = ref(false);
const busy = ref(false);
const dragStart = ref<{ x: number; y: number } | null>(null);
const pointer = ref({ x: 0, y: 0 });

const rect = computed(() => {
  if (!dragStart.value) return null;
  const x = Math.min(dragStart.value.x, pointer.value.x);
  const y = Math.min(dragStart.value.y, pointer.value.y);
  return {
    x,
    y,
    width: Math.abs(pointer.value.x - dragStart.value.x),
    height: Math.abs(pointer.value.y - dragStart.value.y),
  };
});

onMounted(async () => {
  try {
    const selection = await api.selectionImage();
    image.value = selection.dataUrl;
    window.addEventListener("keydown", onKey);
    window.addEventListener("contextmenu", onContextMenu);
  } catch {
    failed.value = true;
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKey);
  window.removeEventListener("contextmenu", onContextMenu);
});

function onKey(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    void cancel();
  }
}

function onContextMenu(event: MouseEvent) {
  event.preventDefault();
  void cancel();
}

function pointerDown(event: PointerEvent) {
  if (event.button !== 0 || busy.value) return;
  dragStart.value = { x: event.clientX, y: event.clientY };
  pointer.value = { x: event.clientX, y: event.clientY };
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function pointerMove(event: PointerEvent) {
  if (!dragStart.value) return;
  pointer.value = {
    x: Math.max(0, Math.min(window.innerWidth, event.clientX)),
    y: Math.max(0, Math.min(window.innerHeight, event.clientY)),
  };
}

async function pointerUp() {
  if (!dragStart.value || !rect.value) return;
  const area = rect.value;
  dragStart.value = null;
  if (area.width < 8 || area.height < 8) return;
  busy.value = true;
  try {
    await api.finishSelection({
      x: area.x / window.innerWidth,
      y: area.y / window.innerHeight,
      width: area.width / window.innerWidth,
      height: area.height / window.innerHeight,
    });
  } catch {
    busy.value = false;
    await cancel();
  }
}

async function cancel() {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.cancelSelection();
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main
    class="selection-screen"
    @pointerdown="pointerDown"
    @pointermove="pointerMove"
    @pointerup="pointerUp"
  >
    <img v-if="image" class="selection-source" :src="image" alt="" draggable="false" />
    <div v-if="!dragStart" class="selection-dim" />
    <div
      v-if="rect"
      class="selection-box"
      :style="{
        left: `${rect.x}px`,
        top: `${rect.y}px`,
        width: `${rect.width}px`,
        height: `${rect.height}px`,
      }"
    >
      <i class="corner top-left" />
      <i class="corner top-right" />
      <i class="corner bottom-left" />
      <i class="corner bottom-right" />
    </div>
    <div class="selection-hint">
      <strong>{{ failed ? "无法读取屏幕画面" : busy ? "正在保存…" : "拖动框选，松手保存" }}</strong>
      <span>{{ failed ? "按 Esc 返回" : "Esc 或右键取消" }}</span>
    </div>
  </main>
</template>
