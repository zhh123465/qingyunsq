<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { NIcon } from 'naive-ui';
import {
  InformationCircleOutline,
  WarningOutline,
  AlertCircleOutline,
  ArrowForwardOutline,
  CloseOutline,
  ChevronBackOutline,
  ChevronForwardOutline,
} from '@vicons/ionicons5';
import { getActiveAnnouncements } from '@/api/announcements';
import type { AnnouncementVO } from '@/types/announcement';

const router = useRouter();
const announcements = ref<AnnouncementVO[]>([]);
const currentIndex = ref(0);
const dismissedIds = ref<Set<number>>(new Set());

const STORAGE_KEY = 'dismissed_announcement_ids';

function loadDismissed(): Set<number> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw) as number[];
    return new Set(arr.filter((n) => typeof n === 'number'));
  } catch {
    return new Set();
  }
}

function saveDismissed(ids: Set<number>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(ids)));
  } catch {
    /* ignore quota */
  }
}

const visibleList = computed(() =>
  announcements.value.filter((a) => !dismissedIds.value.has(a.id)),
);

const current = computed<AnnouncementVO | null>(() => {
  if (!visibleList.value.length) return null;
  const idx = Math.min(currentIndex.value, visibleList.value.length - 1);
  return visibleList.value[idx];
});

const levelStyle = computed(() => {
  const level = current.value?.level || 'info';
  if (level === 'critical') {
    return {
      icon: AlertCircleOutline,
      bg: 'linear-gradient(90deg, rgba(239, 68, 68, 0.14), rgba(220, 38, 38, 0.10))',
      color: '#b91c1c',
      border: 'rgba(239, 68, 68, 0.35)',
      label: '重要',
    };
  }
  if (level === 'warning') {
    return {
      icon: WarningOutline,
      bg: 'linear-gradient(90deg, rgba(234, 179, 8, 0.15), rgba(202, 138, 4, 0.10))',
      color: '#a16207',
      border: 'rgba(234, 179, 8, 0.35)',
      label: '警告',
    };
  }
  return {
    icon: InformationCircleOutline,
    bg: 'linear-gradient(90deg, rgba(56, 189, 248, 0.14), rgba(14, 165, 233, 0.10))',
    color: '#0369a1',
    border: 'rgba(56, 189, 248, 0.32)',
    label: '公告',
  };
});

const displaySummary = computed(() => {
  const a = current.value;
  if (!a) return '';
  if (a.summary && a.summary.trim().length) return a.summary;
  const raw = (a.content || '').replace(/[#*`>\-\[\]\(\)!]/g, '').replace(/\s+/g, ' ').trim();
  return raw.length > 80 ? raw.slice(0, 80) + '…' : raw;
});

async function refresh() {
  try {
    const list = await getActiveAnnouncements();
    dismissedIds.value = loadDismissed();
    announcements.value = list;
    if (visibleList.value.length && currentIndex.value >= visibleList.value.length) {
      currentIndex.value = 0;
    }
  } catch {
    announcements.value = [];
  }
}

function dismiss(id: number) {
  const next = new Set(dismissedIds.value);
  next.add(id);
  dismissedIds.value = next;
  saveDismissed(next);
  if (currentIndex.value >= visibleList.value.length) {
    currentIndex.value = 0;
  }
}

function openDetail(id: number) {
  router.push(`/announcements/${id}`);
}

function prev() {
  if (!visibleList.value.length) return;
  currentIndex.value = (currentIndex.value - 1 + visibleList.value.length) % visibleList.value.length;
}

function next() {
  if (!visibleList.value.length) return;
  currentIndex.value = (currentIndex.value + 1) % visibleList.value.length;
}

onMounted(() => refresh());

defineExpose({ refresh });
</script>

<template>
  <div v-if="current" class="ann-banner" :style="{ background: levelStyle.bg, borderColor: levelStyle.border }">
    <div class="ann-inner">
      <div class="ann-left" @click="openDetail(current.id)">
        <NIcon :size="18" :color="levelStyle.color">
          <component :is="levelStyle.icon" />
        </NIcon>
        <span class="ann-level-tag" :style="{ color: levelStyle.color, borderColor: levelStyle.border }">
          {{ levelStyle.label }}
        </span>
        <span v-if="current.pinned === 1" class="ann-pin" :style="{ color: levelStyle.color }">置顶</span>
        <strong class="ann-title" :style="{ color: levelStyle.color }">{{ current.title }}</strong>
        <span class="ann-summary">{{ displaySummary }}</span>
      </div>
      <div class="ann-right">
        <button v-if="visibleList.length > 1" class="ann-nav-btn" @click="prev" title="上一条">
          <NIcon :size="14"><ChevronBackOutline /></NIcon>
        </button>
        <span v-if="visibleList.length > 1" class="ann-count">
          {{ Math.min(currentIndex + 1, visibleList.length) }}/{{ visibleList.length }}
        </span>
        <button v-if="visibleList.length > 1" class="ann-nav-btn" @click="next" title="下一条">
          <NIcon :size="14"><ChevronForwardOutline /></NIcon>
        </button>
        <button class="ann-detail-btn" :style="{ color: levelStyle.color }" @click="openDetail(current.id)">
          查看详情
          <NIcon :size="12"><ArrowForwardOutline /></NIcon>
        </button>
        <button class="ann-close-btn" @click="dismiss(current.id)" title="关闭该条">
          <NIcon :size="16"><CloseOutline /></NIcon>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ann-banner {
  position: sticky;
  top: 56px;
  z-index: 40;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  border-top: 1px solid transparent;
  backdrop-filter: blur(10px) saturate(150%);
  -webkit-backdrop-filter: blur(10px) saturate(150%);
}

.ann-inner {
  width: 100%;
  min-height: 40px;
  padding: 8px 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  justify-content: space-between;
}

.ann-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  cursor: pointer;
  overflow: hidden;
}

.ann-level-tag {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid;
  white-space: nowrap;
}

.ann-pin {
  font-size: 12px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.05);
  white-space: nowrap;
}

.ann-title {
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
  flex-shrink: 0;
}

.ann-summary {
  font-size: 13px;
  color: var(--cf-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.ann-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.ann-nav-btn,
.ann-close-btn,
.ann-detail-btn {
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--cf-text-muted);
  padding: 4px 6px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: background 0.18s ease, color 0.18s ease;
  font: inherit;
}

.ann-nav-btn:hover,
.ann-close-btn:hover,
.ann-detail-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  color: var(--cf-text-primary);
}

.ann-detail-btn {
  font-size: 13px;
  font-weight: 600;
  padding: 4px 10px;
}

.ann-count {
  font-size: 12px;
  color: var(--cf-text-muted);
  font-weight: 600;
  min-width: 32px;
  text-align: center;
}

html[data-theme='dark'] .ann-banner {
  border-bottom-color: rgba(255, 255, 255, 0.08);
}
html[data-theme='dark'] .ann-summary {
  color: rgba(255, 255, 255, 0.72);
}
html[data-theme='dark'] .ann-pin {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.9);
}

@media (max-width: 720px) {
  .ann-inner {
    padding: 6px 12px;
    gap: 10px;
  }
  .ann-summary {
    display: none;
  }
  .ann-detail-btn span,
  .ann-count {
    display: none;
  }
}
</style>
