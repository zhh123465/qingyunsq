<script setup lang="ts">
import { onMounted, onUnmounted, ref, nextTick, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { getTutorial, getLesson } from '@/api/learning';
import { aiRagChat } from '@/api/ai';
import { createNote, updateNote } from '@/api/ai-workspace';
import type { TutorialDetailVO, LessonVO } from '@/types/learning';
import type { NoteVO } from '@/types/ai-workspace';

const route = useRoute();
const router = useRouter();
const toast = useMessage();

const tutorial = ref<TutorialDetailVO | null>(null);
const currentLesson = ref<LessonVO | null>(null);
const loading = ref(false);
const lessonLoading = ref(false);

// Sidebar collapse state
const isTocCollapsed = ref(false);
const isAiCollapsed = ref(false);

// Right panel tab: 'ai' | 'notes'
const rightTab = ref<'ai' | 'notes'>('ai');

// Right panel resizable width
const panelWidth = ref(300);
const isResizing = ref(false);
const PANEL_MIN = 240;
const PANEL_MAX = 520;

function startResize(e: MouseEvent) {
  isResizing.value = true;
  document.addEventListener('mousemove', onResize);
  document.addEventListener('mouseup', stopResize);
  document.body.style.cursor = 'col-resize';
  document.body.style.userSelect = 'none';
  e.preventDefault();
}

function onResize(e: MouseEvent) {
  if (!isResizing.value) return;
  // Right panel is fixed to the right edge, so width = viewport width - mouse x
  const newWidth = window.innerWidth - e.clientX;
  panelWidth.value = Math.min(PANEL_MAX, Math.max(PANEL_MIN, newWidth));
}

function stopResize() {
  isResizing.value = false;
  document.removeEventListener('mousemove', onResize);
  document.removeEventListener('mouseup', stopResize);
  document.body.style.cursor = '';
  document.body.style.userSelect = '';
}

// AI sidebar
const aiDraft = ref('');
const aiLoading = ref(false);
const aiMessages = ref<{ role: string; content: string }[]>([]);
const aiChatRef = ref<HTMLElement | null>(null);

// Note-taking
const noteContent = ref('');
const noteSaving = ref(false);
const currentNote = ref<NoteVO | null>(null);
const noteEditorRef = ref<HTMLTextAreaElement | null>(null);

const tutorialId = route.params.id as string;

async function loadTutorial() {
  loading.value = true;
  try {
    tutorial.value = await getTutorial(tutorialId);
    // Auto-open first chapter
    const first = tutorial.value.chapters[0];
    if (first) openLesson(first.id);
  } catch (e: any) {
    toast.error('教程不存在');
    router.replace('/learning');
  } finally {
    loading.value = false;
  }
}

async function openLesson(id: string) {
  lessonLoading.value = true;
  try {
    currentLesson.value = await getLesson(id);
    aiMessages.value = [{
      role: 'assistant',
      content: `已加载「${currentLesson.value.title}」，你可以针对这一章节向我提问。`,
    }];
  } catch { /* keep current */ }
  finally { lessonLoading.value = false; }
}

async function handleAiSend() {
  const text = aiDraft.value.trim();
  if (!text || aiLoading.value || !currentLesson.value) return;
  aiDraft.value = '';
  aiLoading.value = true;
  aiMessages.value.push({ role: 'user', content: text });
  const context = `【当前教程章节：${currentLesson.value.title}】\n${stripHtml(currentLesson.value.content).slice(0, 6000)}`;
  try {
    const resp = await aiRagChat([{ role: 'user', content: text }], context, 'mimo-v2.5', ['web-search']);
    aiMessages.value.push({ role: 'assistant', content: resp.reply || '抱歉，暂时无法回答。' });
  } catch { aiMessages.value.push({ role: 'assistant', content: 'AI 响应失败，请稍后重试。' }); }
  finally { aiLoading.value = false; }
}

// ---- Note-taking ----
async function startNoteTaking() {
  // If already in notes mode with a note, just switch to it
  if (rightTab.value === 'notes') return;
  rightTab.value = 'notes';
  // Expand AI panel if collapsed
  if (isAiCollapsed.value) isAiCollapsed.value = false;
  // Auto-create note if not yet created
  if (!currentNote.value) {
    try {
      const title = tutorial.value?.title || '学习笔记';
      const note = await createNote({
        title: `笔记 · ${title}`,
        content: '',
        contentType: 'markdown',
        status: 'draft',
      });
      currentNote.value = note;
      noteContent.value = '';
    } catch (e: any) {
      toast.error('创建笔记失败');
    }
  }
  await nextTick();
  noteEditorRef.value?.focus();
}

async function handleSaveNote(status?: string) {
  if (!currentNote.value || noteSaving.value) return;
  noteSaving.value = true;
  try {
    const updated = await updateNote(currentNote.value.id, {
      title: currentNote.value.title,
      content: noteContent.value,
      status: status || currentNote.value.status,
    });
    currentNote.value = updated;
    toast.success(status === 'published' ? '笔记已发布' : '笔记已保存');
  } catch (e: any) {
    toast.error(e?.message || '保存失败');
  } finally {
    noteSaving.value = false;
  }
}

function stripHtml(html: string): string {
  return html?.replace(/<[^>]+>/g, '').trim() || '';
}

// Auto-scroll AI chat to bottom
watch(aiMessages, () => {
  nextTick(() => {
    const el = aiChatRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}, { deep: true });

onMounted(loadTutorial);
</script>

<template>
  <div
    class="reader-layout"
    :class="{ 'toc-collapsed': isTocCollapsed, 'ai-collapsed': isAiCollapsed }"
    :style="{ '--panel-width': panelWidth + 'px' }"
  >
    <!-- Left: Chapter list TOC -->
    <aside class="reader-sidebar" :class="{ collapsed: isTocCollapsed }">
      <template v-if="!isTocCollapsed">
        <button class="reader-back" @click="router.push('/learning')">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
          返回学习页
        </button>
        <h3 class="reader-tut-title" v-if="tutorial">{{ tutorial.title }}</h3>
        <div v-if="tutorial" class="reader-tut-meta">{{ tutorial.chapters.length }} 章 · {{ tutorial.source }}</div>
        <div v-if="loading" class="reader-loading">加载中...</div>
        <nav v-else-if="tutorial" class="reader-nav">
          <button
            v-for="ch in tutorial.chapters"
            :key="ch.id"
            :class="['reader-chapter', { active: currentLesson?.id === ch.id }]"
            @click="openLesson(ch.id)"
          >
            {{ ch.title }}
          </button>
        </nav>
      </template>
    </aside>

    <!-- TOC toggle — fixed, outside aside to avoid overflow clipping -->
    <button
      class="sidebar-toggle toc-toggle"
      :class="{ collapsed: isTocCollapsed }"
      @click="isTocCollapsed = !isTocCollapsed"
      :title="isTocCollapsed ? '展开目录' : '收起目录'"
    >
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
        <polyline v-if="isTocCollapsed" points="9 18 15 12 9 6" />
        <polyline v-else points="15 18 9 12 15 6" />
      </svg>
    </button>

    <!-- AI panel toggle — fixed -->
    <button
      class="sidebar-toggle ai-toggle"
      :class="{ collapsed: isAiCollapsed }"
      @click="isAiCollapsed = !isAiCollapsed"
      :title="isAiCollapsed ? '展开面板' : '收起面板'"
    >
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
        <polyline v-if="isAiCollapsed" points="15 18 9 12 15 6" />
        <polyline v-else points="9 18 15 12 9 6" />
      </svg>
    </button>

    <!-- Center: Content with top toolbar -->
    <main class="reader-main" v-if="currentLesson">
      <div class="reader-toolbar">
        <h2 class="reader-lesson-title">{{ currentLesson.title }}</h2>
        <div class="reader-toolbar-actions">
          <button
            :class="['reader-note-btn', { active: rightTab === 'notes' && !isAiCollapsed }]"
            @click="startNoteTaking"
            title="记笔记"
          >
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
            记笔记
          </button>
        </div>
      </div>
      <div v-if="lessonLoading" class="reader-loading">加载章节...</div>
      <div v-else class="reader-content" v-html="currentLesson.content" />
      <div class="reader-source">
        内容来自
        <a :href="currentLesson.sourceUrl" target="_blank" rel="noopener">{{ tutorial?.source || '原文' }}</a>
        ，仅供学习参考
      </div>
    </main>

    <!-- Resize handle for right panel -->
    <div
      v-if="!isAiCollapsed"
      class="panel-resize-handle"
      :style="{ right: panelWidth + 'px' }"
      @mousedown="startResize"
    />

    <!-- Right: AI Chat / Notes Panel -->
    <aside class="reader-ai" :class="{ collapsed: isAiCollapsed }" :style="{ width: panelWidth + 'px' }">
      <template v-if="!isAiCollapsed">
        <div class="rai-tabs">
          <button :class="{ active: rightTab === 'ai' }" @click="rightTab = 'ai'">AI 问答</button>
          <button :class="{ active: rightTab === 'notes' }" @click="rightTab = 'notes'">笔记</button>
        </div>

        <template v-if="rightTab === 'ai'">
          <div class="rai-context" v-if="currentLesson">基于章节「{{ currentLesson.title }}」提问</div>
          <div class="rai-chat" ref="aiChatRef">
            <div v-for="(msg, i) in aiMessages" :key="i" :class="['rai-msg', msg.role]">
              <div class="rai-msg-bubble">{{ msg.content }}</div>
            </div>
            <div v-if="aiLoading" class="rai-loading">AI 思考中...</div>
          </div>
          <div class="rai-input">
            <input v-model="aiDraft" placeholder="询问本章内容..." @keydown.enter="handleAiSend" />
            <button @click="handleAiSend" :disabled="!aiDraft.trim() || aiLoading">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
            </button>
          </div>
        </template>

        <template v-else>
          <div class="rai-note-header">
            <span class="rai-note-title">{{ currentNote?.title || '学习笔记' }}</span>
            <div class="rai-note-actions">
              <button class="rai-note-save-btn" :disabled="noteSaving" @click="handleSaveNote()">
                {{ noteSaving ? '保存中...' : '保存草稿' }}
              </button>
              <button class="rai-note-pub-btn" :disabled="noteSaving" @click="handleSaveNote('published')">
                发布
              </button>
            </div>
          </div>
          <div class="rai-note-context" v-if="currentLesson">
            记录「{{ currentLesson.title }}」的学习笔记
          </div>
          <textarea
            ref="noteEditorRef"
            class="rai-note-editor"
            v-model="noteContent"
            placeholder="在此记录笔记...&#10;&#10;支持 Markdown 语法"
          />
        </template>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.reader-layout {
  display: flex;
  min-height: calc(100vh - 56px);
}

/* ---- Left Sidebar (TOC) ---- */
.reader-sidebar {
  width: 220px;
  flex-shrink: 0;
  border-right: 0.5px solid color-mix(in srgb, var(--cf-border, rgba(0,0,0,0.08)) 60%, transparent);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 92%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  padding: 18px 14px;
  overflow-y: auto;
  position: fixed;
  left: 0;
  top: 56px;
  height: calc(100vh - 56px);
  z-index: 10;
  transition: width 0.25s, padding 0.25s;
}
.reader-sidebar.collapsed {
  width: 36px;
  padding: 18px 4px;
  overflow: hidden;
}
.reader-back {
  display: inline-flex; align-items: center; gap: 4px; border: none; background: transparent;
  color: rgba(0,0,0,0.4); font-size: 12px; cursor: pointer; padding: 0; margin-bottom: 12px; font-family: inherit;
}
.reader-back:hover { color: rgb(52,208,188); }
.reader-tut-title { font-size: 15px; font-weight: 700; margin: 0 0 4px; color: rgba(0,0,0,0.8); }
.reader-tut-meta { font-size: 11px; color: rgba(0,0,0,0.35); margin-bottom: 16px; }
.reader-loading { padding: 20px; text-align: center; color: rgba(0,0,0,0.35); font-size: 13px; }
.reader-nav { display: flex; flex-direction: column; gap: 2px; }
.reader-chapter {
  display: block; width: 100%; text-align: left; padding: 8px 10px; border: none; border-radius: 8px;
  background: transparent; color: rgba(0,0,0,0.55); font-size: 13px; cursor: pointer; font-family: inherit;
  line-height: 1.4; transition: all 0.15s;
}
.reader-chapter:hover { background: rgba(52,208,188,0.06); color: rgb(52,208,188); }
.reader-chapter.active { background: rgba(52,208,188,0.1); color: rgb(52,208,188); font-weight: 600; }

/* Sidebar toggle button — fixed to always stay visible regardless of sidebar overflow */
.sidebar-toggle {
  position: fixed;
  top: 50%;
  transform: translateY(-50%);
  width: 24px;
  height: 48px;
  border: 0.5px solid color-mix(in srgb, var(--cf-border, rgba(0,0,0,0.1)) 60%, transparent);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 94%, transparent);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: rgba(0,0,0,0.3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 12;
  transition: left 0.25s, right 0.25s, color 0.15s, background 0.15s;
}
.sidebar-toggle:hover { color: rgb(52,208,188); border-color: rgba(52,208,188,0.4); background: #fff; }
.toc-toggle {
  left: 220px;
  border-radius: 0 8px 8px 0;
}
.toc-toggle.collapsed {
  left: 36px;
}
.ai-toggle {
  right: var(--panel-width, 300px);
  border-radius: 8px 0 0 8px;
}
.ai-toggle.collapsed {
  right: 36px;
}

/* ---- Center Content ---- */
.reader-main {
  flex: 1;
  padding: 24px 40px 32px;
  min-width: 0;
  overflow-y: auto;
  margin-left: 220px;
  margin-right: var(--panel-width, 300px);
  transition: margin 0.25s;
}
.reader-layout.toc-collapsed .reader-main { margin-left: 36px; }
.reader-layout.ai-collapsed .reader-main { margin-right: 36px; }
.reader-layout.toc-collapsed.ai-collapsed .reader-main { margin-left: 36px; margin-right: 36px; }

/* Toolbar */
.reader-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.reader-lesson-title {
  font-size: 24px;
  font-weight: 700;
  margin: 0;
  color: rgba(0,0,0,0.85);
  flex: 1;
  min-width: 0;
}
.reader-toolbar-actions { display: flex; gap: 8px; flex-shrink: 0; }
.reader-note-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 16px;
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 10px;
  background: #fff;
  color: rgba(0,0,0,0.55);
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
  white-space: nowrap;
}
.reader-note-btn:hover,
.reader-note-btn.active {
  border-color: rgb(52,208,188);
  color: rgb(52,208,188);
  background: rgba(52,208,188,0.04);
}

.reader-content { line-height: 1.9; font-size: 15px; color: rgba(0,0,0,0.8); max-width: 780px; }
.reader-content :deep(h1), .reader-content :deep(h2), .reader-content :deep(h3) { margin: 20px 0 10px; }
.reader-content :deep(p) { margin: 10px 0; }
.reader-content :deep(code) { background: rgba(0,0,0,0.04); padding: 2px 6px; border-radius: 4px; font-size: 13px; }
.reader-content :deep(pre) { background: rgba(0,0,0,0.03); padding: 16px; border-radius: 12px; overflow-x: auto; }
.reader-content :deep(pre code) { background: none; padding: 0; }
.reader-content :deep(img) { max-width: 100%; border-radius: 8px; }
.reader-content :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; }
.reader-content :deep(th), .reader-content :deep(td) { border: 1px solid rgba(0,0,0,0.1); padding: 8px 12px; font-size: 13px; }
.reader-content :deep(th) { background: rgba(0,0,0,0.03); }
.reader-source {
  margin-top: 40px; padding-top: 16px; border-top: 1px solid rgba(0,0,0,0.06);
  font-size: 12px; color: rgba(0,0,0,0.3);
}
.reader-source a { color: rgb(52,208,188); }

/* ---- Right Panel (AI + Notes) ---- */
.reader-ai {
  width: var(--panel-width, 300px);
  position: fixed;
  right: 0;
  top: 56px;
  height: calc(100vh - 56px);
  z-index: 10;
  border-left: 0.5px solid color-mix(in srgb, var(--cf-border, rgba(0,0,0,0.08)) 60%, transparent);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 92%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: width 0.25s;
}
.reader-ai.collapsed {
  width: 36px !important;
}

/* Resize handle */
.panel-resize-handle {
  position: fixed;
  top: 56px;
  height: calc(100vh - 56px);
  width: 6px;
  z-index: 13;
  cursor: col-resize;
  background: transparent;
  transition: background 0.15s;
}
.panel-resize-handle:hover,
.panel-resize-handle:active {
  background: rgba(52,208,188,0.25);
}

/* Tab switcher */
.rai-tabs {
  display: flex;
  margin: 12px 12px 0;
  border-radius: 10px;
  background: rgba(0,0,0,0.04);
  padding: 3px;
  gap: 2px;
  flex-shrink: 0;
}
.rai-tabs button {
  flex: 1;
  padding: 6px 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(0,0,0,0.45);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.rai-tabs button.active {
  background: #fff;
  color: rgba(0,0,0,0.75);
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

/* AI Chat */
.rai-context { margin: 8px 16px; padding: 8px 12px; border-radius: 8px; background: rgba(52,208,188,0.06); color: rgb(52,208,188); font-size: 12px; flex-shrink: 0; }
.rai-chat { flex: 1; overflow-y: auto; padding: 8px 16px; }
.rai-msg { margin-bottom: 10px; display: flex; }
.rai-msg.user { justify-content: flex-end; }
.rai-msg-bubble { max-width: 85%; padding: 8px 12px; border-radius: 12px; font-size: 12px; line-height: 1.5; white-space: pre-wrap; }
.rai-msg.user .rai-msg-bubble { background: rgb(52,208,188); color: #fff; }
.rai-msg.assistant .rai-msg-bubble { background: rgba(0,0,0,0.04); color: rgba(0,0,0,0.7); }
.rai-loading { font-size: 11px; color: rgba(0,0,0,0.3); padding: 8px 0; text-align: center; }
.rai-input { display: flex; gap: 6px; padding: 12px 16px; border-top: 1px solid rgba(0,0,0,0.04); flex-shrink: 0; }
.rai-input input { flex: 1; padding: 8px 12px; border: 1px solid rgba(0,0,0,0.06); border-radius: 10px; font-size: 12px; outline: none; font-family: inherit; }
.rai-input button { width: 34px; height: 34px; border: none; border-radius: 10px; background: rgb(52,208,188); color: #fff; display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.rai-input button:disabled { opacity: 0.4; cursor: not-allowed; }

/* Note editor */
.rai-note-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 16px 0;
  flex-shrink: 0;
}
.rai-note-title { font-size: 13px; font-weight: 600; color: rgba(0,0,0,0.65); flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rai-note-actions { display: flex; gap: 4px; flex-shrink: 0; }
.rai-note-save-btn, .rai-note-pub-btn {
  padding: 4px 10px;
  border: 1px solid rgba(0,0,0,0.1);
  border-radius: 8px;
  background: transparent;
  font-size: 11px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.rai-note-save-btn { color: rgba(0,0,0,0.5); }
.rai-note-save-btn:hover { border-color: rgba(0,0,0,0.2); background: rgba(0,0,0,0.02); }
.rai-note-pub-btn { color: #fff; background: rgb(52,208,188); border-color: rgb(52,208,188); }
.rai-note-pub-btn:hover { opacity: 0.85; }
.rai-note-save-btn:disabled, .rai-note-pub-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.rai-note-context {
  margin: 6px 16px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(52,208,188,0.06);
  color: rgb(52,208,188);
  font-size: 11px;
  flex-shrink: 0;
}
.rai-note-editor {
  flex: 1;
  margin: 8px 16px 16px;
  padding: 12px;
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 12px;
  background: rgba(255,255,255,0.6);
  font-size: 13px;
  line-height: 1.7;
  resize: none;
  outline: none;
  font-family: inherit;
  color: rgba(0,0,0,0.75);
}
.rai-note-editor:focus { border-color: rgba(52,208,188,0.3); }
.rai-note-editor::placeholder { color: rgba(0,0,0,0.2); }

/* ---- Responsive ---- */
@media (max-width: 900px) {
  .reader-layout { flex-direction: column; }
  .reader-sidebar {
    width: 100%; height: auto; position: static;
    border-right: none; border-bottom: 1px solid rgba(0,0,0,0.05); max-height: 300px;
  }
  .reader-sidebar.collapsed { width: 100%; max-height: 36px; }
  .sidebar-toggle { display: none; }
  .reader-main { margin-left: 0; margin-right: 0; padding: 16px 20px; }
  .reader-layout.toc-collapsed .reader-main,
  .reader-layout.ai-collapsed .reader-main { margin-left: 0; margin-right: 0; }
  .reader-ai {
    width: 100%;
    position: fixed;
    right: 0;
    top: auto;
    bottom: 0;
    height: 360px;
  }
  .reader-ai.collapsed { height: 36px; width: 100%; }
}

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .reader-back {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .reader-tut-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .reader-tut-meta,
html[data-theme='dark'] .reader-loading {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .reader-chapter {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .sidebar-toggle:hover {
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .reader-lesson-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .reader-note-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-card, #0c0c0d);
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .reader-content {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .reader-content :deep(code) {
  background: rgba(255,255,255,0.06);
}
html[data-theme='dark'] .reader-content :deep(pre) {
  background: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .reader-content :deep(th) {
  background: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .reader-content :deep(th),
html[data-theme='dark'] .reader-content :deep(td) {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
}
html[data-theme='dark'] .reader-source {
  border-top-color: rgba(255,255,255,0.06);
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .rai-tabs {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
}
html[data-theme='dark'] .rai-tabs button {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .rai-tabs button.active {
  background: var(--cf-bg-card, #0c0c0d);
  color: var(--cf-text-primary, #f8fafc);
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}
html[data-theme='dark'] .rai-msg.assistant .rai-msg-bubble {
  background: rgba(255,255,255,0.08);
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .rai-loading {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .rai-input {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .rai-input input {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .rai-input input::placeholder {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .rai-note-title {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .rai-note-save-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .rai-note-save-btn:hover {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
}
html[data-theme='dark'] .rai-note-editor {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 92%, transparent);
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .rai-note-editor::placeholder {
  color: rgba(248,250,252,0.2);
}
</style>
