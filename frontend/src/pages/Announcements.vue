<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { NIcon, NEmpty, NSpin, NPagination, NTag } from 'naive-ui';
import { MegaphoneOutline, ChevronForwardOutline } from '@vicons/ionicons5';
import { getAnnouncements } from '@/api/announcements';
import type { AnnouncementVO } from '@/types/announcement';

const router = useRouter();
const rows = ref<AnnouncementVO[]>([]);
const loading = ref(false);
const page = ref(1);
const size = ref(10);
const total = ref(0);

async function load() {
  loading.value = true;
  try {
    const res = await getAnnouncements({ page: page.value, size: size.value });
    rows.value = res.items || [];
    total.value = res.total || 0;
  } catch {
    rows.value = [];
    total.value = 0;
  }
  loading.value = false;
}

function open(id: number) {
  router.push(`/announcements/${id}`);
}

function fmtTime(iso?: string) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function levelText(l: string) {
  if (l === 'critical') return '重要';
  if (l === 'warning') return '警告';
  return '普通';
}

function levelTagType(l: string): 'info' | 'warning' | 'error' {
  if (l === 'critical') return 'error';
  if (l === 'warning') return 'warning';
  return 'info';
}

onMounted(() => load());
</script>

<template>
  <div class="announcements-page">
    <header class="page-header">
      <div class="header-left">
        <div class="icon-wrap">
          <NIcon size="28"><MegaphoneOutline /></NIcon>
        </div>
        <div>
          <h1>系统公告</h1>
          <p>关注平台通知、活动预告与运维事项</p>
        </div>
      </div>
    </header>

    <NSpin :show="loading">
      <div v-if="!loading && !rows.length" class="empty-wrap">
        <NEmpty description="暂无公告" />
      </div>

      <ul v-else class="list">
        <li
          v-for="row in rows"
          :key="row.id"
          class="item cf-card"
          @click="open(row.id)"
        >
          <div class="item-head">
            <NTag :type="levelTagType(row.level)" size="small" round>{{ levelText(row.level) }}</NTag>
            <NTag v-if="row.pinned === 1" type="error" size="small" round>置顶</NTag>
            <span class="item-time">{{ fmtTime(row.publishTime) || fmtTime(row.createdAt) }}</span>
          </div>
          <h3 class="item-title">{{ row.title }}</h3>
          <p v-if="row.summary" class="item-summary">{{ row.summary }}</p>
          <div class="item-footer">
            <span class="publisher">{{ row.publisherName || '管理员' }} 发布</span>
            <span class="read-more">
              阅读全文
              <NIcon size="14"><ChevronForwardOutline /></NIcon>
            </span>
          </div>
        </li>
      </ul>
    </NSpin>

    <div v-if="total > size" class="pagination-wrap">
      <NPagination
        v-model:page="page"
        :page-size="size"
        :item-count="total"
        @update:page="load"
      />
    </div>
  </div>
</template>

<style scoped>
.announcements-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 32px 24px 60px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 24px;
}

.header-left {
  display: flex;
  gap: 16px;
  align-items: center;
}

.icon-wrap {
  width: 56px;
  height: 56px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: var(--cf-primary-soft);
  color: var(--cf-primary);
}

.page-header h1 {
  font-family: var(--cf-font-heading);
  font-size: 28px;
  font-weight: 900;
  margin: 0;
  color: var(--cf-text-primary);
}

.page-header p {
  margin: 6px 0 0;
  color: var(--cf-text-secondary);
  font-size: 14px;
}

.empty-wrap {
  padding: 60px 0;
  display: grid;
  place-items: center;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 16px;
}

.item {
  padding: 20px 22px;
  border-radius: 18px;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.item:hover {
  transform: translateY(-2px);
  box-shadow: 0 22px 46px rgba(15, 23, 42, 0.08);
}

.item-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.item-time {
  margin-left: auto;
  color: var(--cf-text-muted);
  font-size: 12px;
}

.item-title {
  font-family: var(--cf-font-heading);
  font-weight: 800;
  font-size: 18px;
  margin: 0 0 8px;
  color: var(--cf-text-primary);
}

.item-summary {
  margin: 0 0 12px;
  color: var(--cf-text-secondary);
  font-size: 14px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.item-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--cf-text-muted);
}

.read-more {
  color: var(--cf-primary);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}

.pagination-wrap {
  margin-top: 28px;
  display: grid;
  place-items: center;
}
</style>
