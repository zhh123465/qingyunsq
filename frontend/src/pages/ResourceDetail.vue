<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAlert, NCard, NButton, NTag, NSpace, NSpin, NEmpty, useMessage } from 'naive-ui';
import {
  deleteResource,
  getDownloadUrl,
  getOfficePreviewUrl,
  getPreviewUrl,
  getResourceById,
  getResourcePreviewText,
} from '@/api/resources';
import { useAuthStore } from '@/stores/auth';
import { useTheme } from '@/composables/useTheme';
import type { ResourcePreviewVO, ResourceVO } from '@/types/resource';
import { getResourcePreviewKind } from '@/utils/resource-preview';

const route = useRoute();
const router = useRouter();
const message = useMessage();
const authStore = useAuthStore();
const { isDarkTheme } = useTheme();

const resource = ref<ResourceVO | null>(null);
const loading = ref(true);
const previewLoading = ref(false);
const previewText = ref<ResourcePreviewVO | null>(null);
const previewError = ref('');
const previewUrl = ref('');
const currentUserId = authStore.user?.id;
const isUploader = () => resource.value?.uploaderId === currentUserId;

const markdownSrcdoc = computed(() => {
  if (!previewText.value) return '';
  const bgColor = isDarkTheme.value ? '#111827' : '#ffffff';
  const textColor = isDarkTheme.value ? '#f3f4f6' : '#07111f';
  const preBgColor = isDarkTheme.value ? '#1f2937' : '#f6f8fb';
  const blockquoteBorder = isDarkTheme.value ? '#00f5d4' : '#00d8bf';
  const blockquoteColor = isDarkTheme.value ? '#9ca3af' : '#64748b';
  return `<!doctype html><html><head><meta charset="utf-8"><style>
body{margin:0;padding:20px;font-family:Inter,Segoe UI,sans-serif;color:${textColor};background:${bgColor};line-height:1.75}
pre,code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}pre{padding:14px;background:${preBgColor};border-radius:8px;white-space:pre-wrap}
blockquote{margin:0 0 12px;padding-left:12px;color:${blockquoteColor};border-left:3px solid ${blockquoteBorder}}
</style></head><body>${renderMarkdown(previewText.value.content)}</body></html>`;
});

async function load() {
  loading.value = true;
  previewText.value = null;
  previewError.value = '';
  previewUrl.value = '';
  previewLoading.value = false;
  try {
    const id = Number(route.params.id);
    resource.value = await getResourceById(id);
    await loadPreview();
  } catch {
    resource.value = null;
  }
  loading.value = false;
}

async function loadPreview() {
  if (!resource.value) return;
  previewText.value = null;
  previewError.value = '';
  previewUrl.value = '';

  const kind = getPreviewKind(resource.value);
  if (kind === 'unsupported') return;

  previewLoading.value = true;
  try {
    if (kind === 'text') {
      previewText.value = await getResourcePreviewText(resource.value.id);
    } else if (kind === 'office') {
      previewUrl.value = await getOfficePreviewUrl(resource.value.id);
    } else {
      previewUrl.value = await getPreviewUrl(resource.value.id);
    }
  } catch {
    previewError.value = kind === 'office' ? 'Office 预览服务链接加载失败，可先下载查看' : '预览内容加载失败';
  } finally {
    previewLoading.value = false;
  }
}

async function handleDownload() {
  if (!resource.value) return;
  try {
    const url = await getDownloadUrl(resource.value.id);
    window.open(url, '_blank');
    // 刷新以更新下载计数
    setTimeout(load, 1000);
  } catch (err) {
    message.error(err instanceof Error ? err.message : '下载链接获取失败');
  }
}

async function handleDelete() {
  if (!resource.value) return;
  try {
    await deleteResource(resource.value.id);
    message.success('资源已删除');
    router.replace('/resources');
  } catch {
    message.error('删除失败');
  }
}

