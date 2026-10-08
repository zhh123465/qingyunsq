<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref, h } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import KnowledgeSidebar from './KnowledgeSidebar.vue';
import {
  getKnowledgeBase,
  listDocuments,
  uploadDocuments,
  deleteDocument,
  updateKnowledgeBase,
  deleteKnowledgeBase,
  listQaPairs,
  createQaPair,
  updateQaPair,
  deleteQaPair,
} from '@/api/ai-workspace';
import type { KnowledgeBaseVO, KbDocumentVO, QaPairVO } from '@/types/ai-workspace';

const route = useRoute();
const router = useRouter();
const toast = useMessage();
const kbId = route.params.id as string;

const kb = ref<KnowledgeBaseVO | null>(null);
const activeTab = ref<'documents' | 'qa' | 'settings'>('documents');
const documents = ref<KbDocumentVO[]>([]);
const qaPairs = ref<QaPairVO[]>([]);
const loading = ref(true);
const docsLoading = ref(false);

// Resizable right panel
const panelWidth = ref(300);
const isPanelCollapsed = ref(false);
const PANEL_MIN = 240;
const PANEL_MAX = 520;

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

const categoryColors: Record<string, string> = {
  General: '#52d0bc',
  '产品文档': '#60a5fa',
  '技术文档': '#f97316',
  '学习资料': '#52d0bc',
  '公司制度': '#8b5cf6',
  '市场与销售': '#4ade80',
};

const sidebarCollapsed = inject('sidebarCollapsed', ref(false));

// ---- Load data ----
async function loadKb() {
  loading.value = true;
  try {
    kb.value = await getKnowledgeBase(kbId);
  } catch (e: any) {
    toast.error(e?.message || '加载失败');
  } finally {
    loading.value = false;
  }
}
async function loadDocuments() {
  docsLoading.value = true;
  try { documents.value = await listDocuments(kbId); } catch { /* ignore */ }
  finally { docsLoading.value = false; }
}
async function loadQaPairs() {
  try { qaPairs.value = await listQaPairs(kbId); } catch { /* ignore */ }
}

onMounted(() => {
  loadKb();
  loadDocuments();
  loadQaPairs();
});

// ---- Actions ----
function useInChat() {
  router.push(`/ai/chat?kb=${kbId}`);
}

// ---- File upload ----
const fileInput = ref<HTMLInputElement>();
function triggerUpload() { fileInput.value?.click(); }
async function handleUpload(e: Event) {
  const files = (e.target as HTMLInputElement).files;
  if (!files?.length) return;
  try {
    await uploadDocuments(kbId, Array.from(files));
    toast.success('上传成功');
    await loadDocuments();
    await loadKb();
  } catch (e: any) { toast.error(e?.message || '上传失败'); }
}

async function handleDeleteDoc(docId: string) {
  try {
    await deleteDocument(kbId, docId);
    toast.success('已删除');
    await loadDocuments();
    await loadKb();
  } catch (e: any) { toast.error(e?.message || '删除失败'); }
}

// ---- Q&A CRUD ----
const showQaModal = ref(false);
const editingQa = ref<QaPairVO | null>(null);
const qaForm = ref({ question: '', answer: '', tags: '' });

function openCreateQa() {
  editingQa.value = null;
  qaForm.value = { question: '', answer: '', tags: '' };
  showQaModal.value = true;
}
function openEditQa(qa: QaPairVO) {
  editingQa.value = qa;
  qaForm.value = { question: qa.question, answer: qa.answer, tags: (qa.tags || []).join(', ') };
  showQaModal.value = true;
}
async function saveQa() {
  if (!qaForm.value.question.trim() || !qaForm.value.answer.trim()) {
    toast.warning('问题和答案不能为空');
    return;
  }
  try {
    const payload = {
      question: qaForm.value.question,
      answer: qaForm.value.answer,
      tags: qaForm.value.tags ? qaForm.value.tags.split(/[,，]/).map((s: string) => s.trim()).filter(Boolean) : [],
    };
    if (editingQa.value) {
      await updateQaPair(kbId, editingQa.value.id, payload);
    } else {
      await createQaPair(kbId, payload);
    }
    toast.success('保存成功');
    showQaModal.value = false;
    await loadQaPairs();
    await loadKb();
  } catch (e: any) { toast.error(e?.message || '保存失败'); }
}
async function handleDeleteQa(qaId: string) {
  try {
    await deleteQaPair(kbId, qaId);
    toast.success('已删除');
    await loadQaPairs();
    await loadKb();
  } catch (e: any) { toast.error(e?.message || '删除失败'); }
}

