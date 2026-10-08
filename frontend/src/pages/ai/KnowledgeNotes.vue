<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import KnowledgeSidebar from './KnowledgeSidebar.vue';
import { renderMarkdown, type TocItem } from '@/utils/markdown';
import {
  listNotes,
  getNote,
  createNote,
  updateNote,
  deleteNote,
  uploadDocuments,
} from '@/api/ai-workspace';
import { aiRagChat } from '@/api/ai';
import type { NoteVO } from '@/types/ai-workspace';
import { NOTE_TAGS } from '@/data/note-tags';

const route = useRoute();
const router = useRouter();
const toast = useMessage();

// ---- State ----
const notes = ref<NoteVO[]>([]);
const currentNote = ref<NoteVO | null>(null);
const loading = ref(false);
const saving = ref(false);
const searchQuery = ref('');
const filterStatus = ref('');
const editorMode = ref<'edit' | 'preview' | 'split'>('edit');
const editorContent = ref('');
const previewHtml = ref('');
const fileInputRef = ref<HTMLInputElement | null>(null);
const editorTextareaRef = ref<HTMLTextAreaElement | null>(null);
const imageInputRef = ref<HTMLInputElement | null>(null);
const linkDialogVisible = ref(false);
const linkDialogUrl = ref('');
const linkDialogText = ref('');
const selectedTags = ref<string[]>([]);
const deleteConfirmId = ref('');
// TOC
const tocItems = ref<TocItem[]>([]);
const activeTocId = ref('');
const isTocCollapsed = ref(false);
const showBackToTop = ref(false);

// Sidebar collapse (from KnowledgeSidebar)
const sidebarCollapsed = inject('sidebarCollapsed', ref(false));
// Notes list collapse
const notesListCollapsed = ref(false);
// Right AI panel resize + collapse
const aiPanelWidth = ref(340);
const aiPanelCollapsed = ref(false);
const PANEL_MIN = 240; const PANEL_MAX = 520;

function startResize(e: MouseEvent) {
  document.addEventListener('mousemove', onResize);
  document.addEventListener('mouseup', stopResize);
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  e.preventDefault();
}
function onResize(e: MouseEvent) {
  aiPanelWidth.value = Math.min(PANEL_MAX, Math.max(PANEL_MIN, window.innerWidth - e.clientX));
}
function stopResize() {
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
  document.body.style.cursor = '';
  document.body.style.userSelect = '';
}
onUnmounted(stopResize);

function toggleMarkdown(before: string, after: string) {
  const ta = editorTextareaRef.value;
  if (!ta) return;
  const start = ta.selectionStart;
  const end = ta.selectionEnd;
  const selected = editorContent.value.slice(start, end);
  const bl = before.length;
  const al = after.length;
  // Check if already wrapped — toggle off
  if (selected.startsWith(before) && selected.endsWith(after) && selected.length >= bl + al) {
    const inner = selected.slice(bl, selected.length - al);
    editorContent.value = editorContent.value.slice(0, start) + inner + editorContent.value.slice(end);
    void nextTick(() => {
      ta.focus();
      ta.setSelectionRange(start, start + inner.length);
      updatePreview();
    });
    return;
  }
  // Wrap — select entire wrapped text including markers so re-click toggles off
  const replacement = before + selected + after;
  editorContent.value = editorContent.value.slice(0, start) + replacement + editorContent.value.slice(end);
  void nextTick(() => {
    ta.focus();
    const newEnd = start + bl + selected.length + al;
    ta.setSelectionRange(start, newEnd);
    updatePreview();
  });
}

function insertBlock(before: string, placeholder = '') {
  const ta = editorTextareaRef.value;
  if (!ta) return;
  const lineStart = editorContent.value.lastIndexOf('\n', ta.selectionStart - 1) + 1;
  editorContent.value = editorContent.value.slice(0, lineStart) + before + placeholder + '\n' + editorContent.value.slice(lineStart);
  void nextTick(() => {
    ta.focus();
    const cursor = lineStart + before.length + placeholder.length + 1;
    ta.setSelectionRange(cursor, cursor);
    updatePreview();
  });
}

function handleImageUpload() {
  imageInputRef.value?.click();
}

function onImageSelected(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const dataUrl = reader.result as string;
    const alt = file.name.replace(/\.[^.]+$/, '');
    insertBlock(`![${alt}](${dataUrl})`, '');
    toast.success('图片已插入');
  };
  reader.readAsDataURL(file);
}

function openLinkDialog() {
  const ta = editorTextareaRef.value;
  if (ta) {
    const selected = editorContent.value.slice(ta.selectionStart, ta.selectionEnd);
    linkDialogText.value = selected || '';
  }
  linkDialogUrl.value = '';
  linkDialogVisible.value = true;
}

function confirmLink() {
  const url = linkDialogUrl.value.trim();
  if (!url) { toast.warning('请输入链接地址'); return; }
  const text = linkDialogText.value.trim() || url;
  insertBlock(`[${text}](${url})`, '');
  linkDialogVisible.value = false;
  linkDialogUrl.value = '';
  linkDialogText.value = '';
}

function cancelLink() {
  linkDialogVisible.value = false;
}

// AI Chat state
const aiDraft = ref('');
const aiLoading = ref(false);
const aiMessages = ref<{ role: string; content: string }[]>([]);
const aiChatRef = ref<HTMLElement | null>(null);

// ---- Computed ----
const noteId = computed(() => route.query.edit as string || '');
const filteredNotes = computed(() => {
  let list = notes.value;
  if (filterStatus.value) list = list.filter((n) => n.status === filterStatus.value);
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase();
    list = list.filter((n) => n.title.toLowerCase().includes(q));
  }
  return list;
});

const aiSuggestions = [
  { label: '总结内容', prompt: '请总结这篇笔记的核心要点。' },
  { label: '提炼大纲', prompt: '请为这篇笔记提炼一个结构化大纲。' },
  { label: '扩写内容', prompt: '请帮我扩写这篇笔记，补充更多细节和例子。' },
  { label: '检查错误', prompt: '请检查这篇笔记中的逻辑错误或表述不清之处。' },
];

