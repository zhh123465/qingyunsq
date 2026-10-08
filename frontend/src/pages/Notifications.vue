<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import {
  NSpin, NBadge, useMessage, NIcon
} from 'naive-ui';
import {
  HeartOutline, ChatbubbleOutline, ReturnDownBackOutline,
  CheckmarkCircleOutline, PeopleOutline, ArrowBackOutline,
  NotificationsOutline, CheckmarkDoneOutline, FolderOpenOutline
} from '@vicons/ionicons5';
import { getNotifications, getUnreadCount, markRead, markAllRead } from '@/api/notifications';
import type { NotificationVO } from '@/types/notification';
import type { Component } from 'vue';

const router = useRouter();
const message = useMessage();
const notifications = ref<NotificationVO[]>([]);
const unreadCount = ref(0);
const loading = ref(false);
const hasMore = ref(true);

const typeIcons: Record<string, Component> = {
  LIKE: HeartOutline,
  COMMENT: ChatbubbleOutline,
  REPLY: ReturnDownBackOutline,
  ACCEPT: CheckmarkCircleOutline,
  JOIN: PeopleOutline,
  RESOURCE_REVIEW: FolderOpenOutline,
};

const typeColors: Record<string, string> = {
  LIKE: '#f43f5e',
  COMMENT: '#3b82f6',
  REPLY: '#10b981',
  ACCEPT: '#f59e0b',
  JOIN: '#8b5cf6',
  RESOURCE_REVIEW: '#0ea5e9',
};

const typeBgColors: Record<string, string> = {
  LIKE: 'rgba(244, 63, 94, 0.15)',
  COMMENT: 'rgba(59, 130, 246, 0.15)',
  REPLY: 'rgba(16, 185, 129, 0.15)',
  ACCEPT: 'rgba(245, 158, 11, 0.15)',
  JOIN: 'rgba(139, 92, 246, 0.15)',
  RESOURCE_REVIEW: 'rgba(14, 165, 233, 0.15)',
};

function getTypeIcon(type: string): Component {
  return typeIcons[type] || ChatbubbleOutline;
}

function getTypeColor(type: string): string {
  return typeColors[type] || '#8b949e';
}

function getTypeBg(type: string): string {
  return typeBgColors[type] || 'rgba(139, 148, 158, 0.15)';
}

async function loadNotifications(reset = false) {
  if (loading.value) return;
  loading.value = true;
  try {
    const cursor = reset ? undefined : notifications.value[notifications.value.length - 1]?.id;
    const list = await getNotifications(cursor, 20);
    if (reset) {
      notifications.value = list;
    } else {
      notifications.value.push(...list);
    }
    hasMore.value = list.length >= 20;
  } catch {
    // ignore
  }
  loading.value = false;
}

async function loadUnreadCount() {
  try {
    unreadCount.value = await getUnreadCount();
  } catch {
    // ignore
  }
}

async function handleClick(notif: NotificationVO) {
  if (!notif.isRead) {
    try {
      await markRead(notif.id);
      notif.isRead = true;
      unreadCount.value = Math.max(0, unreadCount.value - 1);
    } catch {
      // ignore
    }
  }
  if (notif.redirectUrl) {
    router.push(notif.redirectUrl);
  }
}

async function handleMarkAllRead() {
  try {
    await markAllRead();
    notifications.value.forEach((n) => (n.isRead = true));
    unreadCount.value = 0;
    message.success('已全部标记为已读');
  } catch {
    message.error('操作失败');
  }
}

function loadMore() {
  if (!loading.value && hasMore.value) {
    loadNotifications(false);
  }
}

function handleScroll(e: Event) {
  const target = e.target as HTMLElement;
  if (target.scrollHeight - target.scrollTop - target.clientHeight < 100) {
    loadMore();
  }
}

onMounted(async () => {
  await loadNotifications(true);
  await loadUnreadCount();
});
</script>

