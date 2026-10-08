<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  NAlert,
  NButton,
  NDynamicTags,
  NInput,
  NModal,
  NSelect,
  NSpin,
  NTag,
  NUpload,
  useMessage,
  type UploadFileInfo,
} from 'naive-ui';
import {
  ArchiveOutline,
  BookOutline,
  BookmarkOutline,
  CloudUploadOutline,
  CodeSlashOutline,
  DocumentTextOutline,
  DownloadOutline,
  EllipsisHorizontalOutline,
  EyeOutline,
  FolderOutline,
  GridOutline,
  ImageOutline,
  ListOutline,
  MusicalNotesOutline,
  PlayCircleOutline,
  StarOutline,
  TimeOutline,
  TrashOutline,
} from '@vicons/ionicons5';
import {
  deleteResource,
  getDownloadUrl,
  getMyResources,
  getOfficePreviewUrl,
  getPreviewUrl,
  getResourceById,
  getResourcePreviewText,
  getResources,
  resourceAccept,
  uploadResource,
} from '@/api/resources';
import { useAuthStore } from '@/stores/auth';
import { useTheme } from '@/composables/useTheme';
import type { ResourcePreviewVO, ResourceVO } from '@/types/resource';
import { ALL_RESOURCE_TOPIC, buildResourceTopicOptions, getResourceTopics } from '@/utils/resource-topic';
import { getResourcePreviewKind, normalizeResourceType } from '@/utils/resource-preview';

type ResourceFilter = 'all' | 'doc' | 'video' | 'audio' | 'image' | 'archive' | 'other';
type SortMode = 'mixed' | 'time' | 'type' | 'size' | 'download';

const message = useMessage();
const authStore = useAuthStore();
const { isDarkTheme } = useTheme();

const resources = ref<ResourceVO[]>([]);
const loading = ref(false);
const activeFilter = ref<ResourceFilter>('all');
const activeTopic = ref(ALL_RESOURCE_TOPIC);
const gridMode = ref<'grid' | 'list'>('grid');
const sortMode = ref<SortMode>('mixed');

const detailVisible = ref(false);
const detailLoading = ref(false);
const selectedResource = ref<ResourceVO | null>(null);
const previewLoading = ref(false);
const previewText = ref<ResourcePreviewVO | null>(null);
const previewError = ref('');
const previewUrl = ref('');

const uploadVisible = ref(false);
const uploadFileList = ref<UploadFileInfo[]>([]);
const uploadFile = ref<File | null>(null);
const uploadDescription = ref('');
const uploadVisibility = ref('PUBLIC');
const uploadTags = ref<string[]>([]);
const uploadLoading = ref(false);

const activePersonalView = ref<'all' | 'upload' | 'collect' | 'recent' | 'download'>('all');
/** 我的上传视图数据（含待审核/已驳回，与公开列表分开存放）。 */
const mineResources = ref<ResourceVO[]>([]);
const mineLoading = ref(false);


const filters: Array<{ key: ResourceFilter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'doc', label: '文档' },
  { key: 'video', label: '视频' },
  { key: 'audio', label: '音频' },
  { key: 'image', label: '图片' },
  { key: 'archive', label: '压缩包' },
  { key: 'other', label: '其他' },
];

const TOPIC_ICON_MAP: Record<string, any> = {
  课程资料: BookOutline,
  考研考证: BookmarkOutline,
  编程技术: CodeSlashOutline,
  设计创意: ImageOutline,
  语言学习: MusicalNotesOutline,
  职业技能: StarOutline,
  考试题库: DocumentTextOutline,
  电子书籍: BookOutline,
  学习笔记: DocumentTextOutline,
  实用工具: ArchiveOutline,
  其他资源: FolderOutline,
  未分类: FolderOutline,
};

const topicOptions = computed(() => {
  const rawItems = resources.value.length
    ? buildResourceTopicOptions(resources.value)
    : [];
  return rawItems.map((item) => ({
    label: item.label,
    count: compactCount(item.count),
    icon: TOPIC_ICON_MAP[item.label] || FolderOutline,
  }));
});

const hotTags = computed(() => {
  if (!resources.value.length) return [] as string[];
  const tagCounts = new Map<string, number>();
  for (const r of resources.value) {
    for (const tag of r.tags || []) {
      const t = tag.trim();
      if (t) tagCounts.set(t, (tagCounts.get(t) || 0) + 1);
    }
  }
  return [...tagCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([tag]) => tag);
});

const visibilityOptions = [
  { label: '公开（所有人可见）', value: 'PUBLIC' },
  { label: '空间内可见', value: 'SPACE' },
  { label: '仅自己可见', value: 'PRIVATE' },
];


const visibleResources = computed(() => {
  // 我的上传视图走 /resources/mine（含待审核/已驳回），其余视图走公开列表
  const source = activePersonalView.value === 'upload' ? mineResources.value : resources.value;
  const filtered = source.filter((item) => {
    const typeMatch = activeFilter.value === 'all' || filterForType(item.fileType) === activeFilter.value;
    const topics = getResourceTopics(item);
    const topicMatch = activeTopic.value === ALL_RESOURCE_TOPIC || topics.includes(activeTopic.value);
    return typeMatch && topicMatch;
  });
  return sortResources(filtered);
});