// ---- Load notes ----
async function loadNotes() {
  try {
    const res = await listNotes({ page: 1, pageSize: 200 });
    notes.value = res.items;
  } catch { /* ignore */ }
}

async function openNote(id: string) {
  loading.value = true;
  try {
    currentNote.value = await getNote(id);
    editorContent.value = currentNote.value.content || '';
    selectedTags.value = currentNote.value.tags?.filter((t) => NOTE_TAGS.includes(t as any)) || [];
    updatePreview();
    router.replace({ query: { edit: id } });
    aiMessages.value = [{
      role: 'assistant',
      content: `已打开笔记「${currentNote.value.title}」，我可以帮你总结、提炼、扩写或检查这篇笔记。`,
    }];
  } catch (e: any) {
    toast.error(e?.message || '加载失败');
  } finally {
    loading.value = false;
  }
}

function updatePreview() {
  const { html, toc } = renderMarkdown(editorContent.value || '');
  tocItems.value = toc;
  previewHtml.value = html;
}
function scrollToHeading(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  activeTocId.value = id;
}
function scrollToTop() {
  const previewEl = document.querySelector('.editor-preview');
  if (previewEl) previewEl.scrollTo({ top: 0, behavior: 'smooth' });
  activeTocId.value = tocItems.value.length > 0 ? tocItems.value[0].id : '';
}
function onPreviewScroll(e: Event) {
  const el = e.target as HTMLElement;
  showBackToTop.value = el.scrollTop > 400;
  const els = tocItems.value.map((t) => document.getElementById(t.id)).filter(Boolean) as HTMLElement[];
  const top = el.scrollTop + 80;
  for (let i = els.length - 1; i >= 0; i--) {
    if (els[i].offsetTop <= top) { activeTocId.value = tocItems.value[i].id; return; }
  }
  if (tocItems.value.length > 0) activeTocId.value = tocItems.value[0].id;
}

function onContentInput() {
  updatePreview();
}

// ---- Actions ----
function toggleTag(tag: string) {
  const idx = selectedTags.value.indexOf(tag);
  if (idx >= 0) {
    selectedTags.value.splice(idx, 1);
  } else {
    selectedTags.value.push(tag);
  }
}

async function handleNewNote() {
  try {
    const note = await createNote({ title: '未命名笔记', content: '', contentType: 'markdown' });
    notes.value.unshift(note);
    await openNote(note.id);
  } catch (e: any) {
    toast.error(e?.message || '创建失败');
  }
}

// 笔记状态文案/样式（审核流 2026-07-16：发布须先经管理员审核）
const statusLabelMap: Record<string, string> = {
  draft: '草稿',
  pending: '审核中',
  published: '已发布',
  rejected: '未通过',
  hidden: '已隐藏',
};
function statusLabel(status: string) {
  return statusLabelMap[status] || status;
}

