<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import {
  NCard, NSelect, NInput, NButton, NSpace, NForm, NFormItem, NTag,
  useMessage,
  type FormInst, type FormRules,
} from 'naive-ui';
import { getTenants, updateTenantAiConfig, getTenantAiConfig } from '@/api/tenants';
import type { TenantVO } from '@/api/tenants';
import { useAuthStore } from '@/stores/auth';

const message = useMessage();
const auth = useAuthStore();
const currentRole = computed(() => auth.user?.role || localStorage.getItem('role') || '');
const isSuper = computed(() => currentRole.value === 'SUPER_ADMIN');

const tenants = ref<TenantVO[]>([]);
const selectedTenantId = ref<number | null>(null);
const currentTenantName = ref<string>('');
const loading = ref(false);
const saving = ref(false);

const form = ref({ provider: 'mock', baseUrl: '', apiKey: '', model: '' });
const formRef = ref<FormInst | null>(null);

const providerOptions = [
  { label: 'Mock (模拟AI)', value: 'mock' },
  { label: 'OpenAI 兼容', value: 'openai' },
];

const rules = computed<FormRules>(() => {
  if (form.value.provider !== 'openai') {
    return {} as FormRules;
  }
  return {
    baseUrl: [
      { required: true, message: '请填写 API Base URL', trigger: ['input', 'blur'] },
      {
        validator: (_rule, value: string) => /^https?:\/\/.+/.test(value || ''),
        message: '必须是 http:// 或 https:// 开头的 URL',
        trigger: ['input', 'blur'],
      },
    ],
    apiKey: [
      { required: true, message: '请填写 API Key', trigger: ['input', 'blur'] },
    ],
    model: [
      { required: true, message: '请填写 Model 名称', trigger: ['input', 'blur'] },
    ],
  };
});

onMounted(async () => {
  if (isSuper.value) {
    try {
      tenants.value = await getTenants();
    } catch {
      // ignore
    }
  } else {
    // TENANT_ADMIN 不能列全租户，直接锁定为当前租户
    if (auth.tenantId != null) {
      selectedTenantId.value = auth.tenantId;
      currentTenantName.value = auth.tenantCode || `租户 ${auth.tenantId}`;
      await loadConfig(auth.tenantId);
    }
  }
});

async function loadConfig(id: number) {
  loading.value = true;
  try {
    const cfg = await getTenantAiConfig(id);
    form.value = {
      provider: cfg.provider || 'mock',
      baseUrl: cfg.baseUrl || '',
      apiKey: cfg.apiKey || '',
      model: cfg.model || '',
    };
  } catch {
    message.error('加载配置失败');
  }
  loading.value = false;
}

async function selectTenant(id: number) {
  selectedTenantId.value = id;
  await loadConfig(id);
}

async function save() {
  if (selectedTenantId.value === null) return;
  try {
    await formRef.value?.validate();
  } catch {
    return;
  }
  saving.value = true;
  try {
    await updateTenantAiConfig(selectedTenantId.value, form.value);
    message.success('AI 配置已保存');
  } catch {
    message.error('保存失败');
  }
  saving.value = false;
}
</script>

<template>
  <div class="admin-page">
    <h2>AI 配置管理</h2>

    <NSpace
      v-if="isSuper"
      class="admin-filterbar"
    >
      <NSelect
        v-model:value="selectedTenantId"
        :options="tenants.map(t => ({ label: t.name, value: t.id }))"
        placeholder="选择租户"
        style="width: 240px;"
        @update:value="selectTenant"
      />
    </NSpace>

    <div
      v-else
      class="admin-filterbar tenant-lock"
    >
      当前租户：
      <NTag
        type="info"
        style="margin-left: 8px;"
      >{{ currentTenantName }}</NTag>
      <span class="lock-hint">
        （租户管理员仅可管理自己租户的 AI 配置，如需跨租户请联系超级管理员）
      </span>
    </div>

    <template v-if="selectedTenantId !== null">
      <NCard
        v-if="!loading"
        title="AI 服务配置"
      >
        <NForm
          ref="formRef"
          :model="form"
          :rules="rules"
          label-placement="top"
          style="width: 100%; max-width: 500px;"
        >
          <NFormItem
            label="AI Provider"
            path="provider"
          >
            <NSelect
              v-model:value="form.provider"
              :options="providerOptions"
            />
          </NFormItem>
          <NFormItem
            label="API Base URL"
            path="baseUrl"
          >
            <NInput
              v-model:value="form.baseUrl"
              placeholder="https://api.deepseek.com"
            />
          </NFormItem>
          <NFormItem
            label="API Key"
            path="apiKey"
          >
            <NInput
              v-model:value="form.apiKey"
              type="password"
              show-password-on="click"
              placeholder="sk-..."
            />
          </NFormItem>
          <NFormItem
            label="Model"
            path="model"
          >
            <NInput
              v-model:value="form.model"
              placeholder="deepseek-v4-flash"
            />
          </NFormItem>
          <NButton
            type="primary"
            :loading="saving"
            @click="save"
          >
            保存配置
          </NButton>
        </NForm>
      </NCard>
      <p v-else>加载中...</p>
    </template>
    <p
      v-else-if="isSuper"
      style="color: #999;"
    >
      请先选择租户
    </p>
  </div>
</template>

<style scoped>
.tenant-lock {
  display: flex;
  align-items: center;
  font-size: 14px;
  color: var(--cf-text-primary);
}
.lock-hint {
  color: var(--cf-text-secondary);
  font-size: 12px;
  margin-left: 12px;
}
</style>
