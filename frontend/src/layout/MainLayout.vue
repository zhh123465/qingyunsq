<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { getMe, logout as apiLogout } from '@/api/auth';
import { useWebSocket } from '@/composables/useWebSocket';
import ThemeToggle from '@/components/ThemeToggle.vue';
import AnnouncementBanner from '@/components/AnnouncementBanner.vue';
import {
  ArrowBackOutline,
  ChatbubblesOutline,
  ChevronDownOutline,
  DocumentTextOutline,
  LogOutOutline,
  PersonOutline,
  SearchOutline,
  SparklesOutline,
  CheckmarkCircleOutline,
  LibraryOutline,
} from '@vicons/ionicons5';
import { NDropdown, NIcon, useMessage } from 'naive-ui';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const message = useMessage();
const searchKeyword = ref('');
const headerScrolled = ref(false);

// 鐩戝惉 WebSocket 浜嬩欢锛屽疄鏃舵洿鏂版湭璇绘暟
useWebSocket((event) => {
  if (
    event.type === 'COMMENT' ||
    event.type === 'LIKE' ||
    event.type === 'REPLY' ||
    event.type === 'MENTION' ||
    event.type === 'ACCEPT' ||
    event.type === 'JOIN' ||
    event.type === 'TAG_SUBSCRIBE' ||
    event.type === 'RESOURCE_REVIEW' ||
    event.type === 'SYSTEM'
  ) {
    showDesktopNotification(event.title || '新通知', event.content || '');
  } else if (event.type === 'MESSAGE') {
    showDesktopNotification('新私信', event.content || '你收到了一条新消息');
  }
});

/** 璇锋眰娴忚鍣ㄩ€氱煡鏉冮檺骞舵樉绀烘闈㈠脊绐?*/
function showDesktopNotification(title: string, body: string) {
  if (!('Notification' in window)) return;
  if (Notification.permission === 'granted') {
    new Notification(title, { body, icon: '/favicon.ico' });
  } else if (Notification.permission !== 'denied') {
    Notification.requestPermission().then((perm) => {
      if (perm === 'granted') {
        new Notification(title, { body, icon: '/favicon.ico' });
      }
    });
  }
}

const navLinks = [
  { name: '资源', path: '/resources', icon: DocumentTextOutline, back: false },
  { name: '打卡', path: '/checkin', icon: CheckmarkCircleOutline, back: false },
  { name: '学习', path: '/learning', icon: LibraryOutline, back: false },
  { name: 'AI 知识库', path: '/ai/chat', icon: SparklesOutline, back: false },
];

const isAiPage = computed(() => route.path.startsWith('/ai'));
const aiNavLinks = [
  { name: '返回资源', path: '/resources', icon: ArrowBackOutline, back: true },
  { name: 'AI 对话', path: '/ai/chat', icon: ChatbubblesOutline, back: false },
  { name: '知识库', path: '/ai/libraries', icon: DocumentTextOutline, back: false },
  { name: '笔记', path: '/ai/notes', icon: LibraryOutline, back: false },
];
const displayedNavLinks = computed(() => (isAiPage.value ? aiNavLinks : navLinks));
const searchPlaceholder = computed(() =>
  isAiPage.value ? '搜索知识库、文档或内容' : '搜索资源、工具、软件或学习笔记',
);
const brandSubtitle = computed(() => (isAiPage.value ? 'AI 知识库' : '个人知识库'));

const userDropdownOptions = [
  {
    label: '个人中心',
    key: 'profile',
    icon: () => h(NIcon, null, { default: () => h(PersonOutline) }),
  },
  {
    label: '退出登录',
    key: 'logout',
    icon: () => h(NIcon, null, { default: () => h(LogOutOutline) }),
  },
];

onMounted(async () => {
  updateTopHeaderState();
  window.addEventListener('scroll', updateTopHeaderState, { passive: true });

  if (authStore.isLoggedIn && !authStore.user && localStorage.getItem('token') !== 'GUEST_TOKEN') {
    try {
      const user = await getMe();
      authStore.setUser(user);
    } catch {
      authStore.logout();
      router.push('/login');
    }
  }

  // 请求桌面通知权限
  if (authStore.isLoggedIn && authStore.user?.role !== 'GUEST') {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission();
    }
  }
});

onUnmounted(() => {
  window.removeEventListener('scroll', updateTopHeaderState);
});

