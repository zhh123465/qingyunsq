<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { EyeOffOutline, EyeOutline } from '@vicons/ionicons5';
import { forgotPassword, resetPassword } from '@/api/auth';
import {
  getPasswordStrength,
  validateConfirmPassword,
  validateEmail,
  validatePassword,
} from '@/utils/authValidation';

const router = useRouter();
const message = useMessage();

const step = ref(1);
const email = ref('');
const emailCode = ref('');
const newPassword = ref('');
const showNewPassword = ref(false);
const confirmPassword = ref('');
const showConfirmPassword = ref(false);
const loading = ref(false);
const fieldState = ref({
  email: { active: false, touched: false, error: '', shaking: false },
  newPassword: { active: false, touched: false, error: '', shaking: false },
  confirmPassword: { active: false, touched: false, error: '', shaking: false },
});

const stepTitle = computed(() => (step.value === 1 ? '验证账号身份' : '设置新密码'));
const passwordStrength = computed(() => getPasswordStrength(newPassword.value));

function getErrorMessage(error: unknown): string {
  const err = error as { response?: { data?: { message?: string } } };
  return err.response?.data?.message || '重置失败';
}

type ForgotField = keyof typeof fieldState.value;

function runFieldValidation(field: ForgotField) {
  const validators: Record<ForgotField, () => string> = {
    email: () => validateEmail(email.value),
    newPassword: () => validatePassword(newPassword.value),
    confirmPassword: () => validateConfirmPassword(confirmPassword.value, newPassword.value),
  };
  fieldState.value[field].error = validators[field]();
  if (field === 'newPassword' && confirmPassword.value) {
    fieldState.value.confirmPassword.error = validateConfirmPassword(
      confirmPassword.value,
      newPassword.value,
    );
  }
}

function focusField(field: ForgotField) {
  fieldState.value[field].active = true;
  runFieldValidation(field);
}

