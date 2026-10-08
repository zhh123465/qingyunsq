<script setup lang="ts">
import { ref, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NLayout, NLayoutSider, NLayoutContent, NMenu, NButton } from 'naive-ui';
import { renderIcon } from '@/utils/render-icon';
import {
  GridOutline, PeopleOutline, DocumentTextOutline,
  AlbumsOutline, ClipboardOutline, FlagOutline, ShieldCheckmarkOutline,
  SchoolOutline, SettingsOutline, ArrowBackOutline,
  FolderOpenOutline, BookOutline, CalendarOutline, ChatbubbleEllipsesOutline,
  MegaphoneOutline,
} from '@vicons/ionicons5';
import type { MenuOption } from 'naive-ui';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const currentRole = computed(() => auth.user?.role || localStorage.getItem('role') || '');

const menuOptions = computed<MenuOption[]>(() => {
  const base: MenuOption[] = [
    { label: '仪表盘', key: '/admin', icon: renderIcon(GridOutline) },
    { label: '用户管理', key: '/admin/users', icon: renderIcon(PeopleOutline) },
    { label: '帖子管理', key: '/admin/posts', icon: renderIcon(DocumentTextOutline) },
    { label: '评论管理', key: '/admin/comments', icon: renderIcon(ChatbubbleEllipsesOutline) },
    { label: '空间管理', key: '/admin/spaces', icon: renderIcon(AlbumsOutline) },
    { label: '资源管理', key: '/admin/resources', icon: renderIcon(FolderOpenOutline) },
    { label: '笔记管理', key: '/admin/notes', icon: renderIcon(BookOutline) },
    { label: '打卡管理', key: '/admin/checkin', icon: renderIcon(CalendarOutline) },
    { label: '公告管理', key: '/admin/announcements', icon: renderIcon(MegaphoneOutline) },
    { label: '举报管理', key: '/admin/reports', icon: renderIcon(FlagOutline) },
    { label: '敏感词', key: '/admin/sensitive-words', icon: renderIcon(ShieldCheckmarkOutline) },
    { label: '审计日志', key: '/admin/audit-logs', icon: renderIcon(ClipboardOutline) },
    { label: 'AI 配置', key: '/admin/ai-config', icon: renderIcon(SettingsOutline) },
  ];
  // 租户管理仅 SUPER_ADMIN 可见（跨租户操作）
  if (currentRole.value === 'SUPER_ADMIN') {
    base.push({ label: '租户管理', key: '/admin/tenants', icon: renderIcon(SchoolOutline) });
  }
  return base;
});

const activeKey = computed(() => {
  const path = route.path;
  if (path === '/admin') return '/admin';
  const prefixes = [
    '/admin/users', '/admin/posts', '/admin/comments', '/admin/spaces',
    '/admin/resources', '/admin/notes', '/admin/checkin', '/admin/announcements',
    '/admin/reports', '/admin/sensitive-words', '/admin/audit-logs',
    '/admin/ai-config', '/admin/tenants',
  ];
  return prefixes.find((p) => path.startsWith(p)) || '/admin';
});

const collapsed = ref(false);

function handleMenuClick(key: string) {
  router.push(key);
}
</script>

<template>
  <NLayout
    class="admin-shell"
    has-sider
  >
    <NLayoutSider
      bordered
      collapse-mode="width"
      :collapsed-width="64"
      :width="200"
      :collapsed="collapsed"
      :content-style="{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
      }"
      @update:collapsed="collapsed = $event"
      class="admin-shell-sider"
    >
      <div class="sider-toggle">
        <NButton
          text
          size="small"
          @click="collapsed = !collapsed"
        >
          {{ collapsed ? '▶' : '◀' }}
        </NButton>
      </div>
      <div class="sider-menu">
        <NMenu
          :value="activeKey"
          :collapsed="collapsed"
          :collapsed-width="64"
          :collapsed-icon-size="22"
          :options="menuOptions"
          @update:value="handleMenuClick"
        />
      </div>
      <div class="sider-footer">
        <NButton
          text
          size="small"
          class="return-btn"
          @click="router.push('/')"
        >
          <template #icon>
            <ArrowBackOutline />
          </template>
          <span v-if="!collapsed">返回前台</span>
        </NButton>
      </div>
    </NLayoutSider>
    <NLayoutContent class="admin-shell-content">
      <router-view />
    </NLayoutContent>
  </NLayout>
</template>

<style scoped lang="scss">
.admin-shell {
  height: 100vh;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(246, 248, 251, 0.98)),
    radial-gradient(circle at 12% 8%, rgba(0, 216, 191, 0.07), transparent 30%),
    radial-gradient(circle at 88% 16%, rgba(56, 189, 248, 0.06), transparent 32%);
}

.admin-shell-sider {
  height: 100vh;
  background: rgba(255, 255, 255, 0.88) !important;
  border-right: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 16px 0 46px rgba(15, 23, 42, 0.04);
}

.sider-toggle {
  flex: 0 0 auto;
  padding: 16px 12px 8px;
  display: flex;
  justify-content: flex-end;
}

.sider-menu {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 12px;
}

.sider-footer {
  flex: 0 0 auto;
  margin-top: auto;
  padding: 12px;
  border-top: 1px solid var(--cf-border);
  background: var(--cf-bg-readable);
}

.return-btn {
  width: 100%;
  justify-content: center;
}

.admin-shell-content {
  height: 100vh;
  min-width: 0;
  overflow: auto;
  background: transparent;
}

.admin-shell-content :deep(> div:not(.admin-dashboard-page)) {
  min-height: 100%;
  color: var(--cf-text-primary);
}

.admin-shell-content :deep(.admin-page) {
  padding: 28px 32px 40px;
}

.admin-shell-content :deep(h2) {
  margin: 0 0 18px !important;
  font-family: var(--cf-font-heading);
  font-size: 26px;
  letter-spacing: 0;
}

.admin-shell-content :deep(.admin-filterbar) {
  width: 100%;
  margin-bottom: 18px;
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 14px 36px rgba(15, 23, 42, 0.04);
}

.admin-shell-content :deep(.n-data-table),
.admin-shell-content :deep(.n-card) {
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.9);
  box-shadow:
    0 18px 55px rgba(15, 23, 42, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
}

.admin-shell-content :deep(.n-input),
.admin-shell-content :deep(.n-base-selection) {
  --n-border-radius: 12px !important;
}

.admin-shell-content :deep(.n-button) {
  --n-border-radius: 12px !important;
}
</style>
