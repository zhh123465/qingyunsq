<script setup lang="ts">
import { computed, h, ref, provide } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NIcon, NDropdown } from 'naive-ui';
import { PersonOutline, LogOutOutline } from '@vicons/ionicons5';
import { useAuthStore } from '@/stores/auth';
import { logout as apiLogout } from '@/api/auth';
import type { Component } from 'vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const isCollapsed = ref(false);
provide('sidebarCollapsed', isCollapsed);

function toggleCollapse() {
  isCollapsed.value = !isCollapsed.value;
}

const navItems = [
  { key: 'chat', label: 'AI 对话', path: '/ai/chat' },
  { key: 'libraries', label: '我的知识库', path: '/ai/libraries' },
  { key: 'notes', label: '笔记', path: '/ai/notes' },
];

const activeKey = computed(() => {
  if (route.path.startsWith('/ai/libraries')) return 'libraries';
  if (route.path.startsWith('/ai/notes')) return 'notes';
  return 'chat';
});

function navigate(path: string) {
  if (path === '#') return;
  router.push(path);
}

const userDropdownOptions = [
  { key: 'profile', label: '个人中心', icon: () => h(NIcon, null, { default: () => h(PersonOutline) }) },
  { key: 'logout', label: '退出登录', icon: () => h(NIcon, null, { default: () => h(LogOutOutline) }) },
];

function handleDropdownSelect(key: string) {
  if (key === 'profile') router.push('/profile');
  else if (key === 'logout') {
    apiLogout();
    authStore.logout();
    router.push('/login');
  }
}
</script>

<template>
  <aside class="sidebar" :class="{ collapsed: isCollapsed }">
    <!-- Brand -->
    <div class="sidebar-brand" @click="router.push('/resources')">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="rgb(52,208,188)" stroke-width="2.5"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
      <span v-show="!isCollapsed">小青知识库</span>
    </div>

    <div v-show="!isCollapsed" class="sidebar-divider" />

    <!-- Nav -->
    <nav class="sidebar-nav">
      <button
        v-for="item in navItems"
        :key="item.key"
        :class="['sidebar-link', { active: activeKey === item.key }]"
        @click="navigate(item.path)"
      >
        <span class="sidebar-link-icon" :class="item.key">
          <svg v-if="item.key === 'chat'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>
          <svg v-else-if="item.key === 'libraries'" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
          <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        </span>
        <span v-show="!isCollapsed" class="sidebar-link-label">{{ item.label }}</span>
      </button>
    </nav>

    <div v-show="!isCollapsed" class="sidebar-spacer" />

    <!-- Bottom: user + back -->
    <div class="sidebar-footer">
      <router-link to="/resources" class="sidebar-link sidebar-back-link">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
        <span v-show="!isCollapsed">返回资源</span>
      </router-link>

      <n-dropdown
        v-if="authStore.user"
        :options="userDropdownOptions"
        @select="handleDropdownSelect"
        placement="top-start"
      >
        <div class="sidebar-user">
          <img
            :src="authStore.user.avatarUrl || 'https://api.dicebear.com/7.x/initials/svg?seed=' + authStore.user.nickname"
            alt=""
            class="sidebar-user-avatar"
          />
          <span v-show="!isCollapsed" class="sidebar-user-name">{{ authStore.user.nickname }}</span>
        </div>
      </n-dropdown>
    </div>
    <!-- Collapse toggle -->
    <button class="sidebar-collapse-btn" @click="toggleCollapse" :title="isCollapsed ? '展开侧栏' : '收起侧栏'">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
        <polyline v-if="isCollapsed" points="9 18 15 12 9 6" />
        <polyline v-else points="15 18 9 12 15 6" />
      </svg>
    </button>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 260px;
  flex-shrink: 0;
  height: 100vh;
  position: sticky;
  top: 0;
  /* backdrop-filter 会创建独立层叠上下文，需抬升 z-index 使突出在右缘的收起按钮
     不被相邻的笔记列表面板（后出现的兄弟节点）覆盖，否则按钮大部分点不到 */
  z-index: 20;
  display: flex;
  flex-direction: column;
  padding: 20px 16px;
  border-right: 1px solid rgba(0,0,0,0.04);
  background: rgba(255,255,255,0.55);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  transition: width 0.25s;
  overflow: visible;
}
.sidebar.collapsed {
  width: 52px;
  padding: 20px 8px;
}
.sidebar.collapsed .sidebar-brand {
  justify-content: center;
  padding: 4px 0;
}
.sidebar.collapsed .sidebar-link {
  justify-content: center;
  padding: 10px 0;
}
.sidebar.collapsed .sidebar-user {
  justify-content: center;
  padding: 8px 0;
}
.sidebar.collapsed .sidebar-back-link {
  justify-content: center;
}