function goBack() {
  router.push('/resources');
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function getPreviewKind(item: ResourceVO) {
  return getResourcePreviewKind(item);
}

function escapeHtml(value: string) {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function renderMarkdown(source: string) {
  return escapeHtml(source)
    .split(/\n{2,}/)
    .map((block) => `<p>${block.replace(/\n/g, '<br>')}</p>`)
    .join('');
}

onMounted(load);
</script>

<template>
  <div class="detail-page">
    <template v-if="loading">
      <div class="loading">
        <NSpin />
      </div>
    </template>

    <template v-else-if="resource">
      <NAlert
        v-if="resource.status === 2"
        type="warning"
        class="review-banner"
        :show-icon="true"
      >
        该资源正在等待管理员审核，审核通过后才对其他用户可见。
      </NAlert>
      <NAlert
        v-else-if="resource.status === 3"
        type="error"
        class="review-banner"
        :show-icon="true"
      >
        该资源未通过审核{{ resource.reviewReason ? `：${resource.reviewReason}` : '' }}。可删除后修改重新上传。
      </NAlert>
      <NAlert
        v-else-if="resource.status === 0"
        type="error"
        class="review-banner"
        :show-icon="true"
      >
        该资源已被管理员隐藏，仅你自己可见。
      </NAlert>

      <NCard class="resource-info">
        <div class="info-header">
          <div>
            <h2>{{ resource.fileName }}</h2>
            <NSpace>
              <NTag size="small">
                {{ resource.fileType ? resource.fileType.toUpperCase() : '未知' }}
              </NTag>
              <NTag
                v-if="resource.visibility === 'PUBLIC'"
                type="success"
                size="small"
              >
                公开
              </NTag>
              <NTag
                v-else-if="resource.visibility === 'SPACE'"
                type="warning"
                size="small"
              >
                空间
              </NTag>
              <NTag
                v-else
                type="default"
                size="small"
              >
                私有
              </NTag>
            </NSpace>
          </div>
          <div class="header-actions">
            <NButton
              size="small"
              @click="goBack"
            >
              返回列表
            </NButton>
            <NButton
              v-if="isUploader()"
              type="error"
              size="small"
              @click="handleDelete"
            >
              删除
            </NButton>
          </div>
        </div>

        <p
          v-if="resource.description"
          class="resource-desc"
        >
          {{ resource.description }}
        </p>

        <div class="info-grid">
          <div class="info-item">
            <span class="info-label">大小</span>
            <span>{{ formatSize(resource.fileSize) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">下载</span>
            <span>{{ resource.downloadCount }} 次</span>
          </div>
          <div class="info-item">
            <span class="info-label">上传者</span>
            <span>{{ resource.uploader?.nickname || '未知' }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">时间</span>
            <span>{{ resource.createdAt?.split('T')[0] }}</span>
          </div>
        </div>

        <div
          v-if="resource.college || resource.major || resource.course || resource.tags?.length"
          class="info-tags"
        >
          <NTag
            v-if="resource.college"
            type="info"
            size="small"
          >
            {{ resource.college }}
          </NTag>
          <NTag
            v-if="resource.major"
            type="info"
            size="small"
          >
            {{ resource.major }}
          </NTag>
          <NTag
            v-if="resource.course"
            type="info"
            size="small"
          >
            {{ resource.course }}
          </NTag>
          <NTag
            v-for="t in resource.tags"
            :key="t"
            size="small"
          >
            {{ t }}
          </NTag>
        </div>

        <section class="preview-section">
          <div class="preview-title">
            <span>文件预览</span>
            <NButton
              quaternary
              size="small"
              @click="loadPreview"
            >
              刷新预览
            </NButton>
          </div>
          <div
            v-if="previewLoading"
            class="preview-state"
          >
            <NSpin />
          </div>
          <NAlert
            v-else-if="previewError"
            type="error"
            :show-icon="false"
          >
            {{ previewError }}
          </NAlert>
          <iframe
            v-else-if="getPreviewKind(resource) === 'pdf'"
            class="preview-frame"
            :src="previewUrl"
            title="PDF 预览"
          />
          <img
            v-else-if="getPreviewKind(resource) === 'image'"
            class="preview-image"
            :src="previewUrl"
            :alt="resource.fileName"
          >
          <iframe
            v-else-if="getPreviewKind(resource) === 'text' && previewText"
            class="markdown-frame"
            :srcdoc="markdownSrcdoc"
            title="文本预览"
          />
          <div
            v-else-if="getPreviewKind(resource) === 'office' && previewUrl"
            class="office-preview"
          >
            <iframe
              class="preview-frame"
              :src="previewUrl"
              title="Office 预览"
            />
            <NAlert
              type="info"
              :show-icon="false"
            >
              旧版 Office 文件依赖外部预览服务；若无法加载，可直接下载查看。
            </NAlert>
          </div>
          <video
            v-else-if="getPreviewKind(resource) === 'video'"
            class="preview-media"
            :src="previewUrl"
            controls
            preload="metadata"
          />
          <audio
            v-else-if="getPreviewKind(resource) === 'audio'"
            class="preview-audio"
            :src="previewUrl"
            controls
            preload="metadata"
          />
          <NAlert
            v-else
            type="info"
            :show-icon="false"
          >
            当前格式暂不支持在线预览，可直接下载查看。
          </NAlert>
        </section>

        <NButton
          type="primary"
          block
          class="download-btn"
          @click="handleDownload"
        >
          下载文件
        </NButton>
      </NCard>
    </template>

    <template v-else>
      <div class="empty">
        <NEmpty description="资源不存在" />
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.review-banner {
  margin-bottom: 16px;
}

.detail-page {
  min-height: calc(100vh - 112px);
  padding: 8px 0 40px;
  display: flex;
  align-items: flex-start;
  justify-content: center;
}

.resource-info {
  width: min(100%, 880px);
  background: var(--cf-card-bg);
  border: 1px solid var(--cf-card-border);
  border-radius: 20px;
  box-shadow: var(--cf-card-shadow);
  backdrop-filter: blur(24px) saturate(150%);

  :deep(.n-card__content) {
    padding: 26px;
  }
}

.loading,
.empty {
  width: min(100%, 880px);
  min-height: 360px;
  padding: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--cf-card-bg);
  border: 1px solid var(--cf-card-border);
  border-radius: 20px;
}

.info-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
  margin-bottom: 18px;
}

.info-header h2 {
  margin: 0 0 10px;
  font-size: 28px;
  line-height: 1.25;
  word-break: break-word;
}

.header-actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.resource-desc {
  margin: 0 0 22px;
  color: var(--cf-text-secondary);
  font-size: 15px;
  line-height: 1.8;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px;
  border: 1px solid var(--cf-border);
  border-radius: 16px;
  background: color-mix(in srgb, var(--cf-primary) 5%, var(--cf-bg-card));
  font-size: 15px;
  font-weight: 850;
}

.info-label {
  font-size: 12px;
  color: var(--cf-text-muted);
  font-weight: 700;
}

.info-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 22px;
}

.preview-section {
  margin: 22px 0;
}

.preview-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  font-weight: 800;
}

.preview-state {
  padding: 40px;
  text-align: center;
  border: 1px solid var(--cf-border);
  border-radius: 10px;
  background: var(--cf-bg-soft);
}

.preview-frame,
.markdown-frame {
  width: 100%;
  height: min(64vh, 720px);
  border: 1px solid var(--cf-border);
  border-radius: 10px;
  background: var(--cf-bg-base);
}

.preview-image {
  display: block;
  max-width: 100%;
  max-height: 64vh;
  margin: 0 auto;
  border-radius: 10px;
  object-fit: contain;
}

.preview-media {
  width: 100%;
  max-height: 64vh;
  border: 1px solid var(--cf-border);
  border-radius: 10px;
  background: #000;
}

.preview-audio {
  width: 100%;
  min-height: 48px;
}

.office-preview {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.download-btn {
  height: 46px;
  margin-top: 4px;
  border-radius: 12px;
  font-weight: 900;
}

@media (max-width: 760px) {
  .detail-page {
    padding-bottom: 24px;
  }

  .info-header {
    flex-direction: column;
  }

  .info-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
