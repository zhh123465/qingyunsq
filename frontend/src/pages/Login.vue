<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { ArrowForwardOutline, EyeOffOutline, EyeOutline, LogoWechat, LogoGithub } from '@vicons/ionicons5';
import {
  checkEmailExists,
  getGithubAuthorizeUrl,
  login,
  loginWithEmailCode,
  loginWithGithubCode,
  loginWithWechatCode,
  sendEmailCode,
} from '@/api/auth';
import { useAuthStore } from '@/stores/auth';
import { validateEmail, validatePassword } from '@/utils/authValidation';

const router = useRouter();
const message = useMessage();
const authStore = useAuthStore();

const email = ref('');
const password = ref('');
const showPassword = ref(false);
const emailCode = ref('');
const loginMode = ref<'password' | 'code'>('password');
const loading = ref(false);
const codeLoading = ref(false);
const wechatLoading = ref(false);
const githubLoading = ref(false);
const codeCountdown = ref(0);
let codeTimer: number | undefined;
const fieldState = ref({
  email: { active: false, touched: false, error: '', shaking: false },
  password: { active: false, touched: false, error: '', shaking: false },
});

const rememberMe = ref(false);

onMounted(() => {
  const savedEmail = localStorage.getItem('rememberedEmail');
  const savedPassword = localStorage.getItem('rememberedPassword');
  if (savedEmail) {
    email.value = savedEmail;
    rememberMe.value = true;
  }
  if (savedPassword) {
    password.value = savedPassword;
  }
  const params = new URLSearchParams(window.location.search);
  const wechatCode = params.get('wechat_code') || params.get('code');
  if (wechatCode && params.get('source') === 'wechat-mini-program') {
    void completeWechatLogin(wechatCode);
    return;
  }
  // GitHub OAuth 回调：GitHub 授权后 302 回 /login?code=..&state=..
  const githubCode = params.get('code');
  const githubState = params.get('state');
  if (githubCode && githubState) {
    void completeGithubLogin(githubCode, githubState);
  }
});

const canSubmit = computed(() => {
  if (!email.value.trim() || fieldState.value.email.error) return false;
  if (loginMode.value === 'password') {
    return password.value.trim() && !fieldState.value.password.error;
  }
  return emailCode.value.trim();
});

function runFieldValidation(field: 'email' | 'password') {
  fieldState.value[field].error =
    field === 'email' ? validateEmail(email.value) : validatePassword(password.value);
}

function focusField(field: 'email' | 'password') {
  fieldState.value[field].active = true;
  runFieldValidation(field);
}

function blurField(field: 'email' | 'password') {
  const state = fieldState.value[field];
  state.active = false;
  state.touched = true;
  runFieldValidation(field);
  if (state.error) {
    state.shaking = false;
    window.setTimeout(() => {
      state.shaking = true;
      window.setTimeout(() => {
        state.shaking = false;
      }, 520);
    }, 0);
  }
}

function validateForm() {
  const fields =
    loginMode.value === 'password' ? (['email', 'password'] as const) : (['email'] as const);
  fields.forEach((field) => {
    fieldState.value[field].touched = true;
    runFieldValidation(field);
  });
  return fields.every((field) => !fieldState.value[field].error);
}

function resetLoginForm() {
  email.value = '';
  password.value = '';
  emailCode.value = '';
  fieldState.value = {
    email: { active: false, touched: false, error: '', shaking: false },
    password: { active: false, touched: false, error: '', shaking: false },
  };
}

function startCodeCountdown() {
  codeCountdown.value = 60;
  window.clearInterval(codeTimer);
  codeTimer = window.setInterval(() => {
    codeCountdown.value -= 1;
    if (codeCountdown.value <= 0) {
      window.clearInterval(codeTimer);
      codeTimer = undefined;
    }
  }, 1000);
}

async function handleSendCode() {
  fieldState.value.email.touched = true;
  runFieldValidation('email');
  if (fieldState.value.email.error) {
    message.warning('请先填写有效的邮箱');
    return;
  }
  const trimmedEmail = email.value.trim();
  codeLoading.value = true;
  try {
    const exists = await checkEmailExists(trimmedEmail);
    if (!exists) {
      message.warning('该邮箱尚未注册，将跳转到注册页');
      router.push({ path: '/register', query: { email: trimmedEmail } });
      return;
    }
    await sendEmailCode(trimmedEmail, 'LOGIN');
    message.success('验证码已发送，请查收邮箱');
    startCodeCountdown();
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '验证码发送失败');
  } finally {
    codeLoading.value = false;
  }
}

