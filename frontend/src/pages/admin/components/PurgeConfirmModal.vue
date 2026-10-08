<script setup lang="ts">
import { NModal, NButton, NSpace } from 'naive-ui';
import { computed, ref, watch } from 'vue';

interface CascadeCount {
  key: string;
  label: string;
  value: number;
}

const props = defineProps<{
  show: boolean;
  title?: string;
  /** 目标摘要（例如"ID: 123 - 「某帖子标题」- 作者 xxx"）。 */
  summary?: string;
  /** 预估级联删除的数量，展示给用户看。 */
  cascades?: CascadeCount[];
  /** 倒计时秒数。默认 3s，防止误点。 */
  countdown?: number;
  loading?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'confirm'): void;
}>();

const remaining = ref(props.countdown ?? 3);
let timer: number | null = null;

function startCountdown() {
  remaining.value = props.countdown ?? 3;
  if (timer) window.clearInterval(timer);
  timer = window.setInterval(() => {
    remaining.value = Math.max(0, remaining.value - 1);
    if (remaining.value <= 0 && timer) {
      window.clearInterval(timer);
      timer = null;
    }
  }, 1000);
}

function stopCountdown() {
  if (timer) {
    window.clearInterval(timer);
    timer = null;
  }
}

watch(
  () => props.show,
  (v) => {
    if (v) startCountdown();
    else stopCountdown();
  },
);

const disabled = computed(() => remaining.value > 0 || !!props.loading);
const confirmLabel = computed(() =>
  remaining.value > 0 ? `请等待 ${remaining.value}s` : '我已确认，彻底删除',
);

function onCancel() {
  emit('update:show', false);
}

function onConfirm() {
  if (disabled.value) return;
  emit('confirm');
}
</script>

<template>
  <NModal
    :show="show"
    :mask-closable="false"
    :close-on-esc="false"
    preset="card"
    style="width: 480px;"
    :title="props.title || '⚠ 确认彻底删除'"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="purge-body">
      <div class="warning">
        <strong>此操作不可撤销。</strong>
        目标数据将被物理删除，同时清理下方所列的关联记录。请仔细确认。
      </div>
      <div
        v-if="summary"
        class="target-line"
      >
        <span class="target-label">目标：</span>{{ summary }}
      </div>
      <div
        v-if="cascades && cascades.length"
        class="cascade-block"
      >
        <div class="cascade-title">将同时清理：</div>
        <ul>
          <li
            v-for="c in cascades"
            :key="c.key"
          >
            {{ c.label }}
            <span class="cascade-value">{{ c.value }} 条</span>
          </li>
        </ul>
      </div>
    </div>

    <template #footer>
      <NSpace justify="end">
        <NButton @click="onCancel">取消</NButton>
        <NButton
          type="error"
          :disabled="disabled"
          :loading="loading"
          @click="onConfirm"
        >
          {{ confirmLabel }}
        </NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.purge-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  font-size: 14px;
  color: var(--cf-text-primary);
}
.warning {
  padding: 10px 14px;
  background: rgba(239, 68, 68, 0.08);
  border-left: 3px solid #ef4444;
  color: #b91c1c;
  border-radius: 4px;
  line-height: 1.5;
}
.target-line {
  padding: 10px 12px;
  background: rgba(15, 23, 42, 0.04);
  border-radius: 8px;
  word-break: break-all;
}
.target-label {
  color: var(--cf-text-secondary);
  margin-right: 4px;
}
.cascade-block {
  font-size: 13px;
  color: var(--cf-text-secondary);
}
.cascade-title {
  margin-bottom: 6px;
}
.cascade-block ul {
  margin: 0;
  padding-left: 20px;
}
.cascade-block li {
  margin-bottom: 4px;
}
.cascade-value {
  color: var(--cf-text-primary);
  font-weight: 500;
  margin-left: 6px;
}
</style>
