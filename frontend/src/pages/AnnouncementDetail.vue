<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NIcon, NSpin, NButton, NTag, NEmpty } from 'naive-ui';
import { ArrowBackOutline } from '@vicons/ionicons5';
import { getAnnouncement } from '@/api/announcements';
import type { AnnouncementVO } from '@/types/announcement';
import { renderMarkdown } from '@/utils/markdown';

const route = useRoute();
const router = useRouter();

const announcement = ref<AnnouncementVO | null>(null);
const loading = ref(false);
const loadError = ref('');

const htmlContent = computed(() => {
  if (!announcement.value?.content) return '';
  return renderMarkdown(announcement.value.content).html;
});

const levelText = computed(() => {
  if (!announcement.value) return '';
  if (announcement.value.level === 'critical') return '重要';
  if (announcement.value.level === 'warning') return '警告';
  return '普通';
});

const levelType = computed<'info' | 'warning' | 'error'>(() => {
  if (!announcement.value) return 'info';
  if (announcement.value.level === 'critical') return 'error';
  if (announcement.value.level === 'warning') return 'warning';
  return 'info';
});

function fmtTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const idParam = route.params.id;
    const id = Array.isArray(idParam) ? idParam[0] : idParam;
    const numId = Number(id);
    if (!numId) throw new Error('无效的公告 ID');
    announcement.value = await getAnnouncement(numId);
  } catch (e: any) {
    loadError.value = e?.message || '公告不存在或已下线';
    announcement.value = null;
  }
  loading.value = false;
}

onMounted(() => load());
</script>

<template>
  <div class="detail-page">
    <div class="detail-nav">
      <NButton text @click="router.back()">
        <template #icon><NIcon><ArrowBackOutline /></NIcon></template>
        返回
      </NButton>
    </div>

    <NSpin :show="loading">
      <div v-if="!loading && !announcement" class="empty-wrap">
        <NEmpty :description="loadError || '公告不存在或已下线'" />
      </div>

      <article v-if="announcement" class="detail-card cf-card">
        <header class="detail-head">
          <div class="tag-row">
            <NTag :type="levelType" size="small" round>{{ levelText }}</NTag>
            <NTag v-if="announcement.pinned === 1" type="error" size="small" round>置顶</NTag>
          </div>
          <h1 class="detail-title">{{ announcement.title }}</h1>
          <div class="detail-meta">
            <span>{{ announcement.publisherName || '管理员' }}</span>
            <span>·</span>
            <span>{{ fmtTime(announcement.publishTime) || fmtTime(announcement.createdAt) }}</span>
          </div>
          <p v-if="announcement.summary" class="detail-summary">{{ announcement.summary }}</p>
        </header>
        <div class="markdown-body" v-html="htmlContent" />
      </article>
    </NSpin>
  </div>
</template>

<style scoped>
.detail-page {
  max-width: 820px;
  margin: 0 auto;
  padding: 20px 24px 60px;
}

.detail-nav {
  margin-bottom: 12px;
}

.empty-wrap {
  padding: 80px 0;
  display: grid;
  place-items: center;
}

.detail-card {
  padding: 36px 40px;
  border-radius: 22px;
}

.detail-head {
  margin-bottom: 28px;
  padding-bottom: 22px;
  border-bottom: 1px solid var(--cf-border);
}

.tag-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.detail-title {
  font-family: var(--cf-font-heading);
  font-weight: 900;
  font-size: 28px;
  margin: 0 0 12px;
  color: var(--cf-text-primary);
  line-height: 1.35;
}

.detail-meta {
  display: flex;
  gap: 10px;
  font-size: 13px;
  color: var(--cf-text-muted);
}

.detail-summary {
  margin: 14px 0 0;
  padding: 12px 16px;
  border-left: 3px solid var(--cf-primary);
  background: var(--cf-primary-soft);
  color: var(--cf-text-secondary);
  border-radius: 6px;
  font-size: 15px;
  line-height: 1.7;
}

.markdown-body {
  font-size: 15px;
  line-height: 1.85;
  color: var(--cf-text-primary);
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin: 22px 0 10px;
  font-family: var(--cf-font-heading);
}

.markdown-body :deep(pre) {
  padding: 14px 16px;
  background: rgba(15, 23, 42, 0.05);
  border-radius: 8px;
  overflow: auto;
}

.markdown-body :deep(code) {
  background: rgba(15, 23, 42, 0.06);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.92em;
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.markdown-body :deep(a) {
  color: var(--cf-primary);
  text-decoration: underline;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid var(--cf-border-strong);
  padding: 6px 14px;
  margin: 12px 0;
  color: var(--cf-text-secondary);
  background: var(--cf-bg-soft);
  border-radius: 4px;
}

.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
</style>