async function handleLogin() {
  if (!validateForm()) {
    message.warning(
      loginMode.value === 'password' ? '请按提示修正邮箱和密码' : '请填写邮箱和验证码',
    );
    return;
  }

  loading.value = true;
  try {
    const res =
      loginMode.value === 'password'
        ? await login({ email: email.value.trim(), password: password.value })
        : await loginWithEmailCode({
            email: email.value.trim(),
            emailCode: emailCode.value.trim(),
          });
    authStore.setToken(res.token);
    authStore.setUser(res.user);
    if (res.tenantId && res.tenantCode) {
      authStore.setTenant(res.tenantId, res.tenantCode);
    }
    if (rememberMe.value) {
      localStorage.setItem('rememberedEmail', email.value.trim());
      if (loginMode.value === 'password') {
        localStorage.setItem('rememberedPassword', password.value);
      }
    } else {
      localStorage.removeItem('rememberedEmail');
      localStorage.removeItem('rememberedPassword');
    }
    message.success('登录成功');
    router.push('/resources');
  } catch {
    message.error(loginMode.value === 'password' ? '邮箱或密码错误' : '邮箱或验证码错误');
    resetLoginForm();
  } finally {
    loading.value = false;
  }
}

function handleSocialLogin(provider: string) {
  if (provider === '微信') {
    void handleWechatLogin();
    return;
  }
  if (provider === 'GitHub') {
    void handleGithubLogin();
    return;
  }
  message.info(`${provider} 登录暂未开放，请先使用邮箱登录或验证码登录`);
}

async function handleGithubLogin() {
  if (githubLoading.value) return;
  githubLoading.value = true;
  try {
    const res = await getGithubAuthorizeUrl();
    if (!res.enabled || !res.url) {
      message.info('GitHub 登录暂未开放，请先使用邮箱登录或验证码登录');
      return;
    }
    // 跳转 GitHub 授权页，授权后 GitHub 会带 code/state 回到 /login
    window.location.href = res.url;
  } catch {
    message.error('GitHub 登录发起失败，请稍后重试');
  } finally {
    githubLoading.value = false;
  }
}

async function completeGithubLogin(code: string, state: string) {
  githubLoading.value = true;
  // 清掉地址栏里的一次性 code/state，防止刷新页面重复消费已失效的授权码
  window.history.replaceState({}, '', window.location.pathname);
  try {
    const res = await loginWithGithubCode(code, state);
    authStore.setToken(res.token);
    authStore.setUser(res.user);
    if (res.tenantId && res.tenantCode) {
      authStore.setTenant(res.tenantId, res.tenantCode);
    }
    message.success('GitHub 登录成功');
    router.push('/resources');
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : 'GitHub 登录失败，请重新尝试');
  } finally {
    githubLoading.value = false;
  }
}

async function completeWechatLogin(code: string) {
  wechatLoading.value = true;
  try {
    const res = await loginWithWechatCode(code);
    authStore.setToken(res.token);
    authStore.setUser(res.user);
    if (res.tenantId && res.tenantCode) {
      authStore.setTenant(res.tenantId, res.tenantCode);
    }
    message.success('微信登录成功');
    router.push('/resources');
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '微信登录失败，请重新尝试');
  } finally {
    wechatLoading.value = false;
  }
}