async function handleDropdownSelect(key: string | number) {
  if (key === 'profile') {
    router.push('/profile');
    return;
  }

  if (key === 'logout') {
    try {
      await apiLogout();
    } finally {
      authStore.logout();
      router.push('/login');
    }
  }
}

function handleSearch() {
  const query = searchKeyword.value.trim();
  if (!query) return;
  if (isAiPage.value) {
    router.push({
      path: '/ai',
      query: { ...route.query, q: query, focus: String(Date.now()) },
    });
    return;
  }
  const postId = extractPostIdFromSearchInput(query);
  if (postId) {
    router.push(`/posts/${postId}`);
    return;
  }
  router.push({ path: '/search', query: { q: query } });
}

function extractPostIdFromSearchInput(value: string) {
  // 鍏佽鐢ㄦ埛绮樿创绔欏唴甯栧瓙鍒嗕韩閾炬帴锛屼紭鍏堢簿纭烦杞埌甯栧瓙锛岄伩鍏嶆妸 URL 褰撴櫘閫氬叧閿瘝鎼滅储銆?
  const directMatch = value.match(/(?:^|\s)(?:https?:\/\/[^\s/]+)?\/?posts\/(\d+)(?=$|[/?#\s])/i);
  if (directMatch) return Number(directMatch[1]);

  try {
    const url = new URL(value, window.location.origin);
    const pathMatch = url.pathname.match(/^\/posts\/(\d+)\/?$/i);
    if (pathMatch) return Number(pathMatch[1]);

    const postId = url.searchParams.get('postId');
    if (postId && /^\d+$/.test(postId)) return Number(postId);
  } catch {
    return null;
  }

  return null;
}

function navigate(path: string) {
  const isGuest = authStore.user?.role === 'GUEST';
  const allowedGuestPaths = ['/', '/resources', '/checkin'];
  const isAllowed =
    allowedGuestPaths.includes(path) ||
    (path.startsWith('/posts/') && path !== '/posts/new') ||
    path.startsWith('/resources/');

  if (isGuest && !isAllowed) {
    message.warning('该功能需要登录后使用，请先登录');
    router.push('/login');
    return;
  }
  router.push(path);
}

function isTopNavActive(link: { path: string; active?: boolean; back?: boolean; section?: string }) {
  if (link.back) return false;
  return route.path.startsWith(link.path);
}

function updateTopHeaderState() {
  headerScrolled.value = window.scrollY > 8;
}

</script>

<template>
  <div class="main-layout">
    <div v-if="isAiPage" class="ai-fullscreen-wrapper">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>

    <nav v-else class="apple-topbar" :class="{ 'is-scrolled': headerScrolled }">
      <div class="apple-topbar-inner">
        <button class="brand-lockup" @click="navigate('/resources')">
          <span class="brand-copy">
            <strong>小青知识库</strong>
            <small>{{ brandSubtitle }}</small>
          </span>
        </button>

        <div class="apple-nav-links">
          <button
            v-for="link in displayedNavLinks"
            :key="link.path"
            :class="{ active: isTopNavActive(link), 'return-link': link.back }"
            @click="navigate(link.path)"
          >
            <n-icon size="17">
              <component :is="link.icon" />
            </n-icon>
            <span>{{ link.name }}</span>
          </button>
        </div>

        <div class="apple-actions">
          <label class="apple-search">
            <n-icon size="17"><SearchOutline /></n-icon>
            <input
              v-model="searchKeyword"
              type="text"
              :placeholder="searchPlaceholder"
              @keyup.enter="handleSearch"
            />
          </label>
          <ThemeToggle class="apple-theme" />
          <n-dropdown
            v-if="authStore.user"
            :options="userDropdownOptions"
            @select="handleDropdownSelect"
          >
            <div class="apple-user">
              <img
                :src="
                  authStore.user.avatarUrl ||
                  'https://api.dicebear.com/7.x/initials/svg?seed=' + authStore.user.nickname
                "
                alt="Avatar"
              />
              <span>{{ authStore.user.nickname }}</span>
              <n-icon size="15">
                <ChevronDownOutline />
              </n-icon>
            </div>
          </n-dropdown>
        </div>
      </div>
    </nav>

    <AnnouncementBanner v-if="!isAiPage" />

    <div v-if="!isAiPage" class="content-wrapper pt-16">
      <main class="page-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>


    <!-- ICP 备案号 -->
    <footer class="icp-footer">
      <p class="copyright">© 2026 qingyunsq.top All Rights Reserved.</p>
      <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">
        桂ICP备2026013355号
      </a>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.main-layout {
  min-height: 100vh;
  background: transparent;
  display: flex;
  flex-direction: column;
}

.ai-fullscreen-wrapper {
  min-height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: transform 0.24s var(--cf-motion-ease);
}

.brand:hover {
  transform: translate3d(2px, -1px, 0);
}

.brand-icon {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--cf-primary);
  color: var(--cf-text-inverse);
  box-shadow:
    0 16px 34px color-mix(in srgb, var(--cf-primary) 28%, transparent),
    0 1px 0 rgba(255, 255, 255, 0.54) inset;
}

.brand-title {
  font-family: var(--cf-font-heading);
  font-size: 18px;
  line-height: 1.1;
  font-weight: 700;
  white-space: nowrap;
}

.brand-subtitle {
  color: var(--cf-text-muted);
  font-size: 12px;
  line-height: 1.2;
  white-space: nowrap;
}

.header-left {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 18px;
}

.top-nav {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.top-nav::-webkit-scrollbar {
  display: none;
}

.top-nav-item {
  height: 42px;
  min-width: 42px;
  border: 1px solid transparent;
  background: transparent;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 12px;
  border-radius: var(--cf-radius-pill);
  color: var(--cf-text-secondary);
  cursor: pointer;
  transition:
    transform 0.24s var(--cf-motion-ease),
    box-shadow 0.24s var(--cf-motion-ease),
    color 0.22s ease,
    background 0.22s ease,
    border-color 0.22s ease;
  font-weight: 600;
  white-space: nowrap;
}

.top-nav-item span {
  font-size: 14px;
}

.top-nav-item:hover {
  background: var(--cf-bg-glass-soft);
  border-color: var(--cf-border-glass);
  box-shadow: 0 14px 34px color-mix(in srgb, var(--cf-text-primary) 8%, transparent);
  color: var(--cf-text-primary);
  transform: translate3d(0, -1px, 0);
}

.top-nav-item.active {
  background:
    linear-gradient(
      135deg,
      color-mix(in srgb, var(--cf-primary) 20%, transparent),
      color-mix(in srgb, var(--cf-secondary) 10%, transparent)
    ),
    var(--cf-bg-glass-soft);
  border-color: color-mix(in srgb, var(--cf-primary) 32%, var(--cf-border-glass));
  box-shadow: 0 18px 46px color-mix(in srgb, var(--cf-primary) 18%, transparent);
  color: var(--cf-primary);
}

.content-wrapper {
  width: 100%;
  min-height: calc(100vh - var(--cf-header-height));
  display: flex;
  flex-direction: column;
}

.top-header {
  position: sticky;
  top: 0;
  z-index: 20;
  height: var(--cf-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 0 24px;
  background:
    linear-gradient(180deg, var(--cf-surface-highlight), transparent 74%),
    linear-gradient(90deg, var(--cf-bg-glass-strong), var(--cf-bg-glass-soft));
  backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  -webkit-backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(136%);
  border-bottom: 1px solid var(--cf-border-glass);
  box-shadow:
    0 20px 70px color-mix(in srgb, var(--cf-text-primary) 9%, transparent),
    0 -1px 0 color-mix(in srgb, #ffffff 36%, transparent) inset;
  transition:
    background 0.26s ease,
    backdrop-filter 0.26s ease,
    border-color 0.26s ease,
    box-shadow 0.26s ease;
}

.top-header.is-scrolled {
  background:
    linear-gradient(
      180deg,
      color-mix(in srgb, var(--cf-surface-highlight) 72%, transparent),
      transparent 82%
    ),
    color-mix(in srgb, var(--cf-bg-base) 88%, transparent);
  backdrop-filter: blur(18px) saturate(160%);
  -webkit-backdrop-filter: blur(18px) saturate(160%);
  border-bottom-color: color-mix(in srgb, var(--cf-border-strong) 42%, var(--cf-border-glass));
  box-shadow:
    0 18px 54px color-mix(in srgb, var(--cf-text-primary) 18%, transparent),
    0 -1px 0 color-mix(in srgb, #ffffff 28%, transparent) inset;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.header-theme-toggle {
  flex-shrink: 0;
}

.search-input {
  width: 360px;
}

.search-cluster {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-icon-btn {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--cf-bg-glass);
  border: 1px solid var(--cf-border-glass);
  box-shadow: 0 12px 30px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--cf-text-secondary);
  transition:
    transform 0.24s var(--cf-motion-ease),
    box-shadow 0.24s var(--cf-motion-ease),
    color 0.22s ease,
    background 0.22s ease,
    border-color 0.22s ease;
}

.header-icon-btn,
.publish-top-btn {
  border: none;
  cursor: pointer;
}

.publish-top-btn {
  height: 40px;
  padding: 0 14px;
  border-radius: var(--cf-radius-pill);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--cf-text-inverse);
  background: var(--cf-primary);
  box-shadow: var(--cf-shadow-glow);
  font-weight: 700;
  transition:
    transform 0.24s var(--cf-motion-ease),
    box-shadow 0.24s var(--cf-motion-ease),
    background 0.22s ease;
}

.publish-top-btn:hover {
  background: var(--cf-primary-hover);
  transform: translate3d(0, -2px, 0);
  box-shadow: 0 18px 54px color-mix(in srgb, var(--cf-primary) 24%, transparent);
}

.header-icon-btn:hover,
.top-nav-item:hover {
  background: var(--cf-bg-readable);
  border-color: var(--cf-border-strong);
  color: var(--cf-primary);
  transform: translate3d(0, -2px, 0);
  box-shadow: var(--cf-shadow-soft);
}

.user-profile-trigger {
  height: 40px;
  padding: 0 12px 0 8px;
  border-radius: 999px;
  background: var(--cf-bg-glass);
  border: 1px solid var(--cf-border-glass);
  box-shadow: 0 12px 30px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--cf-text-primary);
  font-size: 14px;
  font-weight: 600;
  transition:
    transform 0.24s var(--cf-motion-ease),
    box-shadow 0.24s var(--cf-motion-ease),
    border-color 0.22s ease;
}

.user-profile-trigger:hover {
  transform: translate3d(0, -2px, 0);
  border-color: var(--cf-border-strong);
  box-shadow: var(--cf-shadow-soft);
}

.page-content {
  flex: 1;
  padding: 24px;
  overflow-x: hidden;
}

.apple-topbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 50;
  padding: 0 24px;
  pointer-events: none;
  background: color-mix(in srgb, var(--cf-bg-card) 96%, transparent);
  border-bottom: 0.5px solid color-mix(in srgb, var(--cf-border) 40%, transparent);
  box-shadow: 0 1px 8px color-mix(in srgb, var(--cf-text-primary) 4%, transparent);
  backdrop-filter: blur(10px) saturate(150%);
  -webkit-backdrop-filter: blur(10px) saturate(150%);
}

.apple-topbar-inner {
  width: 100%;
  max-width: none;
  min-height: 56px;
  margin: 0 auto;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  display: flex;
  align-items: center;
  gap: 18px;
  pointer-events: auto;
}

.brand-lockup,
.apple-nav-links button,
.apple-icon-btn {
  border: 0;
  background: transparent;
  cursor: pointer;
}

.brand-lockup {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 180px;
  padding: 0;
  color: var(--cf-text-primary);
}

.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: white;
  background: linear-gradient(
    145deg,
    var(--cf-primary),
    color-mix(in srgb, var(--cf-primary) 72%, #38bdf8)
  );
  box-shadow: 0 16px 34px color-mix(in srgb, var(--cf-primary) 28%, transparent);
}

.brand-mark.ai {
  border-radius: 14px;
  background:
    radial-gradient(circle at 32% 30%, #ffffff 0 8%, transparent 9%),
    linear-gradient(145deg, #00d8bf, #0ea5a1);
}

.brand-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
}

.brand-copy strong {
  font-size: 18px;
  line-height: 1;
  letter-spacing: 0;
}

.brand-copy small {
  color: var(--cf-text-muted);
  font-size: 11px;
  line-height: 1;
}

.apple-nav-links {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(24px, 3vw, 46px);
  padding: 0 12px;
}

.apple-nav-links button {
  height: 56px;
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--cf-text-secondary);
  font-size: 14px;
  font-weight: 650;
  white-space: nowrap;
  transition:
    color 0.2s ease,
    transform 0.2s ease;
}

.apple-nav-links button::after {
  content: '';
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: 0;
  height: 2.5px;
  border-radius: 999px;
  background: var(--cf-primary);
  transform: scaleX(0);
  transition: transform 0.2s ease;
}

.apple-nav-links button:hover,
.apple-nav-links button.active {
  color: var(--cf-primary);
}

.apple-nav-links button.active::after {
  transform: scaleX(1);
}

.apple-nav-links :deep(.n-icon) {
  display: none;
}

.apple-nav-links button.return-link {
  gap: 6px;
}

.apple-nav-links button.return-link :deep(.n-icon) {
  display: inline-flex;
}

.apple-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  min-width: 0;
}

.apple-search {
  width: 220px;
  height: 40px;
  padding: 0 16px;
  border: 1px solid var(--cf-border);
  border-radius: 999px;
  background: color-mix(in srgb, var(--cf-bg-card) 76%, transparent);
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, #ffffff 74%, transparent),
    0 12px 28px color-mix(in srgb, var(--cf-text-primary) 6%, transparent);
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--cf-text-muted);
}