const resourceStatusBadge: Record<number, { label: string; kind: 'warning' | 'error' }> = {
  2: { label: '审核中', kind: 'warning' },
  3: { label: '已驳回', kind: 'error' },
  0: { label: '已隐藏', kind: 'error' },
};

const rankingResources = computed(() =>
  [...resources.value]
    .sort((a, b) => b.downloadCount - a.downloadCount)
    .slice(0, 5),
);

const recentResources = computed(() => [...resources.value].slice(0, 4));
const currentUserId = computed(() => authStore.user?.id);
const isUploader = computed(() => selectedResource.value?.uploaderId === currentUserId.value);

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
  try {
    resources.value = await getResources({ limit: 30 });
  } catch {
    resources.value = [];
  } finally {
    loading.value = false;
  }
}

async function openDetail(resource: ResourceVO) {
  selectedResource.value = resource;
  detailVisible.value = true;
  detailLoading.value = true;
  previewText.value = null;
  previewError.value = '';
  previewUrl.value = '';
  previewLoading.value = false;

  try {
    selectedResource.value = await getResourceById(resource.id);
    await loadPreview();
  } catch {
    message.error('资源详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

async function loadPreview() {
  if (!selectedResource.value) return;
  previewText.value = null;
  previewError.value = '';
  previewUrl.value = '';

  const kind = getPreviewKind(selectedResource.value);
  if (kind === 'text') {
    previewLoading.value = true;
    try {
      previewText.value = await getResourcePreviewText(selectedResource.value.id);
    } catch {
      previewError.value = '预览内容加载失败';
    } finally {
      previewLoading.value = false;
    }
  } else if (kind === 'office') {
    previewLoading.value = true;
    try {
      previewUrl.value = await getOfficePreviewUrl(selectedResource.value.id);
    } catch {
      previewError.value = 'Office 预览服务链接加载失败，可先下载查看';
    } finally {
      previewLoading.value = false;
    }
  } else if (['pdf', 'image', 'video', 'audio'].includes(kind)) {
    previewLoading.value = true;
    try {
      previewUrl.value = await getPreviewUrl(selectedResource.value.id);
    } catch {
      previewError.value = '预览链接加载失败';
    } finally {
      previewLoading.value = false;
    }
  }
}

function openUpload() {
  uploadFileList.value = [];
  uploadFile.value = null;
  uploadDescription.value = '';
  uploadVisibility.value = 'PUBLIC';
  uploadTags.value = [];
  uploadVisible.value = true;
}

function scrollResourcesTop() {
  document.querySelector('.resources-main')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function applyTopic(topic: string) {
  activeTopic.value = topic;
  activeFilter.value = 'all';
  activePersonalView.value = 'all'; // Reset personal view
  scrollResourcesTop();
}

function applyPersonalView(view: 'upload' | 'collect' | 'recent' | 'download') {
  const modeMap = {
    upload: 'time',
    collect: 'mixed',
    recent: 'time',
    download: 'download',
  } as const;
  activePersonalView.value = view;
  activeTopic.value = ALL_RESOURCE_TOPIC;
  activeFilter.value = 'all';
  sortMode.value = modeMap[view];
  if (view === 'upload') void loadMine();
  scrollResourcesTop();
  message.info(`已切换至【${view === 'upload' ? '我的上传' : view === 'collect' ? '我的收藏' : view === 'recent' ? '最近查看' : '下载记录'}】视图`);
}

async function loadMine() {
  if (!authStore.user) {
    message.warning('登录后可查看我的上传');
    return;
  }
  mineLoading.value = true;
  try {
    mineResources.value = await getMyResources({ limit: 50 });
  } catch {
    mineResources.value = [];
  } finally {
    mineLoading.value = false;
  }
}

function applySort(mode: SortMode) {
  sortMode.value = mode;
  scrollResourcesTop();
}

function showRankingAll() {
  activeFilter.value = 'all';
  activeTopic.value = ALL_RESOURCE_TOPIC;
  sortMode.value = 'download';
  scrollResourcesTop();
  message.info('已按下载量展示资源');
}

function showRecentAll() {
  activeFilter.value = 'all';
  activeTopic.value = ALL_RESOURCE_TOPIC;
  sortMode.value = 'time';
  scrollResourcesTop();
  message.info('已按最近更新展示资源');
}

function sortResources(list: ResourceVO[]) {
  const sorted = [...list];
  if (sortMode.value === 'time') {
    return sorted.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  }
  if (sortMode.value === 'type') {
    return sorted.sort((a, b) => normalizeType(a.fileType).localeCompare(normalizeType(b.fileType)));
  }
  if (sortMode.value === 'size') {
    return sorted.sort((a, b) => b.fileSize - a.fileSize);
  }
  if (sortMode.value === 'download') {
    return sorted.sort((a, b) => b.downloadCount - a.downloadCount);
  }
  return sorted;
}

function handleFileChange(fileList: UploadFileInfo[]) {
  uploadFileList.value = fileList;
  uploadFile.value = fileList[0]?.file || null;
}

async function submitUpload() {
  if (!uploadFile.value) {
    message.warning('请选择文件');
    return;
  }
  const tags = normalizeTags(uploadTags.value);
  if (!tags.length) {
    message.warning('请至少填写一个资源标签');
    return;
  }

  uploadLoading.value = true;
  try {
    const resource = await uploadResource(uploadFile.value, {
      visibility: uploadVisibility.value,
      tags,
      description: uploadDescription.value.trim() || undefined,
    });
    if (resource.status === 2) {
      // 待审核资源不混入公开列表（刷新即消失造成困惑），归入"我的上传"视图
      mineResources.value = [resource, ...mineResources.value.filter((item) => item.id !== resource.id)];
      message.success('上传成功，已提交审核，可在【我的上传】中查看进度');
    } else {
      resources.value = [resource, ...resources.value.filter((item) => item.id !== resource.id)];
      message.success('上传成功');
    }
    uploadVisible.value = false;
    await openDetail(resource);
  } catch {
    message.error('上传失败');
  } finally {
    uploadLoading.value = false;
  }
}

function normalizeTags(values: string[]) {
  return [...new Set(values.flatMap((value) => value.split(/[\s,，#]+/)).map((tag) => tag.trim()).filter(Boolean))].slice(0, 8);
}

function handleUploadTagsUpdate(value: string[]) {
  uploadTags.value = normalizeTags(value);
}

async function handleDownload(resource = selectedResource.value) {
  if (!resource) return;
  try {
    const url = await getDownloadUrl(resource.id);
    window.open(url, '_blank');
    setTimeout(() => refreshResource(resource.id), 1000);
  } catch {
    message.error('下载链接获取失败');
  }
}

async function refreshResource(id: number) {
  try {
    const latest = await getResourceById(id);
    resources.value = resources.value.map((item) => (item.id === id ? latest : item));
    mineResources.value = mineResources.value.map((item) => (item.id === id ? latest : item));
    if (selectedResource.value?.id === id) selectedResource.value = latest;
  } catch {
    // Download count refresh is non-critical.
  }
}

async function handleDelete() {
  if (!selectedResource.value) return;
  try {
    await deleteResource(selectedResource.value.id);
    resources.value = resources.value.filter((item) => item.id !== selectedResource.value?.id);
    mineResources.value = mineResources.value.filter((item) => item.id !== selectedResource.value?.id);
    detailVisible.value = false;
    message.success('资源已删除');
  } catch {
    message.error('删除失败');
  }
}

function filterForType(fileType: string | null | undefined): ResourceFilter {
  const type = normalizeType(fileType);
  if (['pdf', 'doc', 'docx', 'md', 'markdown', 'ppt', 'pptx', 'xls', 'xlsx', 'txt', 'log', 'csv', 'json', 'xml', 'yml', 'yaml', 'sql', 'java', 'py', 'js', 'jsx', 'ts', 'tsx', 'vue', 'css', 'scss', 'html', 'htm', 'folder'].includes(type)) return 'doc';
  if (['mp4', 'webm', 'mov', 'avi'].includes(type)) return 'video';
  if (['mp3', 'wav', 'm4a', 'ogg'].includes(type)) return 'audio';
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(type)) return 'image';
  if (['zip', 'rar', '7z'].includes(type)) return 'archive';
  return 'other';
}

function getPreviewKind(resource: ResourceVO) {
  return getResourcePreviewKind(resource);
}

function normalizeType(fileType: string | null | undefined) {
  return normalizeResourceType(fileType);
}

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function compactCount(value: number) {
  if (value >= 1000) return `${(value / 1000).toFixed(value >= 10000 ? 1 : 1)}k`;
  return String(value);
}

function formatDate(value: string) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value.slice(0, 10);
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
}

function iconFor(resource: ResourceVO) {
  const type = normalizeType(resource.fileType);
  if (type === 'folder') return FolderOutline;
  if (['mp4', 'webm', 'mov', 'avi'].includes(type)) return PlayCircleOutline;
  if (['mp3', 'wav', 'm4a', 'ogg'].includes(type)) return MusicalNotesOutline;
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(type)) return ImageOutline;
  if (['zip', 'rar', '7z'].includes(type)) return ArchiveOutline;
  if (['md', 'markdown', 'txt', 'log', 'csv', 'json', 'xml', 'yml', 'yaml', 'sql', 'java', 'py', 'js', 'jsx', 'ts', 'tsx', 'vue', 'css', 'scss', 'html', 'htm'].includes(type)) return CodeSlashOutline;
  return DocumentTextOutline;
}

function tileClass(resource: ResourceVO) {
  const type = normalizeType(resource.fileType);
  if (type === 'folder') return 'folder';
  if (['mp4', 'webm', 'mov', 'avi'].includes(type)) return 'video';
  if (['mp3', 'wav', 'm4a', 'ogg'].includes(type)) return 'audio';
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(type)) return 'image';
  if (['zip', 'rar', '7z'].includes(type)) return 'archive';
  if (['xls', 'xlsx'].includes(type)) return 'excel';
  if (['ppt', 'pptx'].includes(type)) return 'ppt';
  if (['pdf'].includes(type)) return 'pdf';
  if (['md', 'markdown', 'txt', 'log', 'csv', 'json', 'xml', 'yml', 'yaml', 'sql', 'java', 'py', 'js', 'jsx', 'ts', 'tsx', 'vue', 'css', 'scss', 'html', 'htm'].includes(type)) return 'code';
  return 'doc';
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
  <div class="resources-page">
    <aside class="resources-left">
      <section class="apple-card nav-card">
        <h2>资源</h2>
        <button class="nav-primary" :class="{ active: activeTopic === ALL_RESOURCE_TOPIC && activePersonalView === 'all' }" @click="applyTopic(ALL_RESOURCE_TOPIC)">
          <n-icon size="18"><FolderOutline /></n-icon>
          全部资源
        </button>
        <div class="nav-divider" />
        <p>资源分类</p>
        <button v-for="item in topicOptions" :key="item.label" class="category-row" :class="{ active: activeTopic === item.label && activePersonalView === 'all' }" @click="applyTopic(item.label)">
          <n-icon size="17"><component :is="item.icon" /></n-icon>
          <span>{{ item.label }}</span>
          <strong>{{ item.count }}</strong>
        </button>
        <div class="nav-divider" />
        <p>我的资源</p>
        <button class="category-row" :class="{ active: activePersonalView === 'upload' }" @click="applyPersonalView('upload')"><n-icon size="17"><CloudUploadOutline /></n-icon><span>我的上传</span></button>
        <button class="category-row" :class="{ active: activePersonalView === 'collect' }" @click="applyPersonalView('collect')"><n-icon size="17"><StarOutline /></n-icon><span>我的收藏</span></button>
        <button class="category-row" :class="{ active: activePersonalView === 'recent' }" @click="applyPersonalView('recent')"><n-icon size="17"><TimeOutline /></n-icon><span>最近查看</span></button>
        <button class="category-row" :class="{ active: activePersonalView === 'download' }" @click="applyPersonalView('download')"><n-icon size="17"><DownloadOutline /></n-icon><span>下载记录</span></button>
      </section>

    </aside>

    <main class="resources-main">
      <header class="main-head">
        <div>
          <h1>全部资源</h1>
          <p>海量优质学习资源，免费下载</p>
        </div>
      </header>

      <div class="type-tabs">
        <button v-for="filter in filters" :key="filter.key" :class="{ active: activeFilter === filter.key }" @click="activeFilter = filter.key">
          {{ filter.label }}
        </button>
      </div>

      <div class="filter-row">
        <button :class="{ active: sortMode === 'mixed' }" @click="applySort('mixed')">综合排序</button>
        <button :class="{ active: sortMode === 'time' }" @click="applySort('time')">上传时间</button>
        <button :class="{ active: sortMode === 'type' }" @click="applySort('type')">格式</button>
        <button :class="{ active: sortMode === 'size' }" @click="applySort('size')">大小</button>
        <button @click="message.info('已显示全部可见资源')">全部权限</button>
        <div class="view-toggle">
          <button :class="{ active: gridMode === 'grid' }" @click="gridMode = 'grid'"><n-icon size="18"><GridOutline /></n-icon></button>
          <button :class="{ active: gridMode === 'list' }" @click="gridMode = 'list'"><n-icon size="18"><ListOutline /></n-icon></button>
        </div>
      </div>

      <div v-if="loading || (activePersonalView === 'upload' && mineLoading)" class="loading-state"><n-spin size="large" /></div>

      <section v-else class="resource-grid" :class="{ list: gridMode === 'list' }">
        <article v-for="resource in visibleResources" :key="resource.id" class="resource-card" @click="openDetail(resource)">
          <div class="file-art" :class="tileClass(resource)">
            <n-icon size="62"><component :is="iconFor(resource)" /></n-icon>
            <small v-if="filterForType(resource.fileType) === 'video'">12:45</small>
            <small v-if="filterForType(resource.fileType) === 'audio'">03:45</small>
            <span
              v-if="resource.status != null && resource.status !== 1 && resourceStatusBadge[resource.status]"
              class="status-badge"
              :class="resourceStatusBadge[resource.status].kind"
            >{{ resourceStatusBadge[resource.status].label }}</span>
          </div>
          <h2>{{ resource.fileName }}</h2>
          <p class="resource-topic">
            <n-icon size="13"><FolderOutline /></n-icon>
            {{ getResourceTopics(resource)[0] || '其他资源' }}
          </p>
          <div class="author-row">
            <span>{{ resource.uploader?.nickname || '未知上传者' }}</span>
            <time>{{ formatDate(resource.createdAt) }}</time>
          </div>
          <footer>
            <span><n-icon size="16"><EyeOutline /></n-icon>{{ compactCount(resource.collectCount) }}</span>
            <span><n-icon size="16"><DownloadOutline /></n-icon>{{ compactCount(resource.downloadCount) }}</span>
            <button @click.stop="openDetail(resource)"><n-icon size="17"><EllipsisHorizontalOutline /></n-icon></button>
          </footer>
        </article>
      </section>
    </main>

    <aside class="resources-right">
      <section class="upload-card apple-card">
        <n-icon size="64"><CloudUploadOutline /></n-icon>
        <h3>上传资源</h3>
        <p>分享你的学习资源，帮助更多同学</p>
        <button @click="openUpload">立即上传</button>
      </section>

      <section class="apple-card side-card">
        <div class="side-title"><h3>资源排行榜</h3><button @click="showRankingAll">查看全部 <n-icon size="12"><DownloadOutline /></n-icon></button></div>
        <div v-for="(resource, index) in rankingResources" :key="resource.id" class="rank-row">
          <span :class="{ podium: index < 3 }">{{ index + 1 }}</span>
          <div><strong>{{ resource.fileName }}</strong><p>{{ resource.uploader?.nickname || '未知上传者' }}</p></div>
          <em>下载 {{ compactCount(resource.downloadCount) }}</em>
        </div>
      </section>

      <section class="apple-card side-card">
        <h3>热门标签</h3>
        <div class="tag-cloud">
          <button v-for="tag in hotTags" :key="tag" @click="applyTopic(tag)">{{ tag }}</button>
        </div>
      </section>

      <section class="apple-card side-card">
        <div class="side-title"><h3>最近更新</h3><button @click="showRecentAll">查看全部</button></div>
        <a v-for="item in recentResources" :key="item.id" @click="openDetail(item)">
          <n-icon size="14"><DocumentTextOutline /></n-icon>
          <span>{{ item.fileName }}</span>
          <time>{{ formatDate(item.createdAt) }}</time>
        </a>
      </section>
    </aside>

    <NModal v-model:show="detailVisible" preset="card" class="resource-modal" :title="selectedResource?.fileName || '资源详情'" :bordered="false">
      <div v-if="detailLoading" class="modal-loading"><n-spin /></div>
      <template v-else-if="selectedResource">
        <NAlert
          v-if="selectedResource.status === 2"
          type="warning"
          :show-icon="true"
          style="margin-bottom: 12px;"
        >正在等待管理员审核，通过后对其他用户可见。</NAlert>
        <NAlert
          v-else-if="selectedResource.status === 3"
          type="error"
          :show-icon="true"
          style="margin-bottom: 12px;"
        >未通过审核{{ selectedResource.reviewReason ? `：${selectedResource.reviewReason}` : '' }}。可删除后修改重新上传。</NAlert>
        <div class="detail-meta">
          <div class="detail-tags">
            <NTag size="small">{{ selectedResource.fileType ? selectedResource.fileType.toUpperCase() : '未知' }}</NTag>
            <NTag type="success" size="small">{{ selectedResource.visibility === 'PUBLIC' ? '公开' : selectedResource.visibility }}</NTag>
          </div>
          <div class="modal-actions">
            <NButton secondary @click="handleDownload()"><template #icon><DownloadOutline /></template>下载</NButton>
            <NButton v-if="isUploader" secondary type="error" @click="handleDelete"><template #icon><TrashOutline /></template>删除</NButton>
          </div>
        </div>
        <p v-if="selectedResource.description" class="resource-desc">{{ selectedResource.description }}</p>
        <div class="info-grid">
          <div><span>大小</span><strong>{{ formatSize(selectedResource.fileSize) }}</strong></div>
          <div><span>下载</span><strong>{{ selectedResource.downloadCount }} 次</strong></div>
          <div><span>上传者</span><strong>{{ selectedResource.uploader?.nickname || '未知' }}</strong></div>
          <div><span>上传时间</span><strong>{{ formatDate(selectedResource.createdAt) }}</strong></div>
        </div>
        <section class="preview-section">
          <div class="preview-title">
            <span>文件预览</span>
            <NButton quaternary size="small" @click="loadPreview"><template #icon><EyeOutline /></template>刷新预览</NButton>
          </div>
          <div v-if="previewLoading" class="preview-state"><n-spin /></div>
          <NAlert v-else-if="previewError" type="error" :show-icon="false">{{ previewError }}</NAlert>
          <iframe v-else-if="getPreviewKind(selectedResource) === 'pdf'" class="preview-frame" :src="previewUrl" title="PDF 预览" />
          <img v-else-if="getPreviewKind(selectedResource) === 'image'" class="preview-image" :src="previewUrl" :alt="selectedResource.fileName" />
          <iframe v-else-if="getPreviewKind(selectedResource) === 'text' && previewText" class="markdown-frame" :srcdoc="markdownSrcdoc" title="文本预览" />
          <div v-else-if="getPreviewKind(selectedResource) === 'office' && previewUrl" class="office-preview">
            <iframe class="preview-frame" :src="previewUrl" title="Office 预览" />
            <NAlert type="info" :show-icon="false">旧版 Office 文件依赖外部预览服务；若无法加载，可直接下载查看。</NAlert>
          </div>
          <video v-else-if="getPreviewKind(selectedResource) === 'video'" class="preview-media" :src="previewUrl" controls preload="metadata" />
          <audio v-else-if="getPreviewKind(selectedResource) === 'audio'" class="preview-audio" :src="previewUrl" controls preload="metadata" />
          <NAlert v-else type="info" :show-icon="false">当前格式暂不支持在线预览，可直接下载查看。</NAlert>
        </section>
      </template>
    </NModal>

    <NModal v-model:show="uploadVisible" preset="card" class="upload-modal" title="上传资源" :bordered="false">
      <div class="upload-form">
        <label>选择文件（最大 200MB，支持任意类型；上传后需管理员审核）</label>
        <NUpload :max="1" :default-upload="false" :file-list="uploadFileList" :accept="resourceAccept || undefined" @update:file-list="handleFileChange">
          <NButton><template #icon><CloudUploadOutline /></template>选择文件</NButton>
        </NUpload>
        <div v-if="uploadFile" class="selected-file">已选：{{ uploadFile.name }}（{{ formatSize(uploadFile.size) }}）</div>
        <label>可见性</label>
        <NSelect v-model:value="uploadVisibility" :options="visibilityOptions" />
        <label>标签 / 主题</label>
        <NDynamicTags :value="uploadTags" :max="8" type="info" round :input-props="{ placeholder: '输入标签后回车' }" @update:value="handleUploadTagsUpdate" />
        <label>描述</label>
        <NInput v-model:value="uploadDescription" type="textarea" placeholder="简单描述资源内容..." maxlength="500" />
        <div class="actions">
          <NButton type="primary" :loading="uploadLoading" @click="submitUpload">上传</NButton>
          <NButton @click="uploadVisible = false">取消</NButton>
        </div>
      </div>
    </NModal>

  </div>
</template>

<style scoped>
/* ===== Layout ===== */
.resources-page {
  min-height: calc(100vh - 112px);
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr) 340px;
  gap: 28px;
  padding: 8px 0 40px;
  color: var(--cf-text-primary);
  background: var(--cf-page-bg);
}

.resources-left,
.resources-right {
  position: sticky;
  top: 8px;
  align-self: start;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.resources-main {
  min-width: 0;
}

/* ===== Cards ===== */
.apple-card,
.resource-card {
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.03);
  transition: all 0.2s;
}

.nav-card,
.side-card,
.upload-card {
  padding: 18px;
}

/* ===== Left nav ===== */
.nav-card h2 {
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.8);
}

.nav-card p {
  margin: 14px 0 8px;
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
  font-weight: 600;
}

.nav-primary,
.category-row {
  width: 100%;
  padding: 9px 12px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  margin-bottom: 2px;
  transition: all 0.15s;
}

.nav-primary:hover,
.category-row:hover,
.nav-primary.active,
.category-row.active {
  background: rgba(52, 208, 188, 0.06);
  color: rgb(52, 208, 188);
}

.category-row strong {
  margin-left: auto;
  color: rgba(0, 0, 0, 0.3);
  font-weight: 500;
  font-size: 12px;
}

.category-row.active strong,
.category-row:hover strong {
  color: rgb(52, 208, 188);
}

.nav-divider {
  height: 1px;
  margin: 10px 0;
  background: rgba(0, 0, 0, 0.05);
}

/* ===== Main head ===== */
.main-head {
  margin: 18px 0 22px;
}

.main-head h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}

.main-head p {
  margin: 8px 0 0;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.45);
}

/* ===== Type tabs (main) ===== */
.type-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.type-tabs button {
  padding: 9px 22px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: rgba(0, 0, 0, 0.5);
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s;
}

.type-tabs button:hover {
  background: rgba(52, 208, 188, 0.06);
  color: rgb(52, 208, 188);
}

.type-tabs button.active {
  background: rgb(52, 208, 188);
  color: #fff;
}

/* ===== Filter row (chips) ===== */
.filter-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 22px;
  flex-wrap: wrap;
}

.filter-row > button {
  padding: 6px 14px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 20px;
  background: transparent;
  color: rgba(0, 0, 0, 0.5);
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}

.filter-row > button:hover,
.filter-row > button.active {
  background: rgba(52, 208, 188, 0.08);
  color: rgb(52, 208, 188);
  border-color: rgba(52, 208, 188, 0.3);
}

.view-toggle {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.view-toggle button {
  width: 34px;
  height: 30px;
  padding: 0;
  display: inline-grid;
  place-items: center;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  background: transparent;
  color: rgba(0, 0, 0, 0.5);
  cursor: pointer;
  transition: all 0.15s;
}

.view-toggle button:hover,
.view-toggle button.active {
  background: rgba(52, 208, 188, 0.08);
  color: rgb(52, 208, 188);
  border-color: rgba(52, 208, 188, 0.3);
}

/* ===== Resource grid ===== */
.resource-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  gap: 16px;
}

.resource-grid.list {
  grid-template-columns: 1fr;
}

.resource-card {
  padding: 16px;
  cursor: pointer;
}

.resource-card:hover {
  transform: translateY(-2px);
  border-color: rgba(52, 208, 188, 0.2);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.06);
}

