<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { EyeOffOutline, EyeOutline } from '@vicons/ionicons5';
import { register, sendEmailCode } from '@/api/auth';
import {
  getPasswordStrength,
  validateConfirmPassword,
  validateEmail,
  validateNickname,
  validatePassword,
  validateStudentNo,
} from '@/utils/authValidation';

const router = useRouter();
const route = useRoute();
const message = useMessage();

const prefilledEmail = typeof route.query.email === 'string' ? route.query.email.trim() : '';

const email = ref(prefilledEmail);
const password = ref('');
const showPassword = ref(false);
const confirmPassword = ref('');
const showConfirmPassword = ref(false);
const studentNo = ref('');
const nickname = ref('');
const emailCode = ref('');
const loading = ref(false);
const codeLoading = ref(false);
const codeCountdown = ref(0);
let codeTimer: number | undefined;
const fieldState = ref({
  nickname: { active: false, touched: false, error: '', shaking: false },
  studentNo: { active: false, touched: false, error: '', shaking: false },
  email: { active: false, touched: false, error: '', shaking: false },
  password: { active: false, touched: false, error: '', shaking: false },
  confirmPassword: { active: false, touched: false, error: '', shaking: false },
});

if (prefilledEmail) {
  fieldState.value.email.touched = true;
  fieldState.value.email.error = validateEmail(prefilledEmail);
}
const passwordStrength = computed(() => getPasswordStrength(password.value));
const canSubmit = computed(() =>
  Boolean(
    email.value.trim() &&
      password.value &&
      confirmPassword.value &&
      nickname.value.trim() &&
      emailCode.value.trim() &&
      !Object.values(fieldState.value).some((item) => item.error),
  ),
);

type RegisterField = keyof typeof fieldState.value;

function runFieldValidation(field: RegisterField) {
  const validators: Record<RegisterField, () => string> = {
    nickname: () => validateNickname(nickname.value),
    studentNo: () => validateStudentNo(studentNo.value),
    email: () => validateEmail(email.value),
    password: () => validatePassword(password.value),
    confirmPassword: () => validateConfirmPassword(confirmPassword.value, password.value),
  };
  fieldState.value[field].error = validators[field]();
  if (field === 'password' && confirmPassword.value) {
    fieldState.value.confirmPassword.error = validateConfirmPassword(confirmPassword.value, password.value);
  }
}

function focusField(field: RegisterField) {
  fieldState.value[field].active = true;
  runFieldValidation(field);
}

function blurField(field: RegisterField) {
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
  (Object.keys(fieldState.value) as RegisterField[]).forEach((field) => {
    fieldState.value[field].touched = true;
    runFieldValidation(field);
  });
  return (Object.keys(fieldState.value) as RegisterField[]).every((field) => !fieldState.value[field].error);
}

function resetRegisterForm() {
  email.value = '';
  password.value = '';
  confirmPassword.value = '';
  studentNo.value = '';
  nickname.value = '';
  emailCode.value = '';
  fieldState.value = {
    nickname: { active: false, touched: false, error: '', shaking: false },
    studentNo: { active: false, touched: false, error: '', shaking: false },
    email: { active: false, touched: false, error: '', shaking: false },
    password: { active: false, touched: false, error: '', shaking: false },
    confirmPassword: { active: false, touched: false, error: '', shaking: false },
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
  codeLoading.value = true;
  try {
    await sendEmailCode(email.value.trim(), 'REGISTER');
    message.success('验证码已发送，请查收邮箱');
    startCodeCountdown();
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '验证码发送失败');
  } finally {
    codeLoading.value = false;
  }
}

async function handleRegister() {
  if (!validateForm()) {
    message.warning('请按提示修正注册信息');
    return;
  }
  if (!emailCode.value.trim()) {
    message.warning('请填写邮箱验证码');
    return;
  }

  if (loading.value) return;
  loading.value = true;
  try {
    await register({
      email: email.value.trim(),
      password: password.value,
      emailCode: emailCode.value.trim(),
      studentNo: studentNo.value.trim() || undefined,
      nickname: nickname.value.trim(),
    });
    message.success('注册成功，请登录');
    router.push('/login');
  } catch (err: unknown) {
    const errMsg = err instanceof Error ? err.message : '注册失败';
    const fieldErrors = errMsg.split(';').map((s: string) => {
      const parts = s.trim().split(':');
      return parts.length > 1 ? parts.slice(1).join(':').trim() : s.trim();
    }).filter(Boolean);
    if (fieldErrors.length > 0 && fieldErrors[0] !== '注册失败') {
      message.error(fieldErrors.join('；'));
    } else {
      message.error(errMsg);
    }
    resetRegisterForm();
  } finally {
    loading.value = false;
  }
}