.apple-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--cf-text-primary);
  font-size: 14px;
}

.apple-publish {
  height: 40px;
  padding: 0 17px;
  border-radius: 999px;
}

.apple-icon-btn {
  position: relative;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  color: var(--cf-text-secondary);
  display: grid;
  place-items: center;
  transition:
    color 0.2s ease,
    background 0.2s ease;
}

.apple-icon-btn:hover {
  color: var(--cf-primary);
  background: var(--cf-primary-soft);
}

.apple-badge {
  position: absolute;
  top: -3px;
  right: -4px;
}

.apple-theme {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
}

.apple-user {
  height: 42px;
  padding: 0 10px 0 4px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--cf-text-primary);
  font-size: 14px;
  font-weight: 650;
  cursor: pointer;
}

.apple-user img {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid var(--cf-border);
}


.mobile-mask {
  display: none;
}

.fade-enter-active,
.fade-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

:deep(.n-input .n-input__input-el),
:deep(.n-input .n-input__placeholder) {
  font-size: 14px;
}

:deep(.search-input.n-input) {
  --n-color: var(--cf-bg-glass) !important;
  --n-color-focus: var(--cf-bg-readable) !important;
  --n-border: 1px solid var(--cf-border-glass) !important;
  --n-border-hover: 1px solid var(--cf-border-strong) !important;
  --n-border-focus: 1px solid var(--cf-border-strong) !important;
  --n-box-shadow-focus: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 12%, transparent) !important;
  --n-text-color: var(--cf-text-primary) !important;
  --n-placeholder-color: var(--cf-text-muted) !important;
  --n-icon-color: var(--cf-text-muted) !important;
  box-shadow: 0 14px 34px color-mix(in srgb, var(--cf-text-primary) 7%, transparent);
  backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(130%);
  -webkit-backdrop-filter: blur(var(--cf-backdrop-blur)) saturate(130%);
}