function blurField(field: ForgotField) {
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

function validateFields(fields: ForgotField[]) {
  fields.forEach((field) => {
    fieldState.value[field].touched = true;
    runFieldValidation(field);
  });
  return fields.every((field) => !fieldState.value[field].error);
}

function resetPasswordFields() {
  newPassword.value = '';
  confirmPassword.value = '';
  fieldState.value.newPassword = { active: false, touched: false, error: '', shaking: false };
  fieldState.value.confirmPassword = { active: false, touched: false, error: '', shaking: false };
}

async function handleSendCode() {
  if (!validateFields(['email'])) {
    message.warning('请按提示修正邮箱');
    return;
  }
  loading.value = true;
  try {
    await forgotPassword(email.value.trim());
    message.success('验证码已发送，请查收邮箱');
    step.value = 2;
  } catch {
    message.error('验证码发送失败');
  } finally {
    loading.value = false;
  }
}

async function handleReset() {
  if (!validateFields(['newPassword', 'confirmPassword'])) {
    message.warning('请按提示修正新密码');
    return;
  }
  loading.value = true;
  try {
    await resetPassword(email.value.trim(), emailCode.value.trim(), newPassword.value);
    message.success('密码重置成功，请重新登录');
  } catch (error) {
    message.error(getErrorMessage(error));
    resetPasswordFields();
  } finally {
    loading.value = false;
  }
}
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
        <div class="text-center mb-8">
          <h1 class="font-headline-xl text-headline-xl text-primary mb-2">找回密码</h1>
          <p class="font-body-md text-body-md text-on-surface-variant">
            {{ stepTitle }}
          </p>
        </div>

        <!-- Step Indicator (Apple Style Connection Bar) -->
        <div class="flex items-center justify-center gap-3 mb-8">
          <div class="flex items-center gap-2">
            <div
              class="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold transition-all duration-300"
              :class="
                step === 1
                  ? 'bg-primary text-on-primary'
                  : 'bg-surface-container-low text-on-surface-variant'
              "
            >
              1
            </div>
            <span
              class="text-sm font-medium transition-colors duration-300"
              :class="step === 1 ? 'text-primary font-bold' : 'text-on-surface-variant'"
            >
              验证身份
            </span>
          </div>
          <div
            class="w-12 h-px transition-colors duration-300"
            :class="step === 2 ? 'bg-primary' : 'bg-outline-variant'"
          />
          <div class="flex items-center gap-2">
            <div
              class="w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold transition-all duration-300"
              :class="
                step === 2
                  ? 'bg-primary text-on-primary'
                  : 'bg-surface-container-low text-on-surface-variant'
              "
            >
              2
            </div>
            <span
              class="text-sm font-medium transition-colors duration-300"
              :class="step === 2 ? 'text-primary font-bold' : 'text-on-surface-variant'"
            >
              重置密码
            </span>
          </div>
        </div>

        <!-- Step 1: Input Email -->
        <div v-if="step === 1" class="space-y-6">
          <div class="space-y-4">
            <div>
              <label class="sr-only" for="email">注册邮箱</label>
              <div class="relative" :class="{ shake: fieldState.email.shaking }">
                <input
                  id="email"
                  v-model="email"
                  type="text"
                  placeholder="请输入注册时使用的邮箱"
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
          </div>

          <button
            :disabled="loading"
            class="w-full bg-primary text-on-primary font-label-md text-label-md py-3.5 rounded-full hover:opacity-90 transition-opacity flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
            @click="handleSendCode"
          >
            {{ loading ? '发送中...' : '获取验证码' }}
          </button>
        </div>

        <!-- Step 2: Input Code & New Password -->
        <div v-else class="space-y-5">
          <div class="space-y-4">
            <!-- Code Input -->
            <div>
              <label class="sr-only" for="emailCode">验证码</label>
              <input
                id="emailCode"
                v-model="emailCode"
                type="text"
                placeholder="请输入邮箱验证码"
                class="w-full bg-transparent border border-outline-variant rounded-xl px-4 py-3 font-body-md text-body-md text-on-surface focus:border-primary focus:ring-1 focus:ring-primary transition-colors outline-none"
              />
            </div>

            <!-- New Password Input -->
            <div>
              <label class="sr-only" for="newPassword">新密码</label>
              <div class="relative" :class="{ shake: fieldState.newPassword.shaking }">
                <input
                  id="newPassword"
                  v-model="newPassword"
                  :type="showNewPassword ? 'text' : 'password'"
                  placeholder="8-64 位新密码，需包含字母和数字"
                  class="w-full bg-transparent border rounded-xl pl-4 pr-10 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                  :class="
                    fieldState.newPassword.touched && fieldState.newPassword.error
                      ? 'border-error focus:border-error focus:ring-error'
                      : 'border-outline-variant focus:border-primary focus:ring-primary'
                  "
                  @focus="focusField('newPassword')"
                  @blur="blurField('newPassword')"
                  @input="runFieldValidation('newPassword')"
                />
                <button
                  type="button"
                  class="absolute right-3 top-1/2 -translate-y-1/2 text-outline-variant hover:text-on-surface transition-colors"
                  @click="showNewPassword = !showNewPassword"
                  tabindex="-1"
                >
                  <EyeOffOutline v-if="showNewPassword" class="w-5 h-5" />
                  <EyeOutline v-else class="w-5 h-5" />
                </button>
              </div>
              <!-- Password Strength Bar -->
              <div
                v-if="fieldState.newPassword.active || newPassword"
                class="mt-2 text-xs flex items-center gap-2"
                :class="{
                  'text-error': passwordStrength.strength === 'weak',
                  'text-orange-500': passwordStrength.strength === 'medium',
                  'text-primary': passwordStrength.strength === 'strong',
                  'text-outline-variant': passwordStrength.strength === 'empty',
                }"
              >
                <div class="flex-grow h-1.5 rounded-full bg-surface-container-low overflow-hidden">
                  <div
                    class="h-full rounded-full transition-all duration-300"
                    :class="{
                      'w-1/3 bg-error': passwordStrength.strength === 'weak',
                      'w-2/3 bg-orange-500': passwordStrength.strength === 'medium',
                      'w-full bg-primary': passwordStrength.strength === 'strong',
                      'w-0': passwordStrength.strength === 'empty',
                    }"
                  />
                </div>
                <span class="whitespace-nowrap">强度: {{ passwordStrength.label }}</span>
              </div>
              <small
                v-if="fieldState.newPassword.touched && fieldState.newPassword.error"
                class="text-error text-xs mt-1 block"
              >
                {{ fieldState.newPassword.error }}
              </small>
            </div>

            <!-- Confirm Password Input -->
            <div>
              <label class="sr-only" for="confirmPassword">确认密码</label>
              <div class="relative" :class="{ shake: fieldState.confirmPassword.shaking }">
                <input
                  id="confirmPassword"
                  v-model="confirmPassword"
                  :type="showConfirmPassword ? 'text' : 'password'"
                  placeholder="请再次输入新密码"
                  class="w-full bg-transparent border rounded-xl pl-4 pr-10 py-3 font-body-md text-body-md text-on-surface focus:ring-1 transition-colors outline-none"
                  :class="
                    fieldState.confirmPassword.touched && fieldState.confirmPassword.error
                      ? 'border-error focus:border-error focus:ring-error'
                      : 'border-outline-variant focus:border-primary focus:ring-primary'
                  "
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
              <small
                v-if="fieldState.confirmPassword.touched && fieldState.confirmPassword.error"
                class="text-error text-xs mt-1 block"
              >
                {{ fieldState.confirmPassword.error }}
              </small>
            </div>
          </div>

          <div class="flex gap-4">
            <button
              type="button"
              class="flex-1 bg-surface-container-low text-primary border border-outline-variant font-label-md text-label-md py-3 rounded-full hover:bg-surface-container transition-colors"
              @click="step = 1"
            >
              上一步
            </button>
            <button
              type="button"
              :disabled="loading"
              class="flex-1 bg-primary text-on-primary font-label-md text-label-md py-3 rounded-full hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed"
              @click="handleReset"
            >
              {{ loading ? '重置中...' : '重置密码' }}
            </button>
          </div>
        </div>

        <!-- Footer Note -->
        <div class="flex flex-col items-center gap-3 mt-8">
          <div class="font-label-md text-label-md text-on-surface-variant">
            想起密码了？
            <button
              class="text-secondary hover:underline font-bold transition-all"
              @click="router.push('/login')"
            >
              返回登录
            </button>
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
</style>