.file-art {
  height: 72px;
  margin-bottom: 12px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  position: relative;
  color: #fff;
}

.file-art small {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.7);
  font-size: 10px;
  font-weight: 500;
}

.status-badge {
  position: absolute;
  left: 6px;
  top: 6px;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 800;
  color: #fff;
}

.status-badge.warning { background: rgba(245, 158, 11, 0.92); }
.status-badge.error { background: rgba(239, 68, 68, 0.92); }

/* Type color palette (desaturated) */
.folder { color: #4d8ff7; background: linear-gradient(135deg, #eef5ff, #dbeaff); }
.video { background: linear-gradient(135deg, #a599e6, #7f6ed4); }
.audio { color: #4c8fff; background: linear-gradient(135deg, #e9f2ff, #d6e6ff); }
.image { background: linear-gradient(135deg, #9dc9ff, #f6a55f); }
.archive { background: linear-gradient(135deg, #ffcb6a, #f59e0b); }
.excel { background: linear-gradient(135deg, #34c070, #16a34a); }
.ppt { background: linear-gradient(135deg, #ff8b6b, #ff6b45); }
.pdf { background: linear-gradient(135deg, #ff7060, #ff514b); }
.code { color: #6d72d8; background: linear-gradient(135deg, #eaecff, #d9ddff); }
.doc { background: linear-gradient(135deg, #61a6ff, #2b75d6); }

.resource-card h2 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.resource-topic {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 0 8px;
  font-size: 12px;
  color: rgb(52, 208, 188);
}

.author-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: rgba(0, 0, 0, 0.4);
}

.resource-card footer {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid rgba(0, 0, 0, 0.04);
  font-size: 12px;
  color: rgba(0, 0, 0, 0.4);
}

.resource-card footer span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.resource-card footer button {
  margin-left: auto;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(0, 0, 0, 0.3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.resource-card footer button:hover {
  background: rgba(52, 208, 188, 0.08);
  color: rgb(52, 208, 188);
}

/* ===== Upload card (right sidebar) ===== */
.upload-card {
  padding: 28px 20px;
  text-align: center;
}

.upload-card :deep(.n-icon) {
  color: rgb(52, 208, 188);
}

.upload-card h3 {
  margin: 10px 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}

.upload-card p {
  margin: 0 0 16px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.45);
}

.upload-card button {
  width: auto;
  height: auto;
  padding: 9px 24px;
  border: none;
  border-radius: 999px;
  background: rgb(52, 208, 188);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}

.upload-card button:hover {
  background: rgb(38, 190, 170);
}

/* ===== Side cards ===== */
.side-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.side-title h3,
.side-card h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.7);
}

.side-title button {
  padding: 0;
  border: none;
  background: transparent;
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-family: inherit;
  font-weight: 500;
}

.side-title button:hover {
  color: rgb(52, 208, 188);
}

.rank-row {
  min-height: 48px;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 72px;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
}

.rank-row > span {
  font-weight: 700;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.4);
  text-align: center;
}

.rank-row > span.podium {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 11px;
  background: linear-gradient(145deg, rgb(52, 208, 188), rgb(38, 178, 155));
}

.rank-row strong,
.rank-row p,
.rank-row em,
.side-card a span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-row strong {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.8);
}

.rank-row p,
.rank-row em {
  margin: 2px 0 0;
  color: rgba(0, 0, 0, 0.35);
  font-size: 11px;
  font-style: normal;
}

.rank-row em {
  text-align: right;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.tag-cloud button {
  padding: 5px 12px;
  border: none;
  border-radius: 999px;
  background: rgba(52, 208, 188, 0.08);
  color: rgb(52, 208, 188);
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s;
}

.tag-cloud button:hover {
  background: rgba(52, 208, 188, 0.15);
}

.side-card a {
  min-height: 32px;
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr) 40px;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  color: rgba(0, 0, 0, 0.6);
  font-size: 13px;
  cursor: pointer;
  transition: color 0.15s;
}

.side-card a time {
  color: rgba(0, 0, 0, 0.3);
  font-size: 11px;
  text-align: right;
}

.side-card a:hover {
  color: rgb(52, 208, 188);
}

/* ===== States ===== */
.loading-state,
.modal-loading,
.preview-state {
  padding: 40px;
  text-align: center;
  color: rgba(0, 0, 0, 0.35);
}

/* ===== Modal (detail) ===== */
.detail-meta {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.detail-tags,
.modal-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.resource-desc {
  color: rgba(0, 0, 0, 0.6);
  line-height: 1.7;
  font-size: 14px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 16px 0;
}

.info-grid div {
  padding: 12px;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.02);
}

.info-grid span {
  display: block;
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
}

.info-grid strong {
  display: block;
  margin-top: 4px;
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.7);
}

.preview-frame,
.markdown-frame {
  width: 100%;
  height: min(64vh, 720px);
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 12px;
  background: #fff;
}

.preview-image {
  max-width: 100%;
  max-height: 64vh;
  margin: 0 auto;
  border-radius: 12px;
  object-fit: contain;
}

.preview-media {
  width: 100%;
  max-height: 64vh;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 12px;
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

.upload-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.upload-form label {
  color: rgba(0, 0, 0, 0.6);
  font-size: 13px;
  font-weight: 600;
}

.selected-file {
  color: rgb(52, 208, 188);
  font-size: 13px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}

:global(.resource-modal.n-card) {
  width: min(920px, calc(100vw - 32px));
}

:global(.upload-modal.n-card) {
  width: min(560px, calc(100vw - 32px));
}

/* ===== Responsive ===== */
@media (max-width: 1320px) {
  .resources-page {
    grid-template-columns: 230px minmax(0, 1fr) 300px;
    gap: 20px;
  }
  .resource-grid {
    grid-template-columns: repeat(3, minmax(180px, 1fr));
  }
}

@media (max-width: 1080px) {
  .resources-page {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .resources-right {
    display: none;
  }
}

@media (max-width: 760px) {
  .resources-page {
    display: flex;
    flex-direction: column;
  }
  .resources-left,
  .resources-right {
    position: static;
  }
  .resource-grid {
    grid-template-columns: 1fr;
  }
  .info-grid {
    grid-template-columns: 1fr;
  }
  .type-tabs {
    overflow-x: auto;
    scrollbar-width: none;
    flex-wrap: nowrap;
  }
  .type-tabs::-webkit-scrollbar { display: none; }
  .type-tabs button { white-space: nowrap; }
}

/* ===== Dark mode ===== */
html[data-theme='dark'] .apple-card,
html[data-theme='dark'] .resource-card {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-card-border, rgba(255, 255, 255, 0.07));
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
}
html[data-theme='dark'] .resource-card:hover {
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
  border-color: rgba(52, 208, 188, 0.3);
}
html[data-theme='dark'] .nav-card h2,
html[data-theme='dark'] .main-head h1,
html[data-theme='dark'] .upload-card h3,
html[data-theme='dark'] .rank-row strong,
html[data-theme='dark'] .info-grid strong,
html[data-theme='dark'] .resource-card h2 {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .nav-card p,
html[data-theme='dark'] .main-head p,
html[data-theme='dark'] .upload-card p,
html[data-theme='dark'] .rank-row p,
html[data-theme='dark'] .rank-row em,
html[data-theme='dark'] .side-title h3,
html[data-theme='dark'] .side-card h3,
html[data-theme='dark'] .preview-title,
html[data-theme='dark'] .upload-form label,
html[data-theme='dark'] .resource-desc {
  color: var(--cf-text-secondary, rgba(248, 250, 252, 0.76));
}
html[data-theme='dark'] .nav-primary,
html[data-theme='dark'] .category-row {
  color: var(--cf-text-secondary, rgba(248, 250, 252, 0.68));
}
html[data-theme='dark'] .type-tabs button {
  color: rgba(248, 250, 252, 0.5);
}
html[data-theme='dark'] .filter-row > button,
html[data-theme='dark'] .view-toggle button {
  border-color: rgba(255, 255, 255, 0.08);
  color: rgba(248, 250, 252, 0.5);
}
html[data-theme='dark'] .rank-row > span {
  color: rgba(248, 250, 252, 0.4);
}
html[data-theme='dark'] .side-card a {
  color: rgba(248, 250, 252, 0.65);
}
html[data-theme='dark'] .side-card a time,
html[data-theme='dark'] .author-row,
html[data-theme='dark'] .resource-card footer,
html[data-theme='dark'] .resource-topic {
  color: rgba(248, 250, 252, 0.4);
}
html[data-theme='dark'] .info-grid div {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.06);
}
html[data-theme='dark'] .info-grid span {
  color: rgba(248, 250, 252, 0.4);
}
html[data-theme='dark'] .preview-frame,
html[data-theme='dark'] .markdown-frame {
  border-color: rgba(255, 255, 255, 0.08);
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .resource-card footer {
  border-top-color: rgba(255, 255, 255, 0.05);
}
html[data-theme='dark'] .nav-divider {
  background: rgba(255, 255, 255, 0.08);
}

/* Type color palette (dark) */
html[data-theme='dark'] .folder { color: #4d8ff7; background: linear-gradient(135deg, rgba(77, 143, 247, 0.18), rgba(13, 22, 43, 0.5)); }
html[data-theme='dark'] .audio { color: #4c8fff; background: linear-gradient(135deg, rgba(76, 143, 255, 0.18), rgba(13, 22, 43, 0.5)); }
html[data-theme='dark'] .code { color: #a78bfa; background: linear-gradient(135deg, rgba(109, 114, 216, 0.22), rgba(15, 17, 36, 0.5)); }
</style>
