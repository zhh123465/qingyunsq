import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import { i18n } from './locales';

/**
 * 仅处理最需要性能的首屏跳转：已登录非游客用户访问首页 `/` → `/resources`。
 * 这比走 Vue Router 的 beforeEach 守卫更快——绕过了加载整个 App + 路由 + 首页组件的开销。
 *
 * 其他所有重定向（legacy 路径如 /square、/spaces、/checkin 以及路由守卫）统一由
 * router/index.ts 中的 redirect 配置和 beforeEach 处理，保持单一职责，避免两套逻辑不一致。
 */
function getBootRedirectTarget(): string {
  const pathname = window.location.pathname.replace(/\/+$/, '') || '/';

  if (pathname !== '/') return '';

  const token = localStorage.getItem('token');
  const role = localStorage.getItem('role');
  if (token && role !== 'GUEST') {
    return '/resources';
  }

  return '';
}

const bootRedirectTarget = getBootRedirectTarget();

if (bootRedirectTarget) {
  window.location.replace(bootRedirectTarget);
} else {
  const app = createApp(App);

  app.use(createPinia());
  app.use(router);
  app.use(i18n);

  app.mount('#app');
}
