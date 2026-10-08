<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import KnowledgeSidebar from './KnowledgeSidebar.vue';
import {
  listKnowledgeBases,
  createKnowledgeBase,
  deleteKnowledgeBase,
  getKnowledgeStats,
} from '@/api/ai-workspace';
import type { KnowledgeBaseVO, KnowledgeStatsVO } from '@/types/ai-workspace';

const router = useRouter();
const toast = useMessage();

const knowledgeBases = ref<KnowledgeBaseVO[]>([]);
const stats = ref<KnowledgeStatsVO | null>(null);
const loading = ref(false);
const searchQuery = ref('');
const showCreateModal = ref(false);
const createForm = ref({ name: '', description: '', category: 'General' });

const categoryColors: Record<string, string> = {
  General: '#52d0bc',
  '产品文档': '#60a5fa',
  '技术文档': '#f97316',
  '学习资料': '#52d0bc',
  '公司制度': '#8b5cf6',
  '市场与销售': '#4ade80',
};

async function loadData() {
  loading.value = true;
  try {
    const [result, s] = await Promise.all([
      listKnowledgeBases({ tab: 'mine', sort: 'recent', page: 1, pageSize: 50 }),
      getKnowledgeStats().catch(() => null),
    ]);
    knowledgeBases.value = result.items;
    stats.value = s;
  } catch (e: any) {
    toast.error(e?.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

const filteredKbs = computed(() => {
  const q = searchQuery.value.toLowerCase();
  if (!q) return knowledgeBases.value;
  return knowledgeBases.value.filter(
    (k) => k.name.toLowerCase().includes(q) || (k.description || '').toLowerCase().includes(q),
  );
});

async function handleCreate() {
  if (!createForm.value.name.trim()) {
    toast.warning('请输入知识库名称');
    return;
  }
  try {
    await createKnowledgeBase(createForm.value);
    showCreateModal.value = false;
    createForm.value = { name: '', description: '', category: 'General' };
    toast.success('知识库已创建');
    await loadData();
  } catch (e: any) {
    toast.error(e?.message || '创建失败');
  }
}

async function handleDelete(id: string) {
  try {
    await deleteKnowledgeBase(id);
    toast.success('已删除');
    await loadData();
  } catch (e: any) {
    toast.error(e?.message || '删除失败');
  }
}

function openKb(id: string) {
  router.push(`/ai/libraries/${id}`);
}

function openKbChat(id: string) {
  router.push(`/ai/chat?kb=${id}`);
}

function formatStorage(bytes?: number): string {
  if (!bytes) return '0 B';
  if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`;
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

import { computed } from 'vue';

const sidebarCollapsed = inject('sidebarCollapsed', ref(false));
const panelWidth = ref(300);
const isPanelCollapsed = ref(false);
const PANEL_MIN = 240; const PANEL_MAX = 520;

function startResize(e: MouseEvent) {
  document.addEventListener('mousemove', onResize);
  document.addEventListener('mouseup', stopResize);
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  e.preventDefault();
}
function onResize(e: MouseEvent) {
  panelWidth.value = Math.min(PANEL_MAX, Math.max(PANEL_MIN, window.innerWidth - e.clientX));
}
function stopResize() {
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
  document.body.style.cursor = '';
  document.body.style.userSelect = '';
}
onUnmounted(stopResize);

onMounted(loadData);
</script>

<template>
  <div class="libraries-layout" :class="{ 'panel-collapsed': isPanelCollapsed }" :style="{ '--panel-width': panelWidth + 'px' }">
    <KnowledgeSidebar />

    <!-- Main Content -->
    <main class="libraries-main">
      <!-- Header -->
      <div class="libraries-header">
        <div>
          <h2 class="libraries-title">我的知识库</h2>
          <p class="libraries-subtitle">管理您的个人数字资产与AI训练数据</p>
        </div>
        <div class="libraries-header-actions">
          <div class="libraries-search">
            <svg class="search-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
            <input v-model="searchQuery" type="text" placeholder="搜索知识库..." />
          </div>
          <button class="btn-import" @click="showCreateModal = true">+ 新建知识库</button>
        </div>
      </div>

      <!-- Stats -->
      <div v-if="stats" class="stats-mini">
        <span>{{ stats.knowledgeBaseCount }} 个知识库</span>
        <span>·</span>
        <span>{{ stats.documentCount }} 份文档</span>
        <span>·</span>
        <span>{{ formatStorage(stats.storageUsedBytes) }} 已用</span>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="libraries-loading">加载中...</div>

      <!-- Empty -->
      <div v-else-if="knowledgeBases.length === 0" class="libraries-empty">
        <div class="empty-icon">
          <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
        </div>
        <h3>还没有知识库</h3>
        <p>创建您的第一个知识库，开始整理文档和知识</p>
        <button class="btn-create-lg" @click="showCreateModal = true">+ 新建知识库</button>
      </div>

      <!-- KB Grid -->
      <div v-else class="kb-grid">
        <!-- Create card -->
        <button class="kb-card kb-card--create" @click="showCreateModal = true">
          <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
          <span>新建知识库</span>
        </button>

        <!-- KB cards -->
        <div
          v-for="kb in filteredKbs"
          :key="kb.id"
          class="kb-card"
          @click="openKb(kb.id)"
        >
          <div class="kb-card-top">
            <div class="kb-card-icon" :style="{ background: categoryColors[kb.category] || '#52d0bc' }">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#fff" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
            </div>
            <div class="kb-card-actions">
              <button class="kb-card-chat-btn" title="在对话中使用" @click.stop="openKbChat(kb.id)">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>
              </button>
              <button class="kb-card-delete" title="删除" @click.stop="handleDelete(kb.id)">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
              </button>
            </div>
          </div>
          <h3 class="kb-card-name">{{ kb.name }}</h3>
          <p class="kb-card-desc" v-if="kb.description">{{ kb.description }}</p>
          <div class="kb-card-stats">
            <div class="kb-stat">
              <span class="kb-stat-val">{{ kb.documentCount || 0 }}</span>
              <span class="kb-stat-label">文档</span>
            </div>
            <div class="kb-stat">
              <span class="kb-stat-val">{{ kb.qaPairCount ?? 0 }}</span>
              <span class="kb-stat-label">问答</span>
            </div>
            <div class="kb-stat">
              <span class="kb-stat-val">{{ kb.vectorCount || 0 }}</span>
              <span class="kb-stat-label">向量</span>
            </div>
            <div class="kb-stat">
              <span class="kb-stat-val">{{ formatStorage(kb.storageBytes) }}</span>
              <span class="kb-stat-label">存储</span>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- Resize handle -->
    <div v-if="!isPanelCollapsed" class="panel-resize-handle" :style="{ right: panelWidth + 'px' }" @mousedown="startResize" />

    <!-- Right AI Panel -->
    <aside class="libraries-ai-panel" :class="{ collapsed: isPanelCollapsed }" :style="{ width: panelWidth + 'px' }">
      <template v-if="!isPanelCollapsed">
        <div class="ai-panel-header">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 2l2.4 7.2h7.6l-6 4.8 2.4 7.2-6-4.8-6 4.8 2.4-7.2-6-4.8h7.6z"/></svg>
          <span>AI 洞察</span>
        </div>
        <div class="ai-panel-card ai-panel-card--primary">
          <p class="ai-panel-card-label">知识库概览</p>
          <p class="ai-panel-card-text" v-if="stats">
            共 {{ stats.knowledgeBaseCount }} 个知识库，{{ stats.documentCount }} 份文档，使用 {{ formatStorage(stats.storageUsedBytes) }}
          </p>
        </div>
        <div class="ai-panel-card">
          <p class="ai-panel-card-label">存储用量</p>
          <div class="storage-bar">
            <div class="storage-bar-fill" :style="{ width: stats ? Math.min(100, (stats.storageUsedBytes / (stats.storageLimitBytes || 1)) * 100) + '%' : '0%' }" />
          </div>
          <p class="storage-text">{{ stats ? formatStorage(stats.storageUsedBytes) : '...' }} / {{ stats ? formatStorage(stats.storageLimitBytes) : '50 GB' }}</p>
        </div>
      </template>
      <button class="panel-toggle-btn" @click="isPanelCollapsed = !isPanelCollapsed" :title="isPanelCollapsed ? '展开面板' : '收起面板'">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline v-if="isPanelCollapsed" points="15 18 9 12 15 6" />
          <polyline v-else points="9 18 15 12 9 6" />
        </svg>
      </button>
    </aside>

    <!-- Create Modal -->
    <Teleport to="body">
      <div v-if="showCreateModal" class="modal-overlay" @click.self="showCreateModal = false">
        <div class="modal-card">
          <h3>新建知识库</h3>
          <div class="modal-field">
            <label>名称 <span class="required">*</span></label>
            <input v-model="createForm.name" placeholder="输入知识库名称" maxlength="60" />
          </div>
          <div class="modal-field">
            <label>分类</label>
            <select v-model="createForm.category">
              <option value="General">通用</option>
              <option value="产品文档">产品文档</option>
              <option value="技术文档">技术文档</option>
              <option value="学习资料">学习资料</option>
              <option value="公司制度">公司制度</option>
              <option value="市场与销售">市场与销售</option>
            </select>
          </div>
          <div class="modal-field">
            <label>描述</label>
            <textarea v-model="createForm.description" placeholder="简要描述知识库内容" rows="3" maxlength="200" />
          </div>
          <div class="modal-actions">
            <button class="btn-cancel" @click="showCreateModal = false">取消</button>
            <button class="btn-submit" @click="handleCreate">创建</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* Layout */
.libraries-layout {
  display: flex;
  min-height: 100vh;
  
}
.libraries-main {
  flex: 1;
  padding: 48px 40px 40px;
  min-width: 0;
  max-width: calc(100% - 560px);
  margin-right: var(--panel-width, 300px);
  transition: margin-right 0.25s;
}
.libraries-ai-panel {
  width: var(--panel-width, 300px);
  flex-shrink: 0;
  position: fixed;
  right: 0;
  top: 56px;
  height: calc(100vh - 56px);
  border-left: 0.5px solid rgba(0,0,0,0.06);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 92%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  padding: 24px 20px 20px;
  overflow-y: auto;
  z-index: 10;
  transition: width 0.25s;
}
.libraries-ai-panel.collapsed {
  width: 36px !important;
  padding: 0;
  overflow: hidden;
}
/* sidebar collapsed */
.sidebar.collapsed ~ .libraries-main { margin-left: 36px; }
/* panel collapse */
.libraries-layout.panel-collapsed .libraries-main { margin-right: 36px; }

/* Header */
.libraries-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 16px;
  flex-wrap: wrap;
}
.libraries-title {
  font-size: 28px;
  font-weight: 600;
  margin: 0;
  color: rgba(0,0,0,0.85);
}
.libraries-subtitle {
  margin: 8px 0 0;
  font-size: 14px;
  color: rgba(0,0,0,0.45);
}
.libraries-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.libraries-search {
  position: relative;
}
.libraries-search input {
  padding: 8px 12px 8px 36px;
  border-radius: 999px;
  border: none;
  background: rgba(0,0,0,0.04);
  font-size: 13px;
  width: 200px;
  outline: none;
  transition: all 0.3s;
}
.libraries-search input:focus {
  background: #fff;
  box-shadow: 0 0 0 3px rgba(52,208,188,0.12);
}
.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: rgba(0,0,0,0.25);
}
.btn-import {
  padding: 8px 20px;
  border-radius: 999px;
  border: none;
  background: rgba(52,208,188,0.1);
  color: rgb(52,208,188);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}
.btn-import:hover { background: rgba(52,208,188,0.18); }

/* Stats */
.stats-mini {
  display: flex;
  gap: 8px;
  font-size: 13px;
  color: rgba(0,0,0,0.35);
  margin-bottom: 28px;
}

/* Loading & Empty */
.libraries-loading, .libraries-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 0;
  text-align: center;
  color: rgba(0,0,0,0.35);
}
.empty-icon { margin-bottom: 16px; color: rgba(0,0,0,0.15); }
.libraries-empty h3 { font-size: 18px; font-weight: 600; color: rgba(0,0,0,0.55); margin: 0 0 8px; }
.libraries-empty p { font-size: 14px; margin: 0 0 20px; }
.btn-create-lg {
  padding: 10px 28px;
  border-radius: 999px;
  border: none;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

/* KB Grid */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}
.kb-card {
  background: #fff;
  border-radius: 20px;
  padding: 22px;
  border: 1px solid rgba(0,0,0,0.04);
  box-shadow: 0 4px 20px rgba(0,0,0,0.03);
  cursor: pointer;
  transition: all 0.25s;
  min-height: 180px;
  display: flex;
  flex-direction: column;
}
.kb-card:hover { transform: translateY(-2px); box-shadow: 0 8px 30px rgba(0,0,0,0.06); }
.kb-card--create {
  border: 2px dashed rgba(0,0,0,0.1);
  background: rgba(255,255,255,0.5);
  box-shadow: none;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: rgba(0,0,0,0.35);
  font-size: 15px;
}
.kb-card--create:hover { border-color: rgb(52,208,188); color: rgb(52,208,188); }
.kb-card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.kb-card-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.kb-card-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}
.kb-card-chat-btn {
  border: none;
  background: transparent;
  color: rgba(0,0,0,0.15);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  opacity: 0;
  transition: all 0.15s;
}
.kb-card:hover .kb-card-chat-btn { opacity: 1; }
.kb-card-chat-btn:hover { color: rgb(52,208,188); background: rgba(52,208,188,0.08); }
.kb-card-delete {
  border: none;
  background: transparent;
  color: rgba(0,0,0,0.15);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  opacity: 0;
  transition: all 0.15s;
}
.kb-card:hover .kb-card-delete { opacity: 1; }
.kb-card-delete:hover { color: #d03050; background: rgba(208,48,80,0.08); }
.kb-card-name {
  font-size: 17px;
  font-weight: 600;
  margin: 14px 0 6px;
  color: rgba(0,0,0,0.85);
}
.kb-card-desc {
  font-size: 13px;
  color: rgba(0,0,0,0.4);
  margin: 0;
  flex: 1;
  line-height: 1.5;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.kb-card-stats {
  display: flex;
  gap: 4px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(0,0,0,0.04);
}
.kb-stat {
  flex: 1;
  text-align: center;
  display: flex;
  flex-direction: column;
}
.kb-stat-val { font-size: 15px; font-weight: 600; color: rgba(0,0,0,0.75); }
.kb-stat-label { font-size: 11px; color: rgba(0,0,0,0.3); margin-top: 2px; }

/* Right AI Panel */
.ai-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgb(52,208,188);
  font-size: 13px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 20px;
}
.ai-panel-card {
  background: rgba(255,255,255,0.6);
  border: 1px solid rgba(255,255,255,0.8);
  border-radius: 18px;
  padding: 16px;
  margin-bottom: 12px;
}
.ai-panel-card--primary {
  background: linear-gradient(135deg, rgb(52,208,188), rgb(0,107,95));
  color: #fff;
  border: none;
}
.ai-panel-card--primary .ai-panel-card-label { color: rgba(255,255,255,0.7); }
.ai-panel-card--primary .ai-panel-card-text { color: #fff; }
.ai-panel-card-label { font-size: 11px; font-weight: 500; opacity: 0.7; margin: 0 0 4px; }
.ai-panel-card-text { font-size: 13px; margin: 0; line-height: 1.5; }
.storage-bar {
  height: 4px;
  background: rgba(0,0,0,0.06);
  border-radius: 2px;
  margin: 8px 0;
  overflow: hidden;
}
.storage-bar-fill {
  height: 100%;
  background: rgb(52,208,188);
  border-radius: 2px;
  transition: width 0.3s;
}
.storage-text { font-size: 12px; color: rgba(0,0,0,0.35); margin: 0; }

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.3);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal-card {
  background: #fff;
  border-radius: 24px;
  padding: 32px;
  width: 420px;
  max-width: 90vw;
  box-shadow: 0 20px 60px rgba(0,0,0,0.12);
}
.modal-card h3 { font-size: 20px; font-weight: 600; margin: 0 0 20px; }
.modal-field { margin-bottom: 16px; }
.modal-field label { display: block; font-size: 13px; font-weight: 500; color: rgba(0,0,0,0.55); margin-bottom: 6px; }
.required { color: #d03050; }
.modal-field input, .modal-field select, .modal-field textarea {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 12px;
  font-size: 14px;
  font-family: inherit;
  outline: none;
  box-sizing: border-box;
  transition: border-color 0.2s;
}
.modal-field input:focus, .modal-field select:focus, .modal-field textarea:focus {
  border-color: rgb(52,208,188);
}
.modal-field textarea { resize: vertical; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 24px; }
.btn-cancel, .btn-submit {
  padding: 10px 24px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}
.btn-cancel { border: 1px solid rgba(0,0,0,0.1); background: transparent; color: rgba(0,0,0,0.55); }
.btn-submit { border: none; background: rgb(52,208,188); color: #fff; }

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .libraries-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .libraries-subtitle {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .libraries-search input {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .libraries-search input::placeholder {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .libraries-search input:focus {
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .search-icon {
  color: rgba(248,250,252,0.3);
}
html[data-theme='dark'] .stats-mini,
html[data-theme='dark'] .libraries-loading,
html[data-theme='dark'] .libraries-empty,
html[data-theme='dark'] .storage-text {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .empty-icon {
  color: rgba(248,250,252,0.15);
}
html[data-theme='dark'] .libraries-empty h3 {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .kb-card {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-card-border, rgba(255,255,255,0.07));
  box-shadow: var(--cf-card-shadow, 0 24px 70px rgba(0,0,0,0.36));
}
html[data-theme='dark'] .kb-card:hover {
  box-shadow: 0 12px 40px rgba(0,0,0,0.4);
}
html[data-theme='dark'] .kb-card--create {
  border-color: rgba(255,255,255,0.12);
  background: rgba(22,28,45,0.3);
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .kb-card-name {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .kb-card-desc {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .kb-card-stats {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .kb-stat-val {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .kb-stat-label {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .kb-card-chat-btn,
html[data-theme='dark'] .kb-card-delete {
  color: rgba(248,250,252,0.18);
}
html[data-theme='dark'] .ai-panel-card:not(.ai-panel-card--primary) {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  border-color: var(--cf-border, rgba(255,255,255,0.08));
}
html[data-theme='dark'] .storage-bar {
  background: rgba(255,255,255,0.08);
}
html[data-theme='dark'] .modal-card {
  background: var(--cf-bg-card, #0c0c0d);
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
}
html[data-theme='dark'] .modal-card h3 {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .modal-field label {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .modal-field input,
html[data-theme='dark'] .modal-field select,
html[data-theme='dark'] .modal-field textarea {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-card, #0c0c0d);
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .btn-cancel {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .panel-toggle-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 94%, transparent);
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .panel-toggle-btn:hover {
  background: var(--cf-bg-card, #0c0c0d);
}
</style>