@media (max-width: 1100px) {
  .search-input {
    width: 280px;
  }

  .brand-copy {
    display: none;
  }
}

@media (max-width: 960px) {
  .top-header {
    height: auto;
    min-height: var(--cf-header-height);
    align-items: stretch;
    flex-direction: column;
    padding: 12px 16px;
    gap: 12px;
  }

  .header-left,
  .header-right {
    width: 100%;
  }

  .search-cluster {
    flex: 1;
  }

  .page-content {
    padding: 16px;
  }
}

@media (max-width: 720px) {
  .header-right {
    gap: 8px;
  }

  .user-profile-trigger span {
    display: none;
  }

  .top-nav-item span {
    display: none;
  }

  .search-input {
    width: 100%;
    min-width: 0;
    flex: 1;
  }

  .publish-top-btn span {
    display: none;
  }
}

/* ICP 备案号 */
.icp-footer {
  text-align: center;
  padding: 20px 0;
  border-top: 1px solid var(--cf-border);
  background: var(--cf-bg-soft);

  .copyright {
    margin: 0 0 6px;
    font-size: 13px;
    color: var(--cf-text-muted);
  }

  a {
    font-size: 13px;
    color: var(--cf-text-muted);
    text-decoration: none;
    transition: color 0.2s ease;

    &:hover {
      color: var(--cf-text-secondary);
    }
  }
}
</style>