async function handleSave(status?: string) {
  if (!currentNote.value) return;
  saving.value = true;
  try {
    // pending/rejected/hidden 是系统/管理员状态，用户端只允许回传 draft/published；
    // 普通保存时 pending→published（后端有实质改动会自动再落 pending）、rejected/hidden→draft
    const cur = currentNote.value.status;
    const fallback = cur === 'pending' ? 'published'
      : cur === 'rejected' || cur === 'hidden' ? 'draft' : cur;
    const updated = await updateNote(currentNote.value.id, {
      title: currentNote.value.title,
      content: editorContent.value,
      tags: selectedTags.value,
      status: status || fallback,
    });
    currentNote.value = updated;
    const idx = notes.value.findIndex((n) => n.id === updated.id);
    if (idx >= 0) notes.value[idx] = updated;
    if (status === 'published') {
      // 后端返回实际落库状态：管理员免审直接 published，普通用户落 pending 待审
      toast.success(updated.status === 'pending' ? '已提交审核，通过后将公开可见' : '已发布');
    } else if (updated.status === 'pending') {
      toast.success('已保存，笔记将重新提交审核');
    } else {
      toast.success('已保存');
    }
  } catch (e: any) {
    toast.error(e?.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

function requestDelete(id: string) {
  deleteConfirmId.value = id;
}
async function confirmDelete() {
  const id = deleteConfirmId.value;
  deleteConfirmId.value = '';
  try {
    await deleteNote(id);
    notes.value = notes.value.filter((n) => n.id !== id);
    if (currentNote.value?.id === id) {
      currentNote.value = null;
      router.replace({ query: {} });
    }
    toast.success('已删除');
  } catch (e: any) {
    toast.error(e?.message || '删除失败');
  }
}

async function handleFileUpload() {
  fileInputRef.value?.click();
}

async function onFileSelected(e: Event) {
  const files = (e.target as HTMLInputElement).files;
  if (!files?.length) return;
  try {
    // Upload and create a note from file
    const file = files[0];
    const text = await file.text();
    const note = await createNote({
      title: file.name,
      content: text.slice(0, 100000),
      contentType: file.name.endsWith('.md') ? 'markdown' : 'file',
    });
    notes.value.unshift(note);
    await openNote(note.id);
    toast.success(`已导入 ${file.name}`);
  } catch (e: any) {
    toast.error(e?.message || '导入失败');
  }
}

// ---- AI Chat ----
async function handleAiSend() {
  const text = aiDraft.value.trim();
  if (!text || aiLoading.value) return;
  aiDraft.value = '';
  aiLoading.value = true;
  aiMessages.value.push({ role: 'user', content: text });

  // Build context from current note
  const context = currentNote.value
    ? `【当前笔记：${currentNote.value.title}】\n${currentNote.value.content?.slice(0, 8000) || ''}`
    : '';

  try {
    const resp = await aiRagChat(
      [{ role: 'user', content: text }],
      context || undefined,
      'mimo-v2.5',
      ['web-search'],
    );
    aiMessages.value.push({ role: 'assistant', content: resp.reply || '抱歉，我暂时无法回答。' });
  } catch (e: any) {
    toast.error(e?.message || 'AI 响应失败');
  } finally {
    aiLoading.value = false;
    await nextTick();
    if (aiChatRef.value) aiChatRef.value.scrollTop = aiChatRef.value.scrollHeight;
  }
}

function handleAiSuggestion(prompt: string) {
  aiDraft.value = prompt;
  handleAiSend();
}

// ---- Lifecycle ----
onMounted(async () => {
  await loadNotes();
  if (noteId.value) {
    await openNote(noteId.value);
  }
});
</script>

<template>
  <div class="notes-layout" :class="{ 'notes-list-collapsed': notesListCollapsed, 'ai-panel-collapsed': aiPanelCollapsed }" :style="{ '--ai-panel-width': aiPanelWidth + 'px' }">
    <KnowledgeSidebar />

    <!-- Left Panel: Note List -->
    <aside class="notes-list-panel" :class="{ collapsed: notesListCollapsed }">
      <div class="notes-list-header">
        <h3 v-show="!notesListCollapsed">笔记</h3>
        <button class="notes-new-btn" @click="handleNewNote">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          <span v-show="!notesListCollapsed">新建</span>
        </button>
      </div>
      <template v-if="!notesListCollapsed">
        <div class="notes-list-search">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <input v-model="searchQuery" type="text" placeholder="搜索笔记..." />
        </div>
        <div class="notes-list-filters">
          <button :class="{ active: !filterStatus }" @click="filterStatus = ''">全部</button>
          <button :class="{ active: filterStatus === 'draft' }" @click="filterStatus = 'draft'">草稿</button>
          <button :class="{ active: filterStatus === 'pending' }" @click="filterStatus = 'pending'">审核中</button>
          <button :class="{ active: filterStatus === 'published' }" @click="filterStatus = 'published'">已发布</button>
        </div>
        <div class="notes-list-items">
          <div
            v-for="note in filteredNotes"
            :key="note.id"
            :class="['notes-list-item', { active: currentNote?.id === note.id }]"
            @click="openNote(note.id)"
          >
            <div class="notes-list-item-title">{{ note.title || '未命名笔记' }}</div>
            <div class="notes-list-item-meta">
              <span :class="['status-dot', note.status]" />
              {{ statusLabel(note.status) }}
              · {{ note.updatedAt?.slice(0, 10) || '' }}
            </div>
            <button class="notes-list-item-del" title="删除" @click.stop="requestDelete(note.id)">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
            </button>
          </div>
          <div v-if="filteredNotes.length === 0" class="notes-list-empty">
            暂无笔记，点击"新建"开始
          </div>
        </div>
        <div class="notes-list-upload">
          <input ref="fileInputRef" type="file" accept=".md,.txt,.html,.json,.xml,.csv" class="hidden" @change="onFileSelected" />
          <button @click="handleFileUpload">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
            导入文件
          </button>
        </div>
      </template>
      <button class="list-toggle-btn" @click="notesListCollapsed = !notesListCollapsed" :title="notesListCollapsed ? '展开列表' : '收起列表'">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline v-if="notesListCollapsed" points="9 18 15 12 9 6" />
          <polyline v-else points="15 18 9 12 15 6" />
        </svg>
      </button>
    </aside>

    <!-- Center: Editor -->
    <main class="notes-editor" v-if="currentNote">
      <!-- 驳回原因横幅（审核流 2026-07-16） -->
      <div v-if="currentNote.status === 'rejected' && currentNote.reviewReason" class="note-reject-banner">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        <span>审核未通过：{{ currentNote.reviewReason }}。修改后可重新提交发布。</span>
      </div>
      <!-- Toolbar -->
      <div class="notes-toolbar">
        <input
          v-if="currentNote"
          v-model="currentNote.title"
          class="note-title-input"
          placeholder="笔记标题"
        />

        <!-- Tag selector -->
        <div v-if="currentNote" class="note-tags-row">
          <span class="note-tags-label">标签：</span>
          <button
            v-for="tag in NOTE_TAGS"
            :key="tag"
            :class="['note-tag-chip', { active: selectedTags.includes(tag) }]"
            @click="toggleTag(tag)"
          >{{ tag }}</button>
        </div>

        <!-- Markdown formatting toolbar -->
        <div class="md-toolbar">
          <button title="粗体" @click="toggleMarkdown('**', '**')"><b>B</b></button>
          <button title="斜体" @click="toggleMarkdown('*', '*')"><i>I</i></button>
          <button title="标题" @click="insertBlock('## ', '标题')">H</button>
          <button title="删除线" @click="toggleMarkdown('~~', '~~')"><s>S</s></button>
          <span class="md-toolbar-divider" />
          <button title="无序列表" @click="insertBlock('- ', '列表项')">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
          </button>
          <button title="有序列表" @click="insertBlock('1. ', '列表项')">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="10" y1="6" x2="21" y2="6"/><line x1="10" y1="12" x2="21" y2="12"/><line x1="10" y1="18" x2="21" y2="18"/><path d="M4 6h1v4"/><path d="M4 12h2"/><path d="M6 18H4h2"/></svg>
          </button>
          <button title="引用" @click="insertBlock('> ', '引用内容')">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M3 15h6v4H5a2 2 0 01-2-2v-4h0z"/><path d="M21 15h-6v4h4a2 2 0 002-2v-4h0z"/></svg>
          </button>
          <span class="md-toolbar-divider" />
          <button title="行内代码" @click="toggleMarkdown('`', '`')">&lt;/&gt;</button>
          <button title="代码块" @click="insertBlock('```\n', '代码\n```')">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          </button>
          <span class="md-toolbar-divider" />
          <button title="链接" @click="openLinkDialog">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71"/></svg>
          </button>
          <button title="图片" @click="handleImageUpload">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
          </button>
          <input ref="imageInputRef" type="file" accept="image/*" class="hidden" @change="onImageSelected" />
          <button title="分割线" @click="insertBlock('---', '')">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="4" y1="12" x2="20" y2="12"/></svg>
          </button>

          <div class="md-toolbar-right">
            <span class="note-status" :class="currentNote.status">{{ statusLabel(currentNote.status) }}</span>
            <button class="btn-save" :disabled="saving" @click="handleSave()">{{ saving ? '保存中...' : '保存' }}</button>
            <button class="btn-publish" :disabled="saving" @click="handleSave('published')">{{ currentNote.status === 'published' ? '发布' : '提交发布' }}</button>
            <button class="btn-delete-note" @click="requestDelete(currentNote.id)">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
            </button>
          </div>
        </div>

        <!-- Mode toggles -->
        <div class="mode-toggles">
          <button :class="{ active: editorMode === 'edit' }" @click="editorMode = 'edit'">编辑</button>
          <button :class="{ active: editorMode === 'preview' }" @click="editorMode = 'preview'">预览</button>
          <button :class="{ active: editorMode === 'split' }" @click="editorMode = 'split'">分屏</button>
        </div>
      </div>

      <!-- Editor / Preview (TOC outside scroll container so it stays fixed) -->
      <div class="notes-editor-body">
        <template v-if="editorMode !== 'edit' && tocItems.length > 1">
          <aside class="editor-toc" :class="{ collapsed: isTocCollapsed }">
            <template v-if="!isTocCollapsed">
              <div class="editor-toc-title">目录</div>
              <nav class="editor-toc-nav">
                <button
                  v-for="item in tocItems"
                  :key="item.id"
                  :class="['editor-toc-item', `editor-toc-lv${item.level}`, { active: activeTocId === item.id }]"
                  @click="scrollToHeading(item.id)"
                >{{ item.text }}</button>
                <button v-if="showBackToTop" class="editor-toc-back-top" @click="scrollToTop" title="回到顶部">
                  <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="18 15 12 9 6 15"/></svg>
                  回到顶部
                </button>
              </nav>
            </template>
          </aside>
          <button class="editor-toc-toggle" :class="{ collapsed: isTocCollapsed }" @click="isTocCollapsed = !isTocCollapsed" :title="isTocCollapsed ? '展开目录' : '收起目录'">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
              <polyline v-if="isTocCollapsed" points="9 18 15 12 9 6" />
              <polyline v-else points="15 18 9 12 15 6" />
            </svg>
          </button>
        </template>
        <div :class="['notes-editor-area', editorMode === 'split' ? 'notes-editor-area--split' : '']">
          <textarea
            ref="editorTextareaRef"
            v-show="editorMode !== 'preview'"
            v-model="editorContent"
            :class="['editor-textarea', editorMode === 'split' ? 'editor-textarea--half' : '']"
            placeholder="开始写作 Markdown 笔记..."
            @input="onContentInput"
            spellcheck="false"
          />
          <div v-if="editorMode === 'split'" class="editor-divider" />
          <div
            v-show="editorMode === 'preview' || editorMode === 'split'"
            :class="['editor-preview', editorMode === 'split' ? 'editor-preview--half' : '']"
            v-html="previewHtml"
            @scroll="onPreviewScroll"
          />
        </div>
      </div>
    </main>

    <!-- Empty state -->
    <main class="notes-editor notes-empty" v-else>
      <div class="empty-state">
        <svg viewBox="0 0 24 24" width="56" height="56" fill="none" stroke="currentColor" stroke-width="1" opacity="0.3"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        <h3>选择或创建一篇笔记</h3>
        <p>从左侧列表选择笔记，或创建新笔记开始写作</p>
        <button class="btn-primary-lg" @click="handleNewNote">+ 新建笔记</button>
      </div>
    </main>

    <!-- Link Dialog -->
    <Teleport to="body">
      <div v-if="linkDialogVisible" class="link-overlay" @click.self="cancelLink">
        <div class="link-dialog">
          <h3>插入链接</h3>
          <div class="link-field">
            <label>链接文本</label>
            <input v-model="linkDialogText" placeholder="显示的文字" @keydown.enter="confirmLink" />
          </div>
          <div class="link-field">
            <label>链接地址 <span class="required">*</span></label>
            <input v-model="linkDialogUrl" placeholder="https://..." @keydown.enter="confirmLink" />
          </div>
          <div class="link-actions">
            <button class="link-btn-cancel" @click="cancelLink">取消</button>
            <button class="link-btn-confirm" @click="confirmLink">确定</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Delete Confirm Dialog -->
    <Teleport to="body">
      <div v-if="deleteConfirmId" class="confirm-overlay" @click.self="deleteConfirmId = ''">
        <div class="confirm-box">
          <p class="confirm-title">确认删除</p>
          <p class="confirm-body">删除后无法恢复，确定要删除这篇笔记吗？</p>
          <div class="confirm-actions">
            <button class="confirm-cancel" @click="deleteConfirmId = ''">取消</button>
            <button class="confirm-ok" @click="confirmDelete">删除</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Right Panel: AI Chat -->
    <!-- AI panel resize handle -->
    <div v-if="!aiPanelCollapsed" class="notes-resize-handle" :style="{ right: aiPanelWidth + 'px' }" @mousedown="startResize" />

    <aside class="notes-ai-panel" :class="{ collapsed: aiPanelCollapsed }" :style="{ width: aiPanelWidth + 'px' }">
      <template v-if="!aiPanelCollapsed">
        <div class="ai-panel-header">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M12 2l2.4 7.2h7.6l-6 4.8 2.4 7.2-6-4.8-6 4.8 2.4-7.2-6-4.8h7.6z"/></svg>
          <span>AI 知识库</span>
        </div>
        <div class="ai-context" v-if="currentNote">
          <div class="ai-context-badge">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            已引用: {{ currentNote.title }}
          </div>
        </div>
        <div class="ai-no-context" v-else>
          <p>打开笔记后自动引用内容，AI 可基于笔记进行总结、提炼、扩写</p>
        </div>
        <div class="ai-suggestions" v-if="currentNote && aiMessages.length <= 1">
          <button v-for="s in aiSuggestions" :key="s.label" class="ai-sugg-btn" @click="handleAiSuggestion(s.prompt)">
            {{ s.label }}
          </button>
        </div>
        <div class="ai-chat-messages" ref="aiChatRef" v-if="aiMessages.length > 1">
          <div v-for="(msg, i) in aiMessages.slice(1)" :key="i" :class="['ai-msg', msg.role]">
            <div class="ai-msg-bubble">{{ msg.content }}</div>
          </div>
          <div v-if="aiLoading" class="ai-loading">AI 思考中...</div>
        </div>
        <div class="ai-input-area">
          <input v-model="aiDraft" placeholder="询问关于这篇笔记的问题..." @keydown.enter="handleAiSend" />
          <button @click="handleAiSend" :disabled="!aiDraft.trim() || aiLoading">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
          </button>
        </div>
      </template>
    </aside>
    <!-- AI 面板收起按钮：必须放在面板外、作为 .notes-layout 直接子节点。
         面板设了 backdrop-filter（会为 fixed 后代创建包含块）+ overflow:hidden，
         按钮若在面板内会被错误定位并裁剪掉，导致完全不可见。 -->
    <button class="panel-toggle-btn" @click="aiPanelCollapsed = !aiPanelCollapsed" :title="aiPanelCollapsed ? '展开面板' : '收起面板'">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
        <polyline v-if="aiPanelCollapsed" points="15 18 9 12 15 6" />
        <polyline v-else points="9 18 15 12 9 6" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
/* Layout */
.notes-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.notes-list-panel {
  position: relative;
  width: 280px;
  flex-shrink: 0;
  border-right: 1px solid rgba(0,0,0,0.05);
  display: flex;
  flex-direction: column;
  background: rgba(255,255,255,0.3);
  transition: width 0.25s;
  overflow-y: auto;
}
.notes-list-panel.collapsed {
  width: 48px;
  padding: 12px 4px;
  align-items: center;
}
.notes-list-panel.collapsed .notes-list-header {
  justify-content: center;
  padding: 8px 0;
}
.notes-list-panel.collapsed .notes-new-btn {
  padding: 6px 8px;
}
.notes-list-panel.collapsed .notes-new-btn span {
  display: none;
}
.notes-layout.notes-list-collapsed .notes-editor { margin-left: 0; }
/* Toggle for notes list */
.list-toggle-btn {
  position: absolute;
  top: 50%;
  right: -24px;
  transform: translateY(-50%);
  width: 24px;
  height: 48px;
  border: 0.5px solid rgba(0,0,0,0.08);
  border-radius: 0 8px 8px 0;
  background: rgba(255,255,255,0.94);
  color: rgba(0,0,0,0.3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 12;
  transition: color 0.15s;
}
.list-toggle-btn:hover { color: rgb(52,208,188); border-color: rgba(52,208,188,0.4); background: #fff; }
/* sidebar collapse */
.sidebar.collapsed ~ .notes-list-panel { margin-left: 52px; }
.sidebar.collapsed ~ .notes-editor { margin-left: 52px; }
.notes-editor {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  margin-right: var(--ai-panel-width, 340px);
  transition: margin-right 0.25s;
}
.notes-layout.ai-panel-collapsed .notes-editor { margin-right: 36px; }

.notes-empty {
  align-items: center;
  justify-content: center;
}
.notes-ai-panel {
  width: var(--ai-panel-width, 340px);
  flex-shrink: 0;
  position: fixed;
  right: 0;
  top: 56px;
  height: calc(100vh - 56px);
  border-left: 0.5px solid rgba(0,0,0,0.06);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 92%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 10;
  transition: width 0.25s;
}
.notes-ai-panel.collapsed {
  width: 36px !important;
}

/* resize handle for notes AI panel */
.notes-resize-handle {
  position: fixed;
  top: 56px;
  height: calc(100vh - 56px);
  width: 6px;
  z-index: 13;
  cursor: col-resize;
  background: transparent;
  transition: background 0.15s;
}
.notes-resize-handle:hover { background: rgba(52,208,188,0.25); }
/* panel toggle */
.panel-toggle-btn {
  position: fixed;
  top: 50%;
  transform: translateY(-50%);
  right: var(--ai-panel-width, 340px);
  width: 20px;
  height: 40px;
  border: 0.5px solid rgba(0,0,0,0.08);
  border-left: none;
  border-radius: 0 6px 6px 0;
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 94%, transparent);
  color: rgba(0,0,0,0.3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 12;
  right: var(--ai-panel-width, 340px);
  transition: right 0.25s, color 0.15s;
}
.notes-layout.ai-panel-collapsed .panel-toggle-btn {
  right: 36px;
}
.panel-toggle-btn:hover {
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.3);
}

/* Left Panel */
.notes-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 16px 12px;
}
.notes-list-header h3 { margin: 0; font-size: 16px; font-weight: 600; }
.notes-new-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border: none;
  border-radius: 8px;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
}
.notes-list-search {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 12px 8px;
  padding: 7px 12px;
  border-radius: 10px;
  background: rgba(0,0,0,0.03);
  color: rgba(0,0,0,0.3);
}
.notes-list-search input {
  border: none;
  background: transparent;
  outline: none;
  font-size: 13px;
  width: 100%;
  font-family: inherit;
}
.notes-list-filters {
  display: flex;
  gap: 4px;
  padding: 0 12px 8px;
}
.notes-list-filters button {
  padding: 4px 12px;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  background: transparent;
  color: rgba(0,0,0,0.4);
  font-family: inherit;
}
.notes-list-filters button.active { background: rgba(52,208,188,0.1); color: rgb(52,208,188); font-weight: 500; }
.notes-list-items {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
}
.notes-list-item {
  position: relative;
  padding: 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}
.notes-list-item:hover { background: rgba(0,0,0,0.03); }
.notes-list-item.active { background: rgba(52,208,188,0.08); }
.notes-list-item-title {
  font-size: 13px;
  font-weight: 500;
  color: rgba(0,0,0,0.8);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.notes-list-item-meta {
  font-size: 11px;
  color: rgba(0,0,0,0.3);
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.notes-list-item-del {
  position: absolute;
  top: 8px;
  right: 8px;
  border: none;
  background: transparent;
  color: transparent;
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: all 0.15s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.notes-list-item:hover .notes-list-item-del {
  color: rgba(0,0,0,0.25);
}
.notes-list-item-del:hover {
  color: #d03050;
  background: rgba(208,48,80,0.08);
}
.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.status-dot.draft { background: #f59e0b; }
.status-dot.pending { background: #38bdf8; }
.status-dot.published { background: #4ade80; }
.status-dot.rejected { background: #ef4444; }
.status-dot.hidden { background: #9ca3af; }
.notes-list-empty {
  text-align: center;
  padding: 40px 16px;
  font-size: 13px;
  color: rgba(0,0,0,0.3);
}
.notes-list-upload {
  padding: 12px;
  border-top: 1px solid rgba(0,0,0,0.04);
}
.notes-list-upload button {
  width: 100%;
  padding: 8px;
  border: 1px dashed rgba(0,0,0,0.1);
  border-radius: 8px;
  background: transparent;
  color: rgba(0,0,0,0.4);
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-family: inherit;
}
.notes-list-upload button:hover { border-color: rgb(52,208,188); color: rgb(52,208,188); }
.hidden { display: none; }

/* Editor */
.notes-toolbar {
  padding: 16px 24px;
  border-bottom: 1px solid rgba(0,0,0,0.04);
}
.note-title-input {
  width: 100%;
  border: none;
  outline: none;
  font-size: 22px;
  font-weight: 700;
  font-family: inherit;
  margin-bottom: 12px;
  color: rgba(0,0,0,0.85);
  background: transparent;
}
.note-title-input::placeholder { color: rgba(0,0,0,0.2); }

/* Tag selector row */
.note-tags-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 0 8px;
}
.note-tags-label {
  font-size: 11px;
  color: rgba(0,0,0,0.4);
  margin-right: 2px;
}
.note-tag-chip {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 12px;
  border: 1px solid rgba(0,0,0,0.1);
  background: transparent;
  color: rgba(0,0,0,0.5);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.note-tag-chip:hover {
  border-color: rgb(52,208,188);
  color: rgb(52,208,188);
}
.note-tag-chip.active {
  background: rgb(52,208,188);
  border-color: rgb(52,208,188);
  color: #fff;
}

/* Markdown Toolbar */
.md-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 8px 0;
  border-bottom: 1px solid rgba(0,0,0,0.05);
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.md-toolbar button {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(0,0,0,0.45);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-family: inherit;
  transition: all 0.15s;
}
.md-toolbar button:hover { background: rgba(52,208,188,0.08); color: rgb(52,208,188); }
.md-toolbar-divider {
  width: 1px;
  height: 18px;
  background: rgba(0,0,0,0.08);
  margin: 0 4px;
}
.md-toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Mode toggles */
.mode-toggles {
  display: flex;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(0,0,0,0.08);
  width: fit-content;
}
.mode-toggles button {
  padding: 5px 14px;
  border: none;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
  color: rgba(0,0,0,0.4);
  font-family: inherit;
}
.mode-toggles button.active { background: rgba(52,208,188,0.08); color: rgb(52,208,188); font-weight: 500; }

/* Status + Save buttons */
.note-status { font-size: 11px; color: rgba(0,0,0,0.3); white-space: nowrap; }
.note-status.published { color: #4ade80; }
.note-status.draft { color: #f59e0b; }
.note-status.pending { color: #38bdf8; }
.note-status.rejected { color: #ef4444; }
.note-status.hidden { color: #9ca3af; }
.note-reject-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  font-size: 12px;
  color: #b91c1c;
  background: rgba(239,68,68,0.08);
  border-bottom: 1px solid rgba(239,68,68,0.15);
  flex-shrink: 0;
}
.btn-save, .btn-publish, .btn-delete-note {
  padding: 6px 16px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  border: 1px solid rgba(0,0,0,0.08);
  background: transparent;
  transition: all 0.15s;
  white-space: nowrap;
}
.btn-save { color: rgba(0,0,0,0.55); }
.btn-save:hover { border-color: rgba(0,0,0,0.2); }
.btn-publish { background: rgb(52,208,188); color: #fff; border-color: rgb(52,208,188); }
.btn-delete-note { color: rgba(0,0,0,0.4); border: none; padding: 6px 8px; }
.btn-delete-note:hover { color: #d03050; background: rgba(208,48,80,0.06); }
.btn-save:disabled, .btn-publish:disabled { opacity: 0.5; cursor: not-allowed; }

/* Editor area */
.notes-editor-body {
  flex: 1;
  display: flex;
  position: relative;
  min-height: 0;
}
.notes-editor-area {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  display: flex;
  min-height: 0;
}
.notes-editor-area--split {
  flex-direction: row;
}

.editor-textarea {
  flex: 1;
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
  padding: 24px 32px;
  border: none;
  outline: none;
  resize: none;
  font-family: 'Inter', ui-monospace, monospace;
  font-size: 14px;
  line-height: 1.8;
  color: rgba(0,0,0,0.8);
  background: transparent;
}
.editor-textarea--half {
  max-width: none;
  width: 50%;
  flex: none;
}
.editor-textarea::placeholder { color: rgba(0,0,0,0.15); }

.editor-divider {
  width: 1px;
  background: rgba(0,0,0,0.08);
  cursor: col-resize;
  flex-shrink: 0;
  transition: background 0.15s;
}
.editor-divider:hover { background: rgb(52,208,188); }

.editor-preview {
  flex: 1;
  padding: 24px 32px;
  max-width: 860px;
  margin: 0 auto;
  width: 100%;
  line-height: 1.8;
  font-size: 15px;
  color: rgba(0,0,0,0.8);
  overflow-y: auto;
}
.editor-preview--half {
  max-width: none;
  width: 50%;
  flex: none;
}
.editor-preview :deep(h1) { font-size: 26px; font-weight: 700; margin: 20px 0 10px; }
.editor-preview :deep(h2) { font-size: 22px; font-weight: 600; margin: 16px 0 8px; }
.editor-preview :deep(h3) { font-size: 18px; font-weight: 600; margin: 14px 0 6px; }
.editor-preview :deep(p) { margin: 10px 0; }
.editor-preview :deep(li) { margin: 4px 0 4px 20px; }
.editor-preview :deep(code) { background: rgba(0,0,0,0.05); padding: 2px 6px; border-radius: 4px; font-size: 13px; }
.editor-preview :deep(pre) { background: rgba(0,0,0,0.03); padding: 14px 18px; border-radius: 10px; overflow-x: auto; margin: 12px 0; }
.editor-preview :deep(pre code) { background: none; padding: 0; }
.editor-preview :deep(blockquote) { border-left: 3px solid rgb(52,208,188); padding-left: 14px; margin: 12px 0; color: rgba(0,0,0,0.55); }
.editor-preview :deep(strong) { font-weight: 600; }
.editor-preview :deep(a) { color: rgb(52,208,188); }
.editor-preview :deep(img) { max-width: 100%; border-radius: 8px; }

/* Editor TOC sidebar */
.editor-toc {
  width: 180px;
  flex-shrink: 0;
  border-right: 0.5px solid rgba(0,0,0,0.06);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 94%, transparent);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.2s;
}
.editor-toc.collapsed {
  width: 0;
  border-right: none;
}
.editor-toc-title {
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  color: rgba(0,0,0,0.35);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 12px 12px 8px;
  flex-shrink: 0;
}
.editor-toc-nav {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  padding: 0 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.editor-toc-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 5px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(0,0,0,0.45);
  font-family: inherit;
  font-size: 13px;
  cursor: pointer;
  line-height: 1.4;
  transition: all 0.15s;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* flex 列布局中默认 flex-shrink:1，标题多时会把每项压扁成一团（看起来像乱码）；
     固定为 0 保持自然高度，改由 .editor-toc-nav 的 overflow-y:auto 滚动 */
  flex-shrink: 0;
}
.editor-toc-lv2 { padding-left: 18px; }
.editor-toc-lv3 { padding-left: 28px; font-size: 12px; }
.editor-toc-item:hover { background: rgba(52,208,188,0.06); color: rgb(52,208,188); }
.editor-toc-item.active { background: rgba(52,208,188,0.1); color: rgb(52,208,188); font-weight: 600; }
/* Toggle outside aside */
.editor-toc-toggle {
  position: absolute;
  left: 180px;
  top: 12px;
  width: 20px;
  height: 40px;
  border: 0.5px solid rgba(0,0,0,0.08);
  border-left: none;
  border-radius: 0 6px 6px 0;
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 94%, transparent);
  color: rgba(0,0,0,0.3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 5;
  transition: left 0.2s, color 0.15s;
  flex-shrink: 0;
}
.editor-toc-toggle.collapsed {
  left: 0;
  border-left: 0.5px solid rgba(0,0,0,0.08);
}
.editor-toc-toggle:hover { color: rgb(52,208,188); border-color: rgba(52,208,188,0.3); }
.editor-toc-back-top {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  flex-shrink: 0;
  padding: 8px 0;
  margin-top: 4px;
  border: none;
  border-radius: 6px;
  background: rgba(52,208,188,0.06);
  color: rgb(52,208,188);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.editor-toc-back-top:hover { background: rgba(52,208,188,0.15); }

/* Empty */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  color: rgba(0,0,0,0.3);
}
.empty-state h3 { font-size: 18px; font-weight: 600; color: rgba(0,0,0,0.5); margin: 16px 0 6px; }
.empty-state p { font-size: 14px; margin: 0 0 20px; }
.btn-primary-lg {
  padding: 10px 28px;
  border: none;
  border-radius: 12px;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
}

/* AI Panel */
.ai-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 20px 16px 12px;
  color: rgb(52,208,188);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.ai-context { padding: 0 16px 8px; }
.ai-context-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(52,208,188,0.06);
  color: rgb(52,208,188);
  font-size: 12px;
  font-weight: 500;
}
.ai-no-context {
  padding: 0 16px;
  font-size: 12px;
  color: rgba(0,0,0,0.3);
  line-height: 1.5;
}
.ai-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 16px;
}
.ai-sugg-btn {
  padding: 6px 12px;
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 8px;
  background: rgba(255,255,255,0.6);
  font-size: 12px;
  color: rgba(0,0,0,0.55);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.ai-sugg-btn:hover { border-color: rgb(52,208,188); color: rgb(52,208,188); background: rgba(52,208,188,0.04); }
.ai-chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 8px 16px;
}
.ai-msg { margin-bottom: 12px; display: flex; }
.ai-msg.user { justify-content: flex-end; }
.ai-msg-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 12px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.ai-msg.user .ai-msg-bubble { background: rgb(52,208,188); color: #fff; }
.ai-msg.assistant .ai-msg-bubble { background: rgba(0,0,0,0.04); color: rgba(0,0,0,0.7); }
.ai-loading { font-size: 11px; color: rgba(0,0,0,0.3); padding: 8px 0; text-align: center; }
.ai-input-area {
  display: flex;
  gap: 6px;
  padding: 12px 16px;
  border-top: 1px solid rgba(0,0,0,0.04);
}
.ai-input-area input {
  flex: 1;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid rgba(0,0,0,0.06);
  background: rgba(255,255,255,0.6);
  font-size: 12px;
  outline: none;
  font-family: inherit;
}
.ai-input-area input:focus { border-color: rgb(52,208,188); }
.ai-input-area button {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  border: none;
  background: rgb(52,208,188);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}
.ai-input-area button:disabled { opacity: 0.4; cursor: not-allowed; }

/* Link dialog */
.link-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.25);
  backdrop-filter: blur(3px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
}
.link-dialog {
  background: #fff;
  border-radius: 20px;
  padding: 28px;
  width: 400px;
  max-width: 90vw;
  box-shadow: 0 20px 60px rgba(0,0,0,0.12);
}
.link-dialog h3 { font-size: 17px; font-weight: 600; margin: 0 0 20px; }
.link-field { margin-bottom: 14px; }
.link-field label { display: block; font-size: 12px; font-weight: 500; color: rgba(0,0,0,0.5); margin-bottom: 5px; }
.link-field input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 10px;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
  font-family: inherit;
}
.link-field input:focus { border-color: rgb(52,208,188); }
.link-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.link-btn-cancel, .link-btn-confirm {
  padding: 8px 22px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
}
.link-btn-cancel { border: 1px solid rgba(0,0,0,0.1); background: transparent; color: rgba(0,0,0,0.5); }
.link-btn-confirm { border: none; background: rgb(52,208,188); color: #fff; }

/* Confirm dialog */
.confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.3);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 300;
}
.confirm-box {
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  max-width: 360px;
  width: 90%;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
}
.confirm-title { font-size: 16px; font-weight: 600; margin: 0 0 8px; color: rgba(0,0,0,0.85); }
.confirm-body { font-size: 13px; color: rgba(0,0,0,0.5); margin: 0 0 20px; line-height: 1.5; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 8px; }
.confirm-cancel {
  padding: 7px 18px;
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 10px;
  background: transparent;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  color: rgba(0,0,0,0.5);
}
.confirm-ok {
  padding: 7px 18px;
  border: none;
  border-radius: 10px;
  background: rgb(255,77,79);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
}
.confirm-ok:hover { opacity: 0.85; }

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .notes-list-panel {
  border-right-color: var(--cf-border, rgba(255,255,255,0.08));
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 96%, transparent);
}
html[data-theme='dark'] .list-toggle-btn {
  border-color: rgba(255,255,255,0.2);
  background: var(--cf-bg-card, #0c0c0d);
  color: rgba(248,250,252,0.7);
}
html[data-theme='dark'] .list-toggle-btn:hover {
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .notes-list-search {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .notes-list-search input {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .notes-list-search input::placeholder {
  color: rgba(248,250,252,0.3);
}
html[data-theme='dark'] .notes-list-filters button {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .notes-list-item:hover {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
}
html[data-theme='dark'] .notes-list-item-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .notes-list-item-meta,
html[data-theme='dark'] .notes-list-empty {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .notes-list-upload {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .notes-list-upload button {
  border-color: rgba(255,255,255,0.1);
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .notes-toolbar {
  border-bottom-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .note-title-input {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .note-title-input::placeholder {
  color: rgba(248,250,252,0.2);
}
html[data-theme='dark'] .note-tags-label {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .note-tag-chip {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .md-toolbar {
  border-bottom-color: rgba(255,255,255,0.05);
}
html[data-theme='dark'] .md-toolbar button {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .md-toolbar-divider {
  background: rgba(255,255,255,0.08);
}
html[data-theme='dark'] .mode-toggles {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
}
html[data-theme='dark'] .mode-toggles button {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .note-status {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .btn-save {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
  border-color: var(--cf-border, rgba(255,255,255,0.08));
}
html[data-theme='dark'] .btn-delete-note {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .btn-delete-note:hover {
  background: rgba(208,48,80,0.12);
}
html[data-theme='dark'] .editor-textarea {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .editor-textarea::placeholder {
  color: rgba(248,250,252,0.15);
}
html[data-theme='dark'] .editor-divider {
  background: rgba(255,255,255,0.08);
}
html[data-theme='dark'] .editor-preview {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .editor-preview :deep(code) {
  background: rgba(255,255,255,0.06);
}
html[data-theme='dark'] .editor-preview :deep(pre) {
  background: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .editor-preview :deep(blockquote) {
  color: rgba(248,250,252,0.55);
}
html[data-theme='dark'] .empty-state {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .empty-state h3 {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .ai-no-context {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .ai-sugg-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .ai-msg.assistant .ai-msg-bubble {
  background: rgba(255,255,255,0.08);
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .ai-loading {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .ai-input-area {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .ai-input-area input {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .ai-input-area input::placeholder {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .link-dialog {
  background: var(--cf-bg-card, #0c0c0d);
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
}
html[data-theme='dark'] .link-dialog h3 {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .link-field label {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .link-field input {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-card, #0c0c0d);
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .link-btn-cancel {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .panel-toggle-btn {
  border-color: rgba(255,255,255,0.15);
  background: var(--cf-bg-card, #0c0c0d);
  color: rgba(248,250,252,0.65);
}
html[data-theme='dark'] .panel-toggle-btn:hover {
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .notes-list-item-del {
  color: transparent;
}
html[data-theme='dark'] .notes-list-item:hover .notes-list-item-del {
  color: rgba(248,250,252,0.25);
}
html[data-theme='dark'] .notes-list-item-del:hover {
  color: #d03050;
  background: rgba(208,48,80,0.15);
}
html[data-theme='dark'] .editor-toc {
  border-right-color: rgba(255,255,255,0.06);
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 94%, transparent);
}
html[data-theme='dark'] .editor-toc-title {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .editor-toc-item {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .editor-toc-toggle {
  border-color: rgba(255,255,255,0.15);
  background: var(--cf-bg-card, #0c0c0d);
  color: rgba(248,250,252,0.65);
}
html[data-theme='dark'] .editor-toc-toggle.collapsed {
  border-left-color: rgba(255,255,255,0.15);
}
html[data-theme='dark'] .confirm-box {
  background: var(--cf-bg-card, #0c0c0d);
  box-shadow: 0 8px 32px rgba(0,0,0,0.4);
}
html[data-theme='dark'] .confirm-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .confirm-body {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .confirm-cancel {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
</style>