onBeforeUnmount(() => {
  window.clearInterval(codeTimer);
});
</script>

<template>
<div class="bg-background text-on-surface min-h-screen flex flex-col relative overflow-hidden py-10">
  <!-- Background Abstract Pattern -->
  <div class="absolute inset-0 pointer-events-none opacity-20">
    <div class="absolute -top-[20%] -left-[10%] w-[50%] h-[50%] rounded-full bg-gradient-to-br from-blue-100 to-purple-100 blur-[100px]" />
    <div class="absolute bottom-[10%] -right-[10%] w-[60%] h-[60%] rounded-full bg-gradient-to-tl from-indigo-50 to-cyan-50 blur-[120px]" />
  </div>

  <!-- TopNavBar -->
  <nav class="bg-transparent docked full-width top-0 z-50">
    <div class="flex justify-between items-center px-margin-mobile md:px-margin-desktop w-full mx-auto">
      <div class="flex items-center gap-3">
        <div class="font-headline-md text-[24px] font-extrabold tracking-tight h-[32px] overflow-hidden">
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
  <main class="flex-grow flex items-center justify-center p-margin-mobile md:p-margin-desktop relative z-10">
    <div class="glass-panel w-full max-w-[560px] rounded-apple p-8 md:p-12 relative overflow-hidden">
      <div class="text-center mb-8">
        <h1 class="font-headline-xl text-headline-xl text-primary mb-2">创建账号</h1>
        <p class="font-body-md text-body-md text-on-surface-variant">填写基础信息开启个人知识库</p>
      </div>

      <form class="space-y-5" @submit.prevent="handleRegister">
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-5">
          <div>
            <label class="sr-only" for="nickname">昵称</label>
            <div class="relative" :class="{ 'shake': fieldState.nickname.shaking }">
              <input
                id="nickname"
                v-model="nickname"
                type="text"
                placeholder="昵称 (如: 数据结构补完计划)"
                class="w-full bg-transparent border rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                :class="fieldState.nickname.touched && fieldState.nickname.error ? 'border-error focus:border-error focus:ring-error' : 'border-outline-variant focus:border-primary focus:ring-primary'"
                @focus="focusField('nickname')"
                @blur="blurField('nickname')"
                @input="runFieldValidation('nickname')"
              />
            </div>
            <small v-if="fieldState.nickname.touched && fieldState.nickname.error" class="text-error text-xs mt-1 block">
              {{ fieldState.nickname.error }}
            </small>
          </div>

          <div>
            <label class="sr-only" for="studentNo">学号</label>
            <div class="relative" :class="{ 'shake': fieldState.studentNo.shaking }">
              <input
                id="studentNo"
                v-model="studentNo"
                type="text"
                placeholder="学号 (选填)"
                class="w-full bg-transparent border rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                :class="fieldState.studentNo.touched && fieldState.studentNo.error ? 'border-error focus:border-error focus:ring-error' : 'border-outline-variant focus:border-primary focus:ring-primary'"
                @focus="focusField('studentNo')"
                @blur="blurField('studentNo')"
                @input="runFieldValidation('studentNo')"
              />
            </div>
            <small v-if="fieldState.studentNo.touched && fieldState.studentNo.error" class="text-error text-xs mt-1 block">
              {{ fieldState.studentNo.error }}
            </small>
          </div>
        </div>

        <div>
          <label class="sr-only" for="email">邮箱</label>
          <div class="relative" :class="{ 'shake': fieldState.email.shaking }">
            <input
              id="email"
              v-model="email"
              type="text"
              placeholder="name@college.edu"
              class="w-full bg-transparent border rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
              :class="fieldState.email.touched && fieldState.email.error ? 'border-error focus:border-error focus:ring-error' : 'border-outline-variant focus:border-primary focus:ring-primary'"
              @focus="focusField('email')"
              @blur="blurField('email')"
              @input="runFieldValidation('email')"
            />
          </div>
          <small v-if="fieldState.email.touched && fieldState.email.error" class="text-error text-xs mt-1 block">
            {{ fieldState.email.error }}
          </small>
        </div>

        <div>
          <label class="sr-only" for="password">密码</label>
          <div class="relative" :class="{ 'shake': fieldState.password.shaking }">
            <input
              id="password"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="8-64 位，需包含字母和数字"
              class="w-full bg-transparent border rounded-xl pl-4 pr-10 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
              :class="fieldState.password.touched && fieldState.password.error ? 'border-error focus:border-error focus:ring-error' : 'border-outline-variant focus:border-primary focus:ring-primary'"
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
          <div v-if="fieldState.password.active || password" class="mt-2 text-xs flex items-center gap-2" :class="{
            'text-error': passwordStrength.strength === 'weak',
            'text-orange-500': passwordStrength.strength === 'medium',
            'text-primary': passwordStrength.strength === 'strong',
            'text-outline-variant': passwordStrength.strength === 'empty'
          }">
            <div class="flex-grow h-1.5 rounded-full bg-surface-container-low overflow-hidden">
              <div class="h-full rounded-full transition-all duration-300" :class="{
                'w-1/3 bg-error': passwordStrength.strength === 'weak',
                'w-2/3 bg-orange-500': passwordStrength.strength === 'medium',
                'w-full bg-primary': passwordStrength.strength === 'strong',
                'w-0': passwordStrength.strength === 'empty'
              }" />
            </div>
            <span class="whitespace-nowrap">强度: {{ passwordStrength.label }}</span>
          </div>
          <small v-if="fieldState.password.touched && fieldState.password.error" class="text-error text-xs mt-1 block">
            {{ fieldState.password.error }}
          </small>
        </div>

        <div>
          <label class="sr-only" for="confirmPassword">确认密码</label>
          <div class="relative" :class="{ 'shake': fieldState.confirmPassword.shaking }">
            <input
              id="confirmPassword"
              v-model="confirmPassword"
              :type="showConfirmPassword ? 'text' : 'password'"
              placeholder="再次输入密码"
              class="w-full bg-transparent border rounded-xl pl-4 pr-10 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
              :class="fieldState.confirmPassword.touched && fieldState.confirmPassword.error ? 'border-error focus:border-error focus:ring-error' : 'border-outline-variant focus:border-primary focus:ring-primary'"
              @focus="focusField('confirmPassword')"
              @blur="blurField('confirmPassword')"
              @input="runFieldValidation('confirmPassword')"
            />
            <button
              type="button"
              class="absolute right-3 top-1/2 -translate-y-1/2 text-outline-variant hover:text-on-surface transition-colors"
              @click="showConfirmPassword = !showConfirmPassword"
              tabindex="-1"
            >
              <EyeOffOutline v-if="showConfirmPassword" class="w-5 h-5" />
              <EyeOutline v-else class="w-5 h-5" />
            </button>
          </div>
          <small v-if="fieldState.confirmPassword.touched && fieldState.confirmPassword.error" class="text-error text-xs mt-1 block">
            {{ fieldState.confirmPassword.error }}
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
              @click="handleSendCode"
              class="bg-surface-container-low text-primary font-label-md text-label-md px-4 py-3 rounded-xl border border-outline-variant hover:bg-surface-container transition-colors whitespace-nowrap disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ codeCountdown > 0 ? `${codeCountdown}s` : codeLoading ? '发送中...' : '获取验证码' }}
            </button>
          </div>
        </div>

        <button
          type="submit"
          :disabled="loading || !canSubmit"
          class="w-full bg-primary text-on-primary font-label-md text-label-md py-3.5 rounded-full hover:opacity-90 transition-opacity flex items-center justify-center gap-2 mt-4 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {{ loading ? '提交中...' : '完成注册' }}
        </button>
      </form>

      <!-- Secondary Actions -->
      <div class="flex flex-col items-center gap-3 mt-8">
        <div class="font-label-md text-label-md text-on-surface-variant">
          已经有账号？
          <button @click="router.push('/login')" class="text-secondary hover:underline font-bold transition-all">去登录</button>
        </div>
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
  overflow: visible;
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

button.bg-primary {
  background: var(--cf-primary) !important;
  color: var(--cf-text-inverse) !important;
  box-shadow: 0 14px 30px color-mix(in srgb, var(--cf-primary) 24%, transparent);
}

button.bg-surface-container-low {
  background: var(--cf-bg-soft) !important;
  border-color: var(--cf-border) !important;
}

html[data-theme='dark'] .glass-panel {
  background: rgba(12, 12, 13, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.shake {
  animation: field-shake 0.48s ease;
}

@keyframes field-shake {
  0%, 100% {
    transform: translateX(0);
  }
  20%, 60% {
    transform: translateX(-4px);
  }
  40%, 80% {
    transform: translateX(4px);
  }
}

.rolling-text {
  animation: none;
}

@keyframes rollDown {
  0% { transform: translateY(-50%); }
  100% { transform: translateY(0); }
}
</style>
