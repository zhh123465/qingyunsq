<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NButton, NInput, NSelect, NCard, NUpload, useMessage, type UploadFileInfo } from 'naive-ui';
import { resourceAccept, uploadResource } from '@/api/resources';

const router = useRouter();
const route = useRoute();
const message = useMessage();

const file = ref<File | null>(null);
const description = ref('');
const visibility = ref('PUBLIC');
const college = ref('');
const major = ref('');
const course = ref('');
const loading = ref(false);
const spaceId = ref<number | undefined>();
const spaceName = ref('');

const visibilityOptions = [
  { label: '公开（所有人可见）', value: 'PUBLIC' },
  { label: '空间内可见', value: 'SPACE' },
  { label: '仅自己可见', value: 'PRIVATE' },
];

function handleFileChange(fileList: UploadFileInfo[]) {
  if (fileList.length > 0) {
    file.value = fileList[0].file || null;
  }
}

onMounted(() => {
  const spaceIdParam = Number(route.query.spaceId);
  if (Number.isFinite(spaceIdParam) && spaceIdParam > 0) {
    spaceId.value = spaceIdParam;
    spaceName.value = String(route.query.spaceName || '当前学习圈');
    visibility.value = 'SPACE';
  }
});

async function submit() {
  if (!file.value) {
    message.warning('请选择文件');
    return;
  }
  loading.value = true;
  try {
    const resource = await uploadResource(file.value, {
      visibility: visibility.value,
      college: college.value.trim() || undefined,
      major: major.value.trim() || undefined,
      course: course.value.trim() || undefined,
      description: description.value.trim() || undefined,
      spaceId: spaceId.value,
    });
    if (resource.status === 2) {
      message.success('上传成功，已提交审核，通过后对外可见');
    } else {
      message.success('上传成功');
    }
    router.push(`/resources/${resource.id}`);
  } catch {
    message.error('上传失败');
  }
  loading.value = false;
}

function cancel() {
  router.back();
}
</script>

<template>
  <div class="upload-page">
    <section class="form-hero">
      <div>
        <span>Resources</span>
        <h1>{{ spaceId ? `上传到 ${spaceName}` : '上传资源' }}</h1>
        <p>补全课程、专业和描述，能让资源在搜索和学习圈里更容易被需要的人找到。</p>
      </div>
      <NButton quaternary @click="cancel">
        返回
      </NButton>
    </section>

    <div class="form-grid">
      <NCard class="form-card" title="资源信息">
        <div class="form">
          <label>选择文件（最大 200MB）</label>
          <div class="upload-drop">
            <NUpload
              :max="1"
              :accept="resourceAccept || undefined"
              @update:file-list="handleFileChange"
            >
              <NButton>选择文件</NButton>
            </NUpload>
            <p>支持任意类型文件（文档、课件、软件安装包等）。上传后需管理员审核，通过后对外可见。</p>
          </div>
          <div
            v-if="file"
            class="selected-file"
          >
            已选：{{ file.name }} ({{ (file.size / 1024 / 1024).toFixed(1) }} MB)
          </div>

          <label>可见性</label>
          <NSelect
            v-model:value="visibility"
            :options="visibilityOptions"
          />

          <label>学院</label>
          <NInput
            v-model:value="college"
            placeholder="例如：计算机学院"
            maxlength="64"
          />

          <label>专业</label>
          <NInput
            v-model:value="major"
            placeholder="例如：软件工程"
            maxlength="64"
          />

          <label>课程</label>
          <NInput
            v-model:value="course"
            placeholder="例如：Java程序设计"
            maxlength="128"
          />

          <label>描述</label>
          <NInput
            v-model:value="description"
            type="textarea"
            placeholder="简单描述资源内容..."
            maxlength="500"
          />

          <div class="actions">
            <NButton
              type="primary"
              :loading="loading"
              @click="submit"
            >
              上传
            </NButton>
            <NButton @click="cancel">
              取消
            </NButton>
          </div>
        </div>
      </NCard>

      <aside class="helper-card">
        <h3>上传建议</h3>
        <p>资源越容易被识别，越容易被收藏、下载和推荐。</p>
        <ul>
          <li>上传的资源需管理员审核，审核结果会通过站内通知告知。</li>
          <li>课程名建议使用老师或课表里的正式名称。</li>
          <li>描述里写清适用章节、考试范围或版本。</li>
          <li>学习圈资料优先设置为空间内可见。</li>
        </ul>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.upload-page {
  min-height: calc(100vh - 112px);
  padding: 8px 0 40px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-hero,
.form-card,
.helper-card {
  background: var(--cf-card-bg);
  border: 1px solid var(--cf-card-border);
  border-radius: 20px;
  box-shadow: var(--cf-card-shadow);
  backdrop-filter: blur(24px) saturate(150%);
}

.form-hero {
  padding: 24px;
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;

  span {
    color: var(--cf-primary);
    font-size: 13px;
    font-weight: 900;
  }

  h1 {
    margin: 8px 0;
    font-size: 28px;
    line-height: 1.2;
  }

  p {
    margin: 0;
    color: var(--cf-text-secondary);
  }
}

.form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 20px;
  align-items: start;
}

.form-card :deep(.n-card-header) {
  font-weight: 900;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form label {
  font-size: 14px;
  color: var(--cf-text-secondary);
  font-weight: 800;
  margin-bottom: -6px;
}

.upload-drop {
  padding: 18px;
  border: 1px dashed color-mix(in srgb, var(--cf-primary) 42%, var(--cf-border));
  border-radius: 16px;
  background: color-mix(in srgb, var(--cf-primary) 7%, var(--cf-bg-card));

  p {
    margin: 10px 0 0;
    color: var(--cf-text-muted);
    font-size: 13px;
  }
}

.selected-file {
  font-size: 13px;
  color: var(--cf-primary);
  font-weight: 800;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 10px;
}

.helper-card {
  padding: 22px;

  h3 {
    margin: 0 0 10px;
  }

  p,
  li {
    color: var(--cf-text-secondary);
    line-height: 1.7;
  }

  ul {
    margin: 16px 0 0;
    padding-left: 18px;
  }
}

@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