<template>
  <div
    class="notifications-layout"
    @scroll="handleScroll"
  >
    <div class="header-banner">
      <div class="banner-content">
        <button
          class="action-btn back-btn"
          title="返回"
          @click="router.back()"
        >
          <n-icon><ArrowBackOutline /></n-icon>
        </button>
        <h1 class="page-title gradient-text">
          <n-icon
            size="32"
            class="title-icon"
          >
            <NotificationsOutline />
          </n-icon>
          通知中心
        </h1>
        <n-badge
          v-if="unreadCount > 0"
          :value="unreadCount"
          type="error"
          class="unread-badge"
        />
      </div>
      <div class="header-actions">
        <button 
          class="neon-btn outline-btn" 
          :disabled="unreadCount === 0" 
          @click="handleMarkAllRead"
        >
          <n-icon style="margin-right: 6px;">
            <CheckmarkDoneOutline />
          </n-icon>
          全部已读
        </button>
      </div>
    </div>

    <div class="main-container">
      <div class="glass-card list-card">
        <div
          v-if="loading && notifications.length === 0"
          class="loading-state"
        >
          <n-spin size="large" />
          <p>加载中...</p>
        </div>
        
        <div
          v-else-if="notifications.length === 0"
          class="empty-state"
        >
          <n-icon
            size="64"
            color="#30363d"
          >
            <NotificationsOutline />
          </n-icon>
          <h3>暂无通知</h3>
          <p>什么时候才能收到你的消息呢？</p>
        </div>

        <div
          v-else
          class="notif-list"
        >
          <div
            v-for="notif in notifications"
            :key="notif.id"
            class="notif-item"
            :class="{ unread: !notif.isRead }"
            @click="handleClick(notif)"
          >
            <div
              class="notif-icon-wrap"
              :style="{ backgroundColor: getTypeBg(notif.type), color: getTypeColor(notif.type) }"
            >
              <n-badge
                :show="!notif.isRead"
                dot
                type="error"
                :offset="[2, 2]"
              >
                <n-icon size="24">
                  <component :is="getTypeIcon(notif.type)" />
                </n-icon>
              </n-badge>
            </div>
            
            <div class="notif-content">
              <div class="notif-header">
                <span class="notif-title">{{ notif.title }}</span>
                <span class="notif-time">{{ new Date(notif.createdAt).toLocaleString() }}</span>
              </div>
              <p class="notif-desc">
                {{ notif.content }}
              </p>
            </div>
          </div>
          
          <div
            v-if="loading && notifications.length > 0"
            class="loading-more"
          >
            <n-spin size="small" />
          </div>
          
          <div
            v-if="!hasMore && notifications.length > 0"
            class="end-state"
          >
            <div class="divider" />
            <span>— 到底了 —</span>
            <div class="divider" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.notifications-layout {
  min-height: calc(100vh - 112px);
  padding: 8px 0 32px;
  overflow-y: auto;
  color: var(--cf-text-primary);
}

.header-banner,
.main-container {
  width: min(100%, 1040px);
  margin: 0 auto;
}

.header-banner {
  min-height: 72px;
  padding: 14px 4px 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.banner-content {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 14px;
}

.back-btn {
  width: 42px;
  height: 42px;
  border: 1px solid var(--cf-border);
  border-radius: 12px;
  background: var(--cf-bg-readable);
  color: var(--cf-text-secondary);
  display: inline-grid;
  place-items: center;
  cursor: pointer;
  box-shadow: var(--cf-shadow-soft);
  transition: color 0.2s ease, border-color 0.2s ease, background 0.2s ease;

  &:hover {
    color: var(--cf-primary);
    border-color: color-mix(in srgb, var(--cf-primary) 42%, transparent);
    background: var(--cf-primary-soft);
  }
}

.page-title {
  margin: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 26px;
  font-weight: 900;
  letter-spacing: 0;
}

.title-icon {
  color: var(--cf-primary);
}

.unread-badge {
  margin-left: 2px;
}

.outline-btn {
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--cf-border) !important;
  border-radius: 12px;
  background: var(--cf-bg-readable) !important;
  color: var(--cf-text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 800;
  cursor: pointer;
  box-shadow: var(--cf-shadow-soft) !important;

  &:hover:not(:disabled) {
    color: var(--cf-primary);
    border-color: color-mix(in srgb, var(--cf-primary) 38%, transparent) !important;
    background: var(--cf-primary-soft) !important;
  }

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

.main-container {
  padding-top: 18px;
}

.list-card {
  min-height: 440px;
  padding: 8px;
  background: var(--cf-card-bg);
  border: 1px solid var(--cf-card-border);
  border-radius: 20px;
  box-shadow: var(--cf-card-shadow);
  backdrop-filter: blur(24px) saturate(150%);
  -webkit-backdrop-filter: blur(24px) saturate(150%);
}

.notif-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.notif-item {
  min-height: 84px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  border-left: 3px solid transparent;
  border-radius: 14px;
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease;

  &:hover {
    background: color-mix(in srgb, var(--cf-primary) 6%, transparent);
  }

  &.unread {
    background: color-mix(in srgb, var(--cf-primary) 8%, transparent);
    border-left-color: var(--cf-primary);

    .notif-title {
      color: var(--cf-text-primary);
      font-weight: 900;
    }
  }
}

.notif-icon-wrap {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}

.notif-content {
  flex: 1;
  min-width: 0;
}

.notif-header {
  margin-bottom: 7px;
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.notif-title {
  color: var(--cf-text-primary);
  font-size: 16px;
  font-weight: 750;
  line-height: 1.35;
}

.notif-time {
  color: var(--cf-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.notif-desc {
  margin: 0;
  color: var(--cf-text-secondary);
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.loading-state,
.empty-state {
  min-height: 360px;
  padding: 48px 0;
  color: var(--cf-text-secondary);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;

  h3 {
    margin: 16px 0 8px;
    color: var(--cf-text-primary);
    font-size: 18px;
  }

  p {
    margin: 0;
    font-size: 14px;
  }
}

.loading-more {
  padding: 20px;
  display: flex;
  justify-content: center;
}

.end-state {
  padding: 24px;
  color: var(--cf-text-muted);
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 16px;

  .divider {
    flex: 1;
    height: 1px;
    background: var(--cf-border);
  }
}

@media (max-width: 720px) {
  .notifications-layout {
    padding-bottom: 20px;
  }

  .header-banner {
    padding-top: 0;
    flex-direction: column;
    align-items: stretch;
  }

  .header-actions {
    display: flex;
  }

  .outline-btn {
    width: 100%;
    justify-content: center;
  }

  .notif-header {
    flex-direction: column;
    gap: 4px;
  }
}
</style>
