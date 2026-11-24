<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { api } from "../api";
import type { AppSettings, SettingsInput, TodayView } from "../types";

const props = defineProps<{
  settings: AppSettings | null;
  today: TodayView;
}>();

const emit = defineEmits<{
  close: [];
  saved: [];
  error: [message: string];
}>();

const form = reactive<SettingsInput>({
  workdays: [1, 2, 3, 4, 5],
  timeZone: "Asia/Shanghai",
  workStart: "09:00",
  workEnd: "18:00",
  reminderLeadMinutes: 15,
  serverUrl: "",
  textShortcut: "CommandOrControl+Shift+Space",
  screenshotShortcut: "CommandOrControl+Shift+Enter",
  autostart: false,
});
const token = ref("");
const clearToken = ref(false);
const testing = ref(false);
const saving = ref(false);
const connectionNote = ref("");
const weekdays = [
  [1, "一"],
  [2, "二"],
  [3, "三"],
  [4, "四"],
  [5, "五"],
  [6, "六"],
  [7, "日"],
] as const;

watch(
  () => props.settings,
  (settings) => {
    if (!settings) return;
    Object.assign(form, {
      workdays: [...settings.workdays],
      timeZone: settings.timeZone,
      workStart: settings.workStart,
      workEnd: settings.workEnd,
      reminderLeadMinutes: settings.reminderLeadMinutes,
      serverUrl: settings.serverUrl,
      textShortcut: settings.textShortcut,
      screenshotShortcut: settings.screenshotShortcut,
      autostart: settings.autostart,
    });
  },
  { immediate: true },
);

function toggleWorkday(day: number) {
  if (form.workdays.includes(day)) {
    if (form.workdays.length === 1) return;
    form.workdays = form.workdays.filter((value) => value !== day);
  } else {
    form.workdays = [...form.workdays, day].sort((left, right) => left - right);
  }
}

async function test() {
  testing.value = true;
  connectionNote.value = "";
  try {
    const status = await api.testConnection(form.serverUrl, token.value);
    connectionNote.value = `连接正常 · 数据集 ${status.datasetId} · 变更序号 ${status.cursor}`;
  } catch (error) {
    connectionNote.value = String(error);
  } finally {
    testing.value = false;
  }
}

async function save() {
  saving.value = true;
  try {
    await api.saveSettings(form, token.value || null, clearToken.value);
    token.value = "";
    clearToken.value = false;
    emit("saved");
  } catch (error) {
    emit("error", String(error));
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="settings-layer">
    <section class="settings-panel">
      <header class="settings-head">
        <button class="quiet-button" @click="emit('close')">返回</button>
        <span class="eyebrow">设置</span>
        <button class="text-action" :disabled="saving" @click="save">
          {{ saving ? "保存中…" : "保存" }}
        </button>
      </header>

      <div class="settings-scroll">
        <section class="settings-group">
          <h2>工作节奏</h2>
          <div class="weekday-row">
            <button
              v-for="day in weekdays"
              :key="day[0]"
              class="weekday"
              :class="{ active: form.workdays.includes(day[0]) }"
              @click="toggleWorkday(day[0])"
            >
              {{ day[1] }}
            </button>
          </div>
          <div class="field-grid">
            <label class="field">
              <span>上班</span>
              <input v-model="form.workStart" type="time" />
            </label>
            <label class="field">
              <span>下班</span>
              <input v-model="form.workEnd" type="time" />
            </label>
          </div>
          <div class="field-grid">
            <label class="field">
              <span>下班前回顾</span>
              <input v-model.number="form.reminderLeadMinutes" type="number" min="0" max="240" />
            </label>
            <label class="field">
              <span>时区</span>
              <input v-model="form.timeZone" type="text" />
            </label>
          </div>
        </section>

        <section class="settings-group">
          <h2>内网同步</h2>
          <label class="field">
            <span>服务器地址</span>
            <input
              v-model="form.serverUrl"
              type="url"
              placeholder="http://192.168.10.20:8787"
              autocomplete="off"
            />
          </label>
          <label class="field">
            <span>访问令牌 {{ settings?.hasToken ? "（已保存）" : "" }}</span>
            <input
              v-model="token"
              type="password"
              :placeholder="settings?.hasToken ? '留空则保留现有令牌' : '填写部署时生成的令牌'"
              autocomplete="new-password"
            />
          </label>
          <div class="inline-actions">
            <button class="outline-button" :disabled="testing" @click="test">
              {{ testing ? "正在连接…" : "测试连接" }}
            </button>
            <button
              v-if="settings?.hasToken"
              class="quiet-button"
              :class="{ danger: clearToken }"
              @click="clearToken = !clearToken"
            >
              {{ clearToken ? "将清除令牌（点此撤销）" : "清除已保存令牌" }}
            </button>
          </div>
          <p v-if="connectionNote" class="settings-note">{{ connectionNote }}</p>
          <dl class="sync-facts">
            <div>
              <dt>待同步</dt>
              <dd>{{ today.pendingCount }}</dd>
            </div>
            <div>
              <dt>最近成功</dt>
              <dd>{{ today.lastSuccessAt ? new Date(today.lastSuccessAt).toLocaleString() : "尚未同步" }}</dd>
            </div>
          </dl>
          <p v-if="today.lastError" class="settings-note error">{{ today.lastError }}</p>
        </section>

        <section class="settings-group">
          <h2>快捷入口</h2>
          <label class="field">
            <span>文字记录</span>
            <input v-model="form.textShortcut" spellcheck="false" />
          </label>
          <label class="field">
            <span>框选截图</span>
            <input v-model="form.screenshotShortcut" spellcheck="false" />
          </label>
          <label class="toggle-row">
            <span>
              <strong>登录时启动</strong>
              <small>保持便签和上下班提醒常驻</small>
            </span>
            <input v-model="form.autostart" type="checkbox" />
          </label>
        </section>

        <p class="settings-footnote">
          快捷键使用 Tauri 的写法，例如 CommandOrControl+Shift+Space。离线记录始终可用，
          连接失败时会保留待同步数量。
        </p>
      </div>
    </section>
  </div>
</template>