.sidebar-collapse-btn {
  position: absolute;
  top: 50%;
  right: -24px;
  transform: translateY(-50%);
  width: 24px;
  height: 48px;
  border: 0.5px solid rgba(0,0,0,0.08);
  border-radius: 0 8px 8px 0;
  background: rgba(255,255,255,0.94);
  color: rgba(0,0,0,0.3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 12;
  transition: color 0.15s, background 0.15s;
}
.sidebar-collapse-btn:hover {
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.4);
  background: #fff;
}
.sidebar-collapse-btn:hover {
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.3);
  background: #fff;
}

/* Brand */
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 8px;
  cursor: pointer;
  color: rgba(0,0,0,0.75);
  font-size: 14px;
  font-weight: 600;
  border-radius: 10px;
  transition: background 0.15s;
}
.sidebar-brand:hover { background: rgba(0,0,0,0.03); }
.sidebar-divider {
  height: 1px;
  background: rgba(0,0,0,0.05);
  margin: 12px 4px 8px;
}

/* Nav */
.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sidebar-spacer { flex: 1; }

.sidebar-link {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  border: none;
  background: transparent;
  color: rgba(0,0,0,0.5);
  font-size: 14px;
  font-weight: 400;
  cursor: pointer;
  transition: all 0.15s ease;
  width: 100%;
  text-align: left;
  font-family: inherit;
  text-decoration: none;
}
.sidebar-link:hover {
  background: rgba(52,208,188,0.06);
  color: rgb(52,208,188);
}
.sidebar-link.active {
  background: rgba(52,208,188,0.1);
  color: rgb(52,208,188);
  font-weight: 600;
}
.sidebar-link.active .sidebar-link-icon svg { stroke-width: 2.5; }
.sidebar-link-label { white-space: nowrap; }

/* Footer */
.sidebar-footer {
  padding-top: 12px;
  border-top: 1px solid rgba(0,0,0,0.04);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sidebar-back-link {
  font-size: 13px;
  color: rgba(0,0,0,0.4);
}
.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}
.sidebar-user:hover { background: rgba(0,0,0,0.03); }
.sidebar-user-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  object-fit: cover;
}
.sidebar-user-name {
  font-size: 13px;
  color: rgba(0,0,0,0.55);
  font-weight: 500;
}

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .sidebar {
  border-right-color: rgba(255,255,255,0.04);
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 96%, transparent);
}
html[data-theme='dark'] .sidebar-collapse-btn {
  border-color: rgba(255,255,255,0.2);
  background: var(--cf-bg-card, #0c0c0d);
  color: rgba(248,250,252,0.7);
}
html[data-theme='dark'] .sidebar-collapse-btn:hover {
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .sidebar-brand {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .sidebar-brand:hover {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
}
html[data-theme='dark'] .sidebar-divider {
  background: rgba(255,255,255,0.05);
}
html[data-theme='dark'] .sidebar-link {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .sidebar-footer {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .sidebar-back-link {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .sidebar-user:hover {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
}
html[data-theme='dark'] .sidebar-user-name {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
</style>