async function handleWechatLogin() {
  const params = new URLSearchParams(window.location.search);
  const wechatCode = params.get('wechat_code') || params.get('code');
  if (wechatCode && params.get('source') === 'wechat-mini-program') {
    await completeWechatLogin(wechatCode);
    return;
  }

  const wxMiniProgram = window.wx?.miniProgram;
  if (!wxMiniProgram) {
    message.warning('网页端无法直接获取小程序登录凭证，请在微信小程序内使用微信登录');
    return;
  }

  wxMiniProgram.getEnv((env) => {
    if (!env.miniprogram) {
      message.warning('请在微信小程序内使用微信登录');
      return;
    }
    wxMiniProgram.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent('/login?source=wechat-mini-program')}`,
      fail: () => message.warning('小程序登录页未配置，请先在小程序端接入 wx.login'),
    });
  });
}

function handleGuestLogin() {
  authStore.setToken('GUEST_TOKEN');
  authStore.setUser({
    id: -1,
    nickname: '游客用户',
    avatarUrl: 'https://api.dicebear.com/7.x/initials/svg?seed=Guest',
    email: 'guest@campus.edu',
    role: 'GUEST',
  });
  message.success('已以游客身份进入知识库');
  router.push('/resources');
}

onBeforeUnmount(() => {
  window.clearInterval(codeTimer);
});
</script>

<template>
  <div class="bg-background text-on-surface min-h-screen flex flex-col relative overflow-hidden">
    <!-- Background Abstract Pattern -->
    <div class="absolute inset-0 pointer-events-none opacity-20">
      <div
        class="absolute -top-[20%] -left-[10%] w-[50%] h-[50%] rounded-full bg-gradient-to-br from-blue-100 to-purple-100 blur-[100px]"
      />
      <div
        class="absolute bottom-[10%] -right-[10%] w-[60%] h-[60%] rounded-full bg-gradient-to-tl from-indigo-50 to-cyan-50 blur-[120px]"
      />
    </div>

    <!-- TopNavBar -->
    <nav class="bg-transparent docked full-width top-0 z-50">
      <div
        class="flex justify-between items-center px-margin-mobile md:px-margin-desktop py-stack-md w-full mx-auto"
      >
        <div class="flex items-center gap-3">
          <div
            class="font-headline-md text-[24px] font-extrabold tracking-tight h-[32px] overflow-hidden"
          >
            <div class="flex flex-col rolling-text">
              <div class="h-[32px] flex items-center">
                <span class="text-gray-400/70 dark:text-gray-500/70">小青知识库</span>
              </div>
              <div class="h-[32px] flex items-center">
                <span class="text-gray-400/70 dark:text-gray-500/70">小青知识库</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </nav>

    <!-- Main Content -->
    <main
      class="flex-grow flex items-center justify-center p-margin-mobile md:p-margin-desktop relative z-10"
    >
      <div
        class="glass-panel w-full max-w-[480px] rounded-apple p-8 md:p-12 relative overflow-hidden"
      >
        <div class="text-center mb-10">
          <h1 class="font-headline-xl text-headline-xl text-primary mb-2">登录您的账号</h1>
          <p class="font-body-md text-body-md text-on-surface-variant">请选择您的登录方式</p>
        </div>

        <!-- Tabs UI -->
        <div class="tabs-container bg-surface-container-low p-1 rounded-xl flex mb-8 relative">
          <button
            class="flex-1 text-center py-2 rounded-lg cursor-pointer font-label-md text-label-md text-primary transition-all duration-300"
            :class="{ 'active-tab shadow-[0_1px_3px_rgba(0,0,0,0.1)]': loginMode === 'password' }"
            @click="loginMode = 'password'"
          >
            密码登录
          </button>
          <button
            class="flex-1 text-center py-2 rounded-lg cursor-pointer font-label-md text-label-md text-primary transition-all duration-300"
            :class="{ 'active-tab shadow-[0_1px_3px_rgba(0,0,0,0.1)]': loginMode === 'code' }"
            @click="loginMode = 'code'"
          >
            验证码登录
          </button>
        </div>

        <!-- Account Login Content -->
        <div v-if="loginMode === 'password'">
          <form class="space-y-6" @submit.prevent="handleLogin">
            <div class="space-y-4">
              <div>
                <label class="sr-only" for="email">邮箱</label>
                <div class="relative" :class="{ shake: fieldState.email.shaking }">
                  <input
                    id="email"
                    v-model="email"
                    type="text"
                    placeholder="name@college.edu"
                    class="w-full bg-transparent border rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                    :class="
                      fieldState.email.touched && fieldState.email.error
                        ? 'border-error focus:border-error focus:ring-error'
                        : 'border-outline-variant focus:border-primary focus:ring-primary'
                    "
                    @focus="focusField('email')"
                    @blur="blurField('email')"
                    @input="runFieldValidation('email')"
                  />
                </div>
                <small
                  v-if="fieldState.email.touched && fieldState.email.error"
                  class="text-error text-xs mt-1 block"
                >
                  {{ fieldState.email.error }}
                </small>
              </div>

              <div>
                <label class="sr-only" for="password">密码</label>
                <div class="relative" :class="{ shake: fieldState.password.shaking }">
                  <input
                    id="password"
                    v-model="password"
                    :type="showPassword ? 'text' : 'password'"
                    placeholder="请输入密码"
                    class="w-full bg-transparent border rounded-xl pl-4 pr-10 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                    :class="
                      fieldState.password.touched && fieldState.password.error
                        ? 'border-error focus:border-error focus:ring-error'
                        : 'border-outline-variant focus:border-primary focus:ring-primary'
                    "
                    @focus="focusField('password')"
                    @blur="blurField('password')"
                    @input="runFieldValidation('password')"
                  />
                  <button
                    type="button"
                    class="absolute right-3 top-1/2 -translate-y-1/2 text-outline-variant hover:text-on-surface transition-colors"
                    @click="showPassword = !showPassword"
                    tabindex="-1"
                  >
                    <EyeOffOutline v-if="showPassword" class="w-5 h-5" />
                    <EyeOutline v-else class="w-5 h-5" />
                  </button>
                </div>
                <small
                  v-if="fieldState.password.touched && fieldState.password.error"
                  class="text-error text-xs mt-1 block"
                >
                  {{ fieldState.password.error }}
                </small>
              </div>
            </div>

            <div class="flex items-center justify-between">
              <label class="flex items-center gap-2 cursor-pointer group">
                <input
                  v-model="rememberMe"
                  type="checkbox"
                  class="w-4 h-4 rounded border-outline-variant text-primary focus:ring-primary rounded-sm transition-colors cursor-pointer"
                />
                <span
                  class="font-label-md text-label-md text-on-surface-variant group-hover:text-primary transition-colors"
                  >记住我</span
                >
              </label>
              <a
                href="#"
                class="font-label-md text-label-md text-secondary hover:underline transition-all"
                @click.prevent="router.push('/forgot-password')"
                >忘记密码?</a
              >
            </div>

            <button
              type="submit"
              :disabled="loading || !canSubmit"
              class="w-full bg-primary text-on-primary font-label-md text-label-md py-3.5 rounded-full hover:opacity-90 transition-opacity flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed group"
            >
              {{ loading ? '登录中...' : '登录' }}
              <ArrowForwardOutline
                class="w-[18px] h-[18px] group-hover:translate-x-1 transition-transform"
              />
            </button>
          </form>
        </div>

        <!-- SMS Login Content -->
        <div v-else>
          <form class="space-y-6" @submit.prevent="handleLogin">
            <div class="space-y-4">
              <div>
                <label class="sr-only" for="emailCodeInput">邮箱</label>
                <div class="relative" :class="{ shake: fieldState.email.shaking }">
                  <input
                    id="emailCodeInput"
                    v-model="email"
                    type="text"
                    placeholder="name@college.edu"
                    class="w-full bg-transparent border rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                    :class="
                      fieldState.email.touched && fieldState.email.error
                        ? 'border-error focus:border-error focus:ring-error'
                        : 'border-outline-variant focus:border-primary focus:ring-primary'
                    "
                    @focus="focusField('email')"
                    @blur="blurField('email')"
                    @input="runFieldValidation('email')"
                  />
                </div>
                <small
                  v-if="fieldState.email.touched && fieldState.email.error"
                  class="text-error text-xs mt-1 block"
                >
                  {{ fieldState.email.error }}
                </small>
              </div>

              <div>
                <label class="sr-only" for="code">验证码</label>
                <div class="relative flex gap-2">
                  <input
                    id="code"
                    v-model="emailCode"
                    type="text"
                    placeholder="6位验证码"
                    class="flex-grow bg-transparent border border-outline-variant rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:border-primary focus:ring-1 focus:ring-primary transition-colors outline-none"
                  />
                  <button
                    type="button"
                    :disabled="codeLoading || codeCountdown > 0"
                    class="bg-surface-container-low text-primary font-label-md text-label-md px-4 py-3 rounded-xl border border-outline-variant hover:bg-surface-container transition-colors whitespace-nowrap disabled:opacity-50 disabled:cursor-not-allowed"
                    @click="handleSendCode"
                  >
                    {{
                      codeCountdown > 0
                        ? `${codeCountdown}s`
                        : codeLoading
                          ? '发送中...'
                          : '获取验证码'
                    }}
                  </button>
                </div>
              </div>
            </div>

            <button
              type="submit"
              :disabled="loading || !canSubmit"
              class="w-full bg-primary text-on-primary font-label-md text-label-md py-3.5 rounded-full hover:opacity-90 transition-opacity flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed group"
            >
              {{ loading ? '登录中...' : '登录' }}
              <ArrowForwardOutline
                class="w-[18px] h-[18px] group-hover:translate-x-1 transition-transform"
              />
            </button>
          </form>
        </div>

        <!-- Divider -->
        <div class="relative my-8">
          <div class="absolute inset-0 flex items-center">
            <div class="w-full border-t border-outline-variant opacity-50" />
          </div>
          <div class="relative flex justify-center text-sm">
            <span
              class="px-4 bg-[#f8f9fc] dark:bg-[#1a1a1c] text-on-surface-variant font-label-sm text-label-sm uppercase rounded-full"
              >其他方式</span
            >
          </div>
        </div>

        <!-- Social Logins -->
        <div class="flex justify-center gap-4 mb-8">
          <button
            aria-label="WeChat Login"
            :disabled="wechatLoading"
            class="w-12 h-12 rounded-full border border-outline-variant flex items-center justify-center hover:bg-surface-container-low transition-colors group"
            @click="handleSocialLogin('微信')"
          >
            <LogoWechat class="w-6 h-6 text-primary group-hover:scale-110 transition-transform" />
          </button>
          <button
            aria-label="QQ Login"
            class="w-12 h-12 rounded-full border border-outline-variant flex items-center justify-center hover:bg-surface-container-low transition-colors group"
            @click="handleSocialLogin('QQ')"
          >
            <svg
              viewBox="0 0 448 512"
              fill="currentColor"
              class="w-5 h-5 text-primary group-hover:scale-110 transition-transform"
            >
              <path
                d="M433.754 420.445c-11.526 1.393-44.86-52.741-44.86-52.741 0 31.345-16.136 72.247-51.051 101.786 16.842 5.192 54.843 19.167 45.803 34.421-7.316 12.343-125.51 7.881-159.632 4.037-34.122 3.844-152.316 8.306-159.632-4.037-9.045-15.25 28.918-29.214 45.783-34.415-34.92-29.539-51.059-70.445-51.059-101.792 0 0-33.334 54.134-44.859 52.741-5.37-.65-12.424-29.644 9.347-99.704 10.261-33.024 21.995-60.478 40.144-105.779C60.683 98.063 108.982.006 224 0c113.737.006 163.156 96.133 160.264 214.963 18.118 45.223 29.912 72.85 40.144 105.778 21.768 70.06 14.716 99.053 9.346 99.704z"
              />
            </svg>
          </button>
          <button
            aria-label="GitHub Login"
            class="w-12 h-12 rounded-full border border-outline-variant flex items-center justify-center hover:bg-surface-container-low transition-colors group"
            @click="handleSocialLogin('GitHub')"
          >
            <LogoGithub class="w-6 h-6 text-primary group-hover:scale-110 transition-transform" />
          </button>
        </div>

        <!-- Secondary Actions -->
        <div class="flex flex-col items-center gap-3">
          <div class="font-label-md text-label-md text-on-surface-variant">
            还没有账号？
            <button
              class="text-secondary hover:underline font-bold transition-all"
              @click="router.push('/register')"
            >
              立即注册
            </button>
          </div>
          <button
            class="font-label-md text-label-md text-on-surface-variant hover:text-primary transition-colors"
            @click="handleGuestLogin"
          >
            以游客身份继续
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.bg-background {
  background: var(--cf-page-bg);
  color: var(--cf-text-primary);
}

.pointer-events-none.opacity-20 {
  display: none;
}

nav {
  padding: 18px 24px 0;
}

.glass-panel {
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 22px;
  box-shadow:
    0 18px 55px rgba(15, 23, 42, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(24px) saturate(150%);
  -webkit-backdrop-filter: blur(24px) saturate(150%);
}

.active-tab {
  background: var(--cf-bg-base) !important;
}

input {
  background: var(--cf-bg-base) !important;
  border-color: var(--cf-border) !important;
  color: var(--cf-text-primary) !important;
}

input::placeholder {
  color: var(--cf-text-muted) !important;
  opacity: 0.8;
}

input:focus {
  border-color: var(--cf-primary) !important;
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--cf-primary) 12%, transparent) !important;
}

.tabs-container {
  background: var(--cf-bg-soft) !important;
  border: 1px solid var(--cf-border);
}

button.bg-primary {
  background: var(--cf-primary) !important;
  color: var(--cf-text-inverse) !important;
  box-shadow: 0 14px 30px color-mix(in srgb, var(--cf-primary) 24%, transparent);
}

button.bg-surface-container-low {
  background: var(--cf-bg-soft) !important;
  border-color: var(--cf-border) !important;
}

.glass-panel {
  overflow: visible;
}

html[data-theme='dark'] .glass-panel {
  background: rgba(12, 12, 13, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.shake {
  animation: field-shake 0.48s ease;
}

@keyframes field-shake {
  0%,
  100% {
    transform: translateX(0);
  }
  20%,
  60% {
    transform: translateX(-4px);
  }
  40%,
  80% {
    transform: translateX(4px);
  }
}

.rolling-text {
  animation: none;
}

@keyframes rollDown {
  0% {
    transform: translateY(-50%);
  }
  100% {
    transform: translateY(0);
  }
}
</style>