// ---- Settings ----
const savingSettings = ref(false);
async function saveSettings() {
  if (!kb.value) return;
  savingSettings.value = true;
  try {
    await updateKnowledgeBase(kbId, {
      name: kb.value.name,
      description: kb.value.description,
      category: kb.value.category,
      visibility: kb.value.visibility,
    });
    toast.success('已保存');
  } catch (e: any) { toast.error(e?.message || '保存失败'); }
  finally { savingSettings.value = false; }
}
async function handleDeleteKb() {
  if (!confirm(`确定删除知识库「${kb.value?.name}」吗？删除后无法恢复。`)) return;
  try {
    await deleteKnowledgeBase(kbId);
    toast.success('已删除');
    router.push('/ai/libraries');
  } catch (e: any) { toast.error(e?.message || '删除失败'); }
}

// ---- Formatting ----
function formatStorage(bytes?: number): string {
  if (!bytes) return '0 B';
  if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`;
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}
function formatFileSize(bytes?: number): string {
  if (!bytes) return '0 B';
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}
function formatDate(d?: string): string {
  if (!d) return '-';
  return new Date(d).toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
}
</script>

<template>
  <div class="detail-layout" :class="{ 'panel-collapsed': isPanelCollapsed }" :style="{ '--panel-width': panelWidth + 'px' }">
    <KnowledgeSidebar />

    <!-- Main Content -->
    <main class="detail-main">
      <div v-if="loading" class="detail-loading">加载中...</div>

      <template v-else-if="kb">
        <!-- Header -->
        <div class="detail-header">
          <router-link to="/ai/libraries" class="back-link">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
            返回知识库列表
          </router-link>

          <div class="detail-title-row">
            <div class="detail-icon" :style="{ background: categoryColors[kb.category] || '#52d0bc' }">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="#fff" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
            </div>
            <div>
              <h1 class="detail-name">{{ kb.name }}</h1>
              <p class="detail-desc" v-if="kb.description">{{ kb.description }}</p>
            </div>
          </div>

          <div class="detail-stats">
            <span>{{ kb.documentCount || 0 }} 份文档</span>
            <span>·</span>
            <span>{{ kb.qaPairCount ?? 0 }} 条问答</span>
            <span>·</span>
            <span>{{ formatStorage(kb.storageBytes) }}</span>
            <span>·</span>
            <span>{{ kb.visibility === 'shared' ? '共享' : '私有' }}</span>
          </div>

          <button class="detail-chat-btn" @click="useInChat">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/></svg>
            在对话中使用
          </button>
        </div>

        <!-- Tabs -->
        <div class="detail-tabs">
          <button :class="{ active: activeTab === 'documents' }" @click="activeTab = 'documents'">文档</button>
          <button :class="{ active: activeTab === 'qa' }" @click="activeTab = 'qa'">问答对</button>
          <button :class="{ active: activeTab === 'settings' }" @click="activeTab = 'settings'">设置</button>
        </div>

        <!-- Tab: Documents -->
        <div v-if="activeTab === 'documents'" class="detail-tab-content">
          <div class="upload-zone" @click="triggerUpload">
            <input ref="fileInput" type="file" multiple hidden @change="handleUpload" />
            <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
            <span>点击上传文档</span>
            <span class="upload-hint">支持 PDF、Word、TXT、Markdown 等格式</span>
          </div>

          <div v-if="docsLoading" class="qa-empty">加载中...</div>
          <div v-else-if="documents.length === 0" class="qa-empty">
            <p>暂无文档</p>
            <p class="qa-empty-hint">上传文档后，AI 可检索其中的内容来回答问题</p>
          </div>
          <div v-else class="doc-list">
            <div v-for="doc in documents" :key="doc.id" class="doc-item">
              <div class="doc-info">
                <div class="doc-icon">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                </div>
                <div class="doc-meta">
                  <span class="doc-name">{{ doc.fileName }}</span>
                  <span class="doc-sub">{{ formatFileSize(doc.fileSize) }} · {{ doc.status }} · {{ formatDate(doc.createdAt) }}</span>
                </div>
              </div>
              <button class="doc-delete" @click="handleDeleteDoc(doc.id)" title="删除">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
              </button>
            </div>
          </div>
        </div>

        <!-- Tab: Q&A Pairs -->
        <div v-if="activeTab === 'qa'" class="detail-tab-content">
          <div class="qa-toolbar">
            <button class="btn-add-qa" @click="openCreateQa">+ 添加问答</button>
          </div>

          <div v-if="qaPairs.length === 0" class="qa-empty">
            <p>暂无问答对</p>
            <p class="qa-empty-hint">手动添加"问题-答案"组合，补充 AI 不掌握的内部知识</p>
          </div>
          <div v-else class="qa-list">
            <div v-for="qa in qaPairs" :key="qa.id" class="qa-card">
              <div class="qa-question">
                <span class="qa-label">Q</span>
                <span>{{ qa.question }}</span>
              </div>
              <div class="qa-answer">
                <span class="qa-label a">A</span>
                <span>{{ qa.answer }}</span>
              </div>
              <div class="qa-footer">
                <div class="qa-tags" v-if="qa.tags && qa.tags.length">
                  <span v-for="t in qa.tags" :key="t" class="qa-tag">{{ t }}</span>
                </div>
                <div class="qa-actions">
                  <button class="qa-action-btn" @click="openEditQa(qa)">编辑</button>
                  <button class="qa-action-btn danger" @click="handleDeleteQa(qa.id)">删除</button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Tab: Settings -->
        <div v-if="activeTab === 'settings'" class="detail-tab-content">
          <div class="settings-form">
            <div class="settings-field">
              <label>名称</label>
              <input v-model="kb.name" maxlength="60" />
            </div>
            <div class="settings-field">
              <label>描述</label>
              <textarea v-model="kb.description" rows="3" maxlength="200" />
            </div>
            <div class="settings-row">
              <div class="settings-field">
                <label>分类</label>
                <select v-model="kb.category">
                  <option value="General">通用</option>
                  <option value="产品文档">产品文档</option>
                  <option value="技术文档">技术文档</option>
                  <option value="学习资料">学习资料</option>
                  <option value="公司制度">公司制度</option>
                  <option value="市场与销售">市场与销售</option>
                </select>
              </div>
              <div class="settings-field">
                <label>可见性</label>
                <select v-model="kb.visibility">
                  <option value="private">私有</option>
                  <option value="shared">共享</option>
                </select>
              </div>
            </div>
            <div class="settings-actions">
              <button class="btn-save" :disabled="savingSettings" @click="saveSettings">
                {{ savingSettings ? '保存中...' : '保存设置' }}
              </button>
              <button class="btn-danger" @click="handleDeleteKb">删除知识库</button>
            </div>
          </div>
        </div>
      </template>
    </main>

    <!-- Resize handle -->
    <div v-if="!isPanelCollapsed" class="panel-resize-handle" :style="{ right: panelWidth + 'px' }" @mousedown="startResize" />

    <!-- Right Panel -->
    <aside class="detail-panel" :class="{ collapsed: isPanelCollapsed }" :style="{ width: panelWidth + 'px' }">
      <template v-if="!isPanelCollapsed">
        <div class="panel-header">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
          <span>知识库概览</span>
        </div>
        <div class="panel-card" v-if="kb">
          <p class="panel-card-label">基本信息</p>
          <p class="panel-card-text">分类: {{ kb.category }}</p>
          <p class="panel-card-text">类型: {{ kb.type || '未分类' }}</p>
          <p class="panel-card-text">创建于 {{ formatDate(kb.createdAt) }}</p>
        </div>
        <div class="panel-card">
          <p class="panel-card-label">存储用量</p>
          <div class="storage-bar">
            <div class="storage-bar-fill" :style="{ width: Math.min(100, ((kb?.storageBytes || 0) / 53687091200) * 100) + '%' }" />
          </div>
          <p class="storage-text">{{ kb ? formatStorage(kb.storageBytes) : '...' }} / 50 GB</p>
        </div>
        <div class="panel-card">
          <p class="panel-card-label">快捷操作</p>
          <button class="panel-action-btn" @click="useInChat">在对话中使用</button>
          <button class="panel-action-btn secondary" @click="activeTab = 'documents'; triggerUpload()">上传文档</button>
        </div>
      </template>
      <button class="panel-toggle-btn" @click="isPanelCollapsed = !isPanelCollapsed" :title="isPanelCollapsed ? '展开面板' : '收起面板'">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline v-if="isPanelCollapsed" points="15 18 9 12 15 6" />
          <polyline v-else points="9 18 15 12 9 6" />
        </svg>
      </button>
    </aside>

    <!-- Q&A Modal -->
    <Teleport to="body">
      <div v-if="showQaModal" class="modal-overlay" @click.self="showQaModal = false">
        <div class="modal-card">
          <h3>{{ editingQa ? '编辑问答' : '添加问答' }}</h3>
          <div class="modal-field">
            <label>问题 <span class="required">*</span></label>
            <textarea v-model="qaForm.question" placeholder="输入问题" rows="2" maxlength="500" />
          </div>
          <div class="modal-field">
            <label>答案 <span class="required">*</span></label>
            <textarea v-model="qaForm.answer" placeholder="输入答案" rows="4" maxlength="2000" />
          </div>
          <div class="modal-field">
            <label>标签（逗号分隔）</label>
            <input v-model="qaForm.tags" placeholder="如: 使用指南, 入门" maxlength="200" />
          </div>
          <div class="modal-actions">
            <button class="btn-cancel" @click="showQaModal = false">取消</button>
            <button class="btn-submit" @click="saveQa">保存</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
/* Layout */
.detail-layout {
  display: flex;
  min-height: 100vh;
}
.detail-main {
  flex: 1;
  padding: 40px;
  min-width: 0;
  max-width: calc(100% - 560px);
  margin-right: var(--panel-width, 300px);
  transition: margin-right 0.25s;
}
.detail-layout.panel-collapsed .detail-main { margin-right: 36px; }

/* Loading */
.detail-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 80px 0;
  color: rgba(0,0,0,0.35);
}

/* Header */
.detail-header {
  margin-bottom: 24px;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: rgba(0,0,0,0.4);
  text-decoration: none;
  margin-bottom: 16px;
  transition: color 0.15s;
}
.back-link:hover { color: rgb(52,208,188); }
.detail-title-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}
.detail-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.detail-name {
  font-size: 26px;
  font-weight: 600;
  margin: 0;
  color: rgba(0,0,0,0.85);
}
.detail-desc {
  margin: 6px 0 0;
  font-size: 14px;
  color: rgba(0,0,0,0.45);
}
.detail-stats {
  display: flex;
  gap: 8px;
  font-size: 13px;
  color: rgba(0,0,0,0.35);
  margin-bottom: 16px;
}
.detail-chat-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 22px;
  border: none;
  border-radius: 999px;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}
.detail-chat-btn:hover { opacity: 0.9; transform: translateY(-1px); }

/* Tabs */
.detail-tabs {
  display: flex;
  gap: 4px;
  background: rgba(0,0,0,0.03);
  border-radius: 12px;
  padding: 4px;
  margin-bottom: 24px;
  width: fit-content;
}
.detail-tabs button {
  padding: 8px 20px;
  border: none;
  border-radius: 10px;
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  color: rgba(0,0,0,0.45);
  cursor: pointer;
  transition: all 0.15s;
}
.detail-tabs button.active {
  background: #fff;
  color: rgba(0,0,0,0.85);
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}

/* Tab content */
.detail-tab-content {
  min-height: 300px;
}

/* Upload zone */
.upload-zone {
  border: 2px dashed rgba(0,0,0,0.1);
  border-radius: 16px;
  padding: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: rgba(0,0,0,0.3);
  transition: all 0.2s;
  margin-bottom: 20px;
}
.upload-zone:hover {
  border-color: rgb(52,208,188);
  color: rgb(52,208,188);
  background: rgba(52,208,188,0.03);
}
.upload-zone span { font-size: 14px; }
.upload-hint { font-size: 12px !important; opacity: 0.6; }

/* Document list */
.doc-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.doc-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  background: #fff;
  border: 1px solid rgba(0,0,0,0.04);
  border-radius: 14px;
  transition: box-shadow 0.15s;
}
.doc-item:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.doc-info {
  display: flex;
  align-items: center;
  gap: 12px;
}
.doc-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(0,0,0,0.03);
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(0,0,0,0.3);
}
.doc-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.doc-name {
  font-size: 14px;
  font-weight: 500;
  color: rgba(0,0,0,0.8);
}
.doc-sub {
  font-size: 12px;
  color: rgba(0,0,0,0.35);
}
.doc-delete {
  border: none;
  background: transparent;
  color: rgba(0,0,0,0.15);
  cursor: pointer;
  padding: 6px;
  border-radius: 8px;
  transition: all 0.15s;
}
.doc-delete:hover { color: #d03050; background: rgba(208,48,80,0.06); }

/* QA Toolbar */
.qa-toolbar {
  margin-bottom: 16px;
}
.btn-add-qa {
  padding: 8px 20px;
  border: none;
  border-radius: 10px;
  background: rgba(52,208,188,0.08);
  color: rgb(52,208,188);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}
.btn-add-qa:hover { background: rgba(52,208,188,0.15); }

/* QA empty */
.qa-empty {
  text-align: center;
  padding: 48px 0;
  color: rgba(0,0,0,0.35);
}
.qa-empty p { margin: 0; font-size: 14px; }
.qa-empty-hint { margin-top: 6px !important; font-size: 12px !important; opacity: 0.6; }

/* QA list */
.qa-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.qa-card {
  background: #fff;
  border: 1px solid rgba(0,0,0,0.04);
  border-radius: 16px;
  padding: 18px 20px;
  transition: box-shadow 0.15s;
}
.qa-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.04); }
.qa-question, .qa-answer {
  display: flex;
  gap: 10px;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(0,0,0,0.8);
}
.qa-answer {
  margin-top: 10px;
  color: rgba(0,0,0,0.55);
}
.qa-label {
  font-weight: 700;
  font-size: 13px;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: rgba(52,208,188,0.1);
  color: rgb(52,208,188);
}
.qa-label.a { background: rgba(0,0,0,0.05); color: rgba(0,0,0,0.45); }
.qa-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid rgba(0,0,0,0.03);
}
.qa-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.qa-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(0,0,0,0.04);
  color: rgba(0,0,0,0.4);
}
.qa-actions { display: flex; gap: 8px; }
.qa-action-btn {
  border: none;
  background: transparent;
  font-size: 12px;
  color: rgba(0,0,0,0.35);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: all 0.15s;
}
.qa-action-btn:hover { background: rgba(0,0,0,0.04); color: rgba(0,0,0,0.65); }
.qa-action-btn.danger:hover { color: #d03050; background: rgba(208,48,80,0.06); }

/* Settings form */
.settings-form {
  max-width: 480px;
}
.settings-field {
  margin-bottom: 16px;
}
.settings-field label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: rgba(0,0,0,0.55);
  margin-bottom: 6px;
}
.settings-field input, .settings-field select, .settings-field textarea {
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
.settings-field input:focus, .settings-field select:focus, .settings-field textarea:focus {
  border-color: rgb(52,208,188);
}
.settings-field textarea { resize: vertical; }
.settings-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.settings-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid rgba(0,0,0,0.06);
}
.btn-save {
  padding: 10px 24px;
  border: none;
  border-radius: 12px;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}
.btn-save:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-danger {
  padding: 10px 24px;
  border: 1px solid rgba(208,48,80,0.2);
  border-radius: 12px;
  background: transparent;
  color: #d03050;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}
.btn-danger:hover { background: rgba(208,48,80,0.04); }

/* Right Panel */
.detail-panel {
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
.detail-panel.collapsed {
  width: 36px !important;
  padding: 0;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgb(52,208,188);
  font-size: 13px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 16px;
}
.panel-card {
  background: rgba(255,255,255,0.6);
  border: 1px solid rgba(255,255,255,0.8);
  border-radius: 18px;
  padding: 16px;
  margin-bottom: 12px;
}
.panel-card-label {
  font-size: 11px;
  font-weight: 500;
  opacity: 0.7;
  margin: 0 0 6px;
  color: rgba(0,0,0,0.55);
}
.panel-card-text {
  font-size: 13px;
  margin: 0;
  line-height: 1.6;
  color: rgba(0,0,0,0.55);
}
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
.storage-text {
  font-size: 12px;
  color: rgba(0,0,0,0.35);
  margin: 0;
}
.panel-action-btn {
  display: block;
  width: 100%;
  padding: 8px 0;
  border: none;
  border-radius: 10px;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  margin-bottom: 8px;
}
.panel-action-btn.secondary {
  background: rgba(0,0,0,0.04);
  color: rgba(0,0,0,0.55);
}
.panel-action-btn.secondary:hover { background: rgba(0,0,0,0.08); }

/* Resize handle */
.panel-resize-handle {
  position: fixed;
  top: 56px;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 11;
  background: transparent;
  transition: background 0.15s;
}
.panel-resize-handle:hover { background: rgba(52,208,188,0.15); }

/* Panel toggle */
.panel-toggle-btn {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 24px;
  height: 24px;
  border: 0.5px solid rgba(0,0,0,0.08);
  border-radius: 50%;
  background: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(0,0,0,0.3);
  z-index: 2;
}

/* Modal (shared with KnowledgeLibraries) */
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
  width: 460px;
  max-width: 90vw;
  box-shadow: 0 20px 60px rgba(0,0,0,0.12);
}
.modal-card h3 { font-size: 20px; font-weight: 600; margin: 0 0 20px; }
.modal-field { margin-bottom: 16px; }
.modal-field label { display: block; font-size: 13px; font-weight: 500; color: rgba(0,0,0,0.55); margin-bottom: 6px; }
.required { color: #d03050; }
.modal-field input, .modal-field textarea {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 12px;
  font-size: 14px;
  font-family: inherit;
  outline: none;
  box-sizing: border-box;
  resize: vertical;
}
.modal-field input:focus, .modal-field textarea:focus { border-color: rgb(52,208,188); }
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
html[data-theme='dark'] .detail-loading,
html[data-theme='dark'] .detail-stats,
html[data-theme='dark'] .qa-empty,
html[data-theme='dark'] .storage-text {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .back-link {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .detail-name,
html[data-theme='dark'] .doc-name,
html[data-theme='dark'] .qa-question {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .detail-desc {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .detail-tabs {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
}
html[data-theme='dark'] .detail-tabs button {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .detail-tabs button.active {
  background: var(--cf-bg-card, #0c0c0d);
  color: var(--cf-text-primary, #f8fafc);
  box-shadow: 0 1px 4px rgba(0,0,0,0.2);
}
html[data-theme='dark'] .upload-zone {
  border-color: rgba(255,255,255,0.1);
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .doc-item,
html[data-theme='dark'] .qa-card {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-card-border, rgba(255,255,255,0.07));
}
html[data-theme='dark'] .doc-item:hover,
html[data-theme='dark'] .qa-card:hover {
  box-shadow: 0 2px 12px rgba(0,0,0,0.3);
}
html[data-theme='dark'] .doc-icon {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .doc-sub {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .doc-delete {
  color: rgba(248,250,252,0.18);
}
html[data-theme='dark'] .qa-answer {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .qa-label.a {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .qa-footer {
  border-top-color: rgba(255,255,255,0.03);
}
html[data-theme='dark'] .qa-tag {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .qa-action-btn {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .qa-action-btn:hover {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .settings-field label,
html[data-theme='dark'] .modal-field label,
html[data-theme='dark'] .panel-card-label,
html[data-theme='dark'] .panel-card-text {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .settings-field input,
html[data-theme='dark'] .settings-field select,
html[data-theme='dark'] .settings-field textarea,
html[data-theme='dark'] .modal-field input,
html[data-theme='dark'] .modal-field textarea {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-card, #0c0c0d);
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .settings-actions {
  border-top-color: rgba(255,255,255,0.06);
}
html[data-theme='dark'] .panel-card {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  border-color: var(--cf-border, rgba(255,255,255,0.08));
}
html[data-theme='dark'] .storage-bar {
  background: rgba(255,255,255,0.08);
}
html[data-theme='dark'] .panel-action-btn.secondary {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .panel-action-btn.secondary:hover {
  background: var(--cf-bg-muted, rgba(255,255,255,0.09));
}
html[data-theme='dark'] .panel-toggle-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-card, #0c0c0d);
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .modal-card {
  background: var(--cf-bg-card, #0c0c0d);
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
}
html[data-theme='dark'] .modal-card h3 {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .btn-cancel {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
</style>
