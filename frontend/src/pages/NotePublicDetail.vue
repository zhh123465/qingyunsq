<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { marked } from 'marked';
import { getPublicNote, deleteNote, createConversation, sendMessage, listConversations, getConversationMessages } from '@/api/ai-workspace';
import { aiRagChat, getRateLimitStatus } from '@/api/ai';
import { useAuthStore } from '@/stores/auth';
import type { NoteVO, ConversationVO, MessageVO } from '@/types/ai-workspace';
import type { RateLimitStatus } from '@/types/ai';

const route = useRoute();
const router = useRouter();
const toast = useMessage();
const authStore = useAuthStore();

const note = ref<NoteVO | null>(null);
const loading = ref(false);
const loadError = ref('');
const deleting = ref(false);
const showDeleteConfirm = ref(false);
const previewHtml = ref('');
// Conversation-based AI chat
const aiDraft = ref('');
const aiLoading = ref(false);
const aiMessages = ref<{ role: string; content: string }[]>([]);
const aiChatRef = ref<HTMLElement | null>(null);
const conv = ref<ConversationVO | null>(null);
const conversations = ref<ConversationVO[]>([]);
const showHistory = ref(false);
const rateLimit = ref<RateLimitStatus | null>(null);
// History search
const historySearch = ref('');
const searchResults = ref<{ conv: ConversationVO; matches: { role: string; content: string }[] }[]>([]);
let searchTimer: ReturnType<typeof setTimeout> | null = null;

const noteId = route.params.id as string;
const isOwner = () => note.value?.ownerId === authStore.user?.id;

// Markdown TOC
interface TocItem { id: string; text: string; level: number; }
const tocItems = ref<TocItem[]>([]);
const activeTocId = ref('');
const isTocCollapsed = ref(false);
const contentRef = ref<HTMLElement | null>(null);

function extractToc(md: string): TocItem[] {
  const items: TocItem[] = [];
  const re = /^(#{1,3})\s+(.+)$/gm;
  let m;
  let i = 0;
  while ((m = re.exec(md)) !== null) {
    items.push({ id: `heading-${i++}`, text: m[2].replace(/[`*_~\[\]]/g, '').trim(), level: m[1].length });
  }
  return items;
}

function renderMdWithIds(md: string): string {
  let html = marked.parse(md, { async: false }) as string;
  // 后处理注入 heading id：<h1> -> <h1 id="heading-N">
  let idx = 0;
  html = html.replace(/<(h[1-3])>/g, (_m, tag) => `<${tag} id="heading-${idx++}">`);
  return html;
}

function scrollToHeading(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  activeTocId.value = id;
}

function onContentScroll() {
  const els = tocItems.value.map((t) => document.getElementById(t.id)).filter(Boolean) as HTMLElement[];
  const top = (contentRef.value?.scrollTop || 0) + 80;
  for (let i = els.length - 1; i >= 0; i--) {
    if (els[i].offsetTop <= top) { activeTocId.value = tocItems.value[i].id; return; }
  }
  if (tocItems.value.length > 0) activeTocId.value = tocItems.value[0].id;
}

// AI panel resize + collapse
const panelWidth = ref(320);
const isAiCollapsed = ref(false);
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

onMounted(load);
onUnmounted(stopResize);

async function load() {
  loading.value = true;
  try {
    note.value = await getPublicNote(noteId);
  } catch (e: any) {
    console.error('[NotePublicDetail] 加载笔记失败:', e);
    loadError.value = e?.message || '笔记不存在或未公开';
    loading.value = false;
    return;
  }
  const md = note.value.content || '';
  tocItems.value = extractToc(md);
  try {
    previewHtml.value = renderMdWithIds(md);
  } catch (e: any) {
    console.error('[NotePublicDetail] Markdown 解析失败:', e);
    previewHtml.value = `<pre>${md.slice(0, 10000)}</pre>`;
  }
  aiMessages.value = [{
    role: 'assistant',
    content: `已加载笔记「${note.value.title}」，你可以针对这篇笔记向我提问。`,
  }];
  loading.value = false;
  loadConversations();
  loadRateLimit();
  // Try to restore last conversation
  if (conversations.value.length > 0 && !conv.value) {
    const last = conversations.value[0];
    conv.value = last;
    await loadConversationMessages(last.id);
  }
}

function handleDelete() {
  showDeleteConfirm.value = true;
}

async function confirmDelete() {
  showDeleteConfirm.value = false;
  deleting.value = true;
  try {
    await deleteNote(noteId);
    toast.success('已删除');
    router.replace('/learning');
  } catch (e: any) {
    toast.error(e?.message || '删除失败');
  } finally {
    deleting.value = false;
  }
}

async function loadRateLimit() {
  try { rateLimit.value = await getRateLimitStatus(); } catch { /* ignore */ }
}

async function loadConversations() {
  try {
    const res = await listConversations(1, 50);
    conversations.value = res.items;
  } catch { /* ignore */ }
}

function onHistorySearchInput() {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(doSearch, 300);
}

async function doSearch() {
  const q = historySearch.value.trim().toLowerCase();
  if (!q) { searchResults.value = []; return; }
  const results: typeof searchResults.value = [];
  for (const c of conversations.value) {
    try {
      const msgs = await getConversationMessages(c.id);
      const matches = msgs.filter((m) => m.content?.toLowerCase().includes(q));
      if (matches.length > 0) {
        results.push({ conv: c, matches: matches.map((m) => ({ role: m.role, content: m.content })) });
      }
    } catch { /* skip */ }
  }
  searchResults.value = results;
}

async function loadConversationMessages(convId: string) {
  try {
    const msgs = await getConversationMessages(convId);
    aiMessages.value = msgs.map((m) => ({ role: m.role, content: m.content }));
  } catch { /* ignore */ }
}

async function handleAiSend() {
  const text = aiDraft.value.trim();
  if (!text || aiLoading.value || !note.value) return;
  aiDraft.value = '';
  aiLoading.value = true;
  aiMessages.value.push({ role: 'user', content: text });
  const context = `【笔记：${note.value.title}】\n${note.value.content?.slice(0, 8000) || ''}`;
  try {
    if (!conv.value) {
      const c = await createConversation({ title: text.slice(0, 40), model: 'mimo-v2.5' });
      conv.value = c;
      conversations.value.unshift(c);
    }
    const { assistantMessage } = await sendMessage(conv.value.id, { content: text, model: 'mimo-v2.5', attachedContext: context });
    aiMessages.value.push({ role: 'assistant', content: assistantMessage.content || '抱歉，暂时无法回答。' });
    loadRateLimit();
  } catch (e: any) {
    console.error('[NotePublicDetail] AI 请求失败:', e?.response?.status, e?.response?.data || e?.message || e);
    aiMessages.value.push({ role: 'assistant', content: 'AI 响应失败，请稍后重试。' });
  } finally { aiLoading.value = false; }
}

function newConversation() {
  conv.value = null;
  aiMessages.value = [{
    role: 'assistant',
    content: note.value ? `已加载笔记「${note.value.title}」，你可以针对这篇笔记向我提问。` : '新建对话',
  }];
}

async function switchConversation(c: ConversationVO) {
  conv.value = c;
  aiLoading.value = true;
  await loadConversationMessages(c.id);
  aiLoading.value = false;
  showHistory.value = false;
}
</script>

<template>
  <div class="public-detail" :class="{ 'toc-collapsed': isTocCollapsed, 'ai-collapsed': isAiCollapsed }" :style="{ '--ai-panel-width': panelWidth + 'px' }">
    <div v-if="loading" class="pd-loading">加载中...</div>
    <div v-else-if="loadError" class="pd-error">
      <p>笔记加载失败</p>
      <p class="pd-error-hint">{{ loadError }}</p>
      <button class="notes-goto-btn" @click="router.push('/learning')">返回学习页</button>
    </div>
    <div v-else-if="!note" class="pd-loading">笔记不存在</div>
    <template v-else-if="note">
      <!-- Left TOC sidebar -->
      <aside class="pd-toc" :class="{ collapsed: isTocCollapsed }" v-if="tocItems.length > 1">
        <template v-if="!isTocCollapsed">
          <div class="pd-toc-title">目录</div>
          <nav class="pd-toc-nav">
            <button
              v-for="item in tocItems"
              :key="item.id"
              :class="['pd-toc-item', `pd-toc-lv${item.level}`, { active: activeTocId === item.id }]"
              @click="scrollToHeading(item.id)"
            >{{ item.text }}</button>
          </nav>
        </template>
      </aside>
      <!-- Toggle outside aside to escape backdrop-filter containing block -->
      <button class="pd-toc-toggle" :class="{ collapsed: isTocCollapsed }" @click="isTocCollapsed = !isTocCollapsed" :title="isTocCollapsed ? '展开目录' : '收起目录'">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline v-if="isTocCollapsed" points="9 18 15 12 9 6" />
          <polyline v-else points="15 18 9 12 15 6" />
        </svg>
      </button>

      <main class="pd-main" ref="contentRef" @scroll="onContentScroll">
        <button class="pd-back" @click="router.push('/learning')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
          返回学习页
        </button>
        <h1 class="pd-title">{{ note.title }}</h1>
        <!-- Source banner: 外部同步笔记显式标注原作者与原文 URL -->
        <div
          v-if="note.sourceUrl"
          class="pd-source-banner"
        >
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6" />
            <polyline points="15 3 21 3 21 9" />
            <line x1="10" y1="14" x2="21" y2="3" />
          </svg>
          <span>
            本文由 <b>{{ note.sourceAuthor || '原作者' }}</b> 授权同步收录 · 原文：
            <a
              :href="note.sourceUrl"
              target="_blank"
              rel="noopener noreferrer"
            >{{ note.sourceName || note.sourceUrl }}</a>
          </span>
        </div>
        <!-- Author row -->
        <div class="pd-author" @click="router.push('/users/' + note.ownerId)">
          <span class="pd-author-avatar">
            <img v-if="note.ownerAvatar" :src="note.ownerAvatar" width="28" height="28" />
            <span v-else class="pd-author-avatar-fallback">{{ note.ownerName?.charAt(0) || '?' }}</span>
          </span>
          <span class="pd-author-name">{{ note.ownerName || '未知用户' }}</span>
        </div>
        <div class="pd-meta">
          <span>{{ note.updatedAt?.slice(0, 10) }}</span>
          <span>·</span>
          <span>{{ note.viewCount || 0 }} 次阅读</span>
          <template v-if="isOwner()">
            <span>·</span>
            <button class="pd-action-btn" @click="router.push('/ai/notes')">编辑</button>
            <button class="pd-action-btn pd-action-del" :disabled="deleting" @click="handleDelete">{{ deleting ? '删除中...' : '删除' }}</button>
          </template>
        </div>
        <!-- Tags -->
        <div v-if="note.tags?.length" class="pd-tags">
          <span v-for="tag in note.tags" :key="tag" class="pd-tag">{{ tag }}</span>
        </div>
        <div class="pd-content" v-html="previewHtml" />
      </main>

      <!-- AI panel resize handle -->
      <div v-if="!isAiCollapsed" class="pd-resize-handle" :style="{ right: panelWidth + 'px' }" @mousedown="startResize" />

      <aside class="pd-ai" :class="{ collapsed: isAiCollapsed }" :style="{ width: panelWidth + 'px' }">
        <template v-if="!isAiCollapsed">
          <div class="pd-ai-header">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M12 2l2.4 7.2h7.6l-6 4.8 2.4 7.2-6-4.8-6 4.8 2.4-7.2-6-4.8h7.6z"/></svg>
            <span>AI 问答</span>
            <div class="pd-ai-header-actions">
              <button class="pd-ai-hdr-btn" @click="showHistory = !showHistory" title="历史对话">历史</button>
              <button class="pd-ai-hdr-btn" @click="newConversation" title="新建对话">+</button>
            </div>
          </div>

          <!-- History panel -->
          <div v-if="showHistory" class="pd-ai-history">
            <div class="pd-ai-hist-search">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
              <input v-model="historySearch" type="text" placeholder="搜索对话内容..." @input="onHistorySearchInput" />
            </div>
            <!-- Search results -->
            <template v-if="historySearch.trim()">
              <div v-if="searchResults.length === 0" class="pd-ai-hist-empty">无匹配结果</div>
              <button
                v-for="r in searchResults"
                :key="r.conv.id"
                class="pd-ai-hist-item"
                @click="switchConversation(r.conv)"
              >
                <span class="pd-ai-hist-title">{{ r.conv.title || '对话' }}</span>
                <span class="pd-ai-hist-meta">匹配 {{ r.matches.length }} 条 · {{ r.conv.updatedAt?.slice(0, 10) || '' }}</span>
                <span class="pd-ai-hist-snippet" v-for="m in r.matches.slice(0, 2)" :key="m.content">{{ m.role === 'user' ? 'Q' : 'A' }}: {{ m.content.slice(0, 80) }}{{ m.content.length > 80 ? '...' : '' }}</span>
              </button>
            </template>
            <!-- Conversation list -->
            <template v-else-if="conversations.length > 0">
              <button
                v-for="c in conversations"
                :key="c.id"
                :class="['pd-ai-hist-item', { active: conv?.id === c.id }]"
                @click="switchConversation(c)"
              >
                <span class="pd-ai-hist-title">{{ c.title || '对话' }}</span>
                <span class="pd-ai-hist-meta">{{ c.messageCount || 0 }} 条 · {{ c.updatedAt?.slice(0, 10) || '' }}</span>
              </button>
            </template>
            <div v-else class="pd-ai-hist-empty">暂无历史对话</div>
          </div>

          <div class="pd-ai-context" v-if="conv">
            对话 #{{ conv.id.slice(-6) }} · 笔记「{{ note.title }}」
          </div>
          <div class="pd-ai-context" v-else>
            新对话 · 基于笔记「{{ note.title }}」提问
          </div>

          <!-- Rate limit -->
          <div v-if="rateLimit" class="pd-ai-rate">
            <span class="pd-ai-rate-bar">
              <span class="pd-ai-rate-fill" :style="{ width: Math.max(0, (rateLimit.normal.remaining / rateLimit.normal.limit) * 100) + '%' }" />
            </span>
            <span class="pd-ai-rate-text">普通模型 剩余 {{ rateLimit.normal.remaining }}/{{ rateLimit.normal.limit }} 次</span>
          </div>

          <div class="pd-ai-chat" ref="aiChatRef">
            <div v-for="(msg, i) in aiMessages" :key="i" :class="['pd-msg', msg.role]">
              <div class="pd-msg-bubble">{{ msg.content }}</div>
            </div>
            <div v-if="aiLoading" class="pd-ai-loading">AI 思考中...</div>
          </div>
          <div class="pd-ai-input">
            <input v-model="aiDraft" placeholder="询问笔记内容..." @keydown.enter="handleAiSend" />
            <button @click="handleAiSend" :disabled="!aiDraft.trim() || aiLoading">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
            </button>
          </div>
        </template>
        <button class="pd-ai-toggle" @click="isAiCollapsed = !isAiCollapsed" :title="isAiCollapsed ? '展开面板' : '收起面板'">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
            <polyline v-if="isAiCollapsed" points="15 18 9 12 15 6" />
            <polyline v-else points="9 18 15 12 9 6" />
          </svg>
        </button>
      </aside>
    </template>

    <!-- Delete confirmation overlay -->
    <div v-if="showDeleteConfirm" class="pd-confirm-overlay" @click.self="showDeleteConfirm = false">
      <div class="pd-confirm-box">
        <p class="pd-confirm-title">确认删除</p>
        <p class="pd-confirm-body">删除后无法恢复，确定要删除这篇笔记吗？</p>
        <div class="pd-confirm-actions">
          <button class="pd-confirm-cancel" @click="showDeleteConfirm = false">取消</button>
          <button class="pd-confirm-ok" :disabled="deleting" @click="confirmDelete">{{ deleting ? '删除中...' : '删除' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.public-detail { display: flex; min-height: calc(100vh - 64px); padding: 24px 32px; gap: 28px; max-width: 1200px; margin: 0 auto; }
.pd-loading { flex: 1; display: flex; align-items: center; justify-content: center; color: rgba(0,0,0,0.35); }
.pd-error { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: rgba(0,0,0,0.5); }
.pd-error-hint { font-size: 13px; color: rgba(0,0,0,0.3); }
.notes-goto-btn {
  margin-top: 12px;
  padding: 8px 20px;
  border: none;
  border-radius: 10px;
  background: rgb(52,208,188);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
}
/* Left TOC sidebar */
.pd-toc {
  width: 200px;
  flex-shrink: 0;
  position: fixed;
  left: 0;
  top: 56px;
  height: calc(100vh - 56px);
  border-right: 0.5px solid rgba(0,0,0,0.06);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 92%, transparent);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  padding: 18px 14px;
  overflow-y: auto;
  z-index: 9;
  transition: width 0.25s;
}
.pd-toc.collapsed {
  width: 36px;
  padding: 18px 4px;
  overflow: hidden;
}
.pd-toc-title {
  font-size: 12px;
  font-weight: 600;
  color: rgba(0,0,0,0.35);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 12px;
}
.pd-toc-nav { display: flex; flex-direction: column; gap: 1px; }
.pd-toc-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 5px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(0,0,0,0.45);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  line-height: 1.4;
  transition: all 0.15s;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pd-toc-lv2 { padding-left: 20px; }
.pd-toc-lv3 { padding-left: 32px; font-size: 11px; }
.pd-toc-item:hover { background: rgba(52,208,188,0.06); color: rgb(52,208,188); }
.pd-toc-item.active { background: rgba(52,208,188,0.1); color: rgb(52,208,188); font-weight: 600; }
.pd-toc-toggle {
  position: fixed;
  top: 50%;
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
  left: 200px;
  transition: left 0.25s, color 0.15s;
}
.pd-toc-toggle:hover { color: rgb(52,208,188); border-color: rgba(52,208,188,0.4); background: #fff; }
.public-detail.toc-collapsed .pd-toc-toggle { left: 36px; }
.public-detail.toc-collapsed .pd-main,
.public-detail.toc-collapsed .pd-error,
.public-detail.toc-collapsed .pd-loading { padding-left: 56px; }

.pd-main {
  flex: 1; min-width: 0;
  padding-left: calc(200px + 24px);
  padding-right: calc(var(--ai-panel-width, 320px) + 20px);
  transition: padding-left 0.25s, padding-right 0.25s;
  overflow-y: auto;
  height: calc(100vh - 56px);
}
.public-detail.ai-collapsed .pd-main { padding-right: 56px; }
.public-detail:not(.toc-collapsed) .pd-main { /* uses calc above */ }
/* No TOC: remove left padding */
.pd-toc:not(:has(.pd-toc-nav)) ~ .pd-main { padding-left: 32px; }
.pd-back {
  display: inline-flex; align-items: center; gap: 6px;
  border: none; background: transparent; color: rgba(0,0,0,0.4);
  font-size: 13px; cursor: pointer; font-family: inherit; padding: 0; margin-bottom: 16px;
}
.pd-back:hover { color: rgb(52,208,188); }
.pd-title { font-size: 26px; font-weight: 700; margin: 0 0 12px; color: rgba(0,0,0,0.85); }
.pd-source-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(52,208,188,0.08);
  border: 1px solid rgba(52,208,188,0.22);
  color: rgba(0,0,0,0.65);
  font-size: 13px;
  line-height: 1.5;
}
.pd-source-banner svg {
  flex-shrink: 0;
  color: rgb(52,208,188);
}
.pd-source-banner b { color: rgba(0,0,0,0.85); font-weight: 600; }
.pd-source-banner a {
  color: rgb(52,208,188);
  text-decoration: underline;
  text-decoration-color: rgba(52,208,188,0.35);
  word-break: break-all;
}
.pd-source-banner a:hover { text-decoration-color: rgb(52,208,188); }
.pd-author {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: opacity 0.15s;
}
.pd-author:hover { opacity: 0.75; }
.pd-author-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.pd-author-avatar img { width: 100%; height: 100%; object-fit: cover; }
.pd-author-avatar-fallback {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(52,208,188,0.12);
  color: rgb(52,208,188);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.pd-author-name { font-size: 13px; color: rgba(0,0,0,0.55); font-weight: 500; }
.pd-meta { font-size: 12px; color: rgba(0,0,0,0.35); display: flex; gap: 6px; align-items: center; margin-bottom: 12px; }
.pd-action-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: rgb(52,208,188);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.pd-action-btn:hover { text-decoration: underline; }
.pd-action-del { color: rgb(255,77,79); }
.pd-action-del:disabled { opacity: 0.5; cursor: not-allowed; }
.pd-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 20px; }
.pd-tag {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 12px;
  background: rgba(52,208,188,0.06);
  color: rgb(52,208,188);
}
.pd-content {
  line-height: 1.9; font-size: 15px; color: rgba(0,0,0,0.8);
  max-width: 720px;
}
.pd-content :deep(h1) { font-size: 24px; margin: 24px 0 12px; }
.pd-content :deep(h2) { font-size: 20px; margin: 20px 0 10px; }
.pd-content :deep(h3) { font-size: 17px; margin: 16px 0 8px; }
.pd-content :deep(p) { margin: 10px 0; }
.pd-content :deep(code) { background: rgba(0,0,0,0.04); padding: 2px 6px; border-radius: 4px; font-size: 13px; }
.pd-content :deep(pre) { background: rgba(0,0,0,0.03); padding: 16px; border-radius: 12px; overflow-x: auto; }
.pd-content :deep(pre code) { background: none; padding: 0; }
.pd-content :deep(blockquote) { border-left: 3px solid rgb(52,208,188); padding-left: 14px; color: rgba(0,0,0,0.5); margin: 12px 0; }
.pd-content :deep(img) { max-width: 100%; border-radius: 8px; }

/* AI sidebar */
.pd-ai {
  width: var(--ai-panel-width, 320px);
  position: fixed; right: 0; top: 56px; height: calc(100vh - 56px);
  border-left: 0.5px solid rgba(0,0,0,0.06);
  background: color-mix(in srgb, var(--cf-bg-card, #fff) 92%, transparent);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  display: flex; flex-direction: column; overflow: hidden; z-index: 10;
  transition: width 0.25s;
}
.pd-ai.collapsed {
  width: 36px !important;
}

/* AI toggle button */
.pd-ai-toggle {
  position: fixed;
  top: 50%;
  transform: translateY(-50%);
  width: 24px;
  height: 48px;
  border: 0.5px solid rgba(0,0,0,0.08);
  border-radius: 8px 0 0 8px;
  background: rgba(255,255,255,0.94);
  color: rgba(0,0,0,0.3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 12;
  right: var(--ai-panel-width, 320px);
  transition: right 0.25s, color 0.15s;
}
.pd-ai-toggle:hover { color: rgb(52,208,188); border-color: rgba(52,208,188,0.4); background: #fff; }
.public-detail.ai-collapsed .pd-ai-toggle { right: 36px; }

/* Resize handle */
.pd-resize-handle {
  position: fixed;
  top: 56px;
  height: calc(100vh - 56px);
  width: 6px;
  z-index: 13;
  cursor: col-resize;
  background: transparent;
  transition: background 0.15s;
}
.pd-resize-handle:hover { background: rgba(52,208,188,0.25); }
.pd-ai-header { display: flex; align-items: center; gap: 8px; padding: 16px 18px 0; color: rgb(52,208,188); font-size: 12px; font-weight: 600; text-transform: uppercase; }
.pd-ai-header-actions { display: flex; gap: 4px; margin-left: auto; }
.pd-ai-hdr-btn {
  padding: 3px 10px; border: 1px solid rgba(0,0,0,0.1); border-radius: 8px;
  background: transparent; color: rgba(0,0,0,0.4); font-size: 11px; cursor: pointer;
  font-family: inherit; transition: all 0.15s;
}
.pd-ai-hdr-btn:hover { border-color: rgb(52,208,188); color: rgb(52,208,188); }

/* History dropdown */
.pd-ai-history {
  max-height: 280px; overflow-y: auto; margin: 4px 12px 0;
  border: 1px solid rgba(0,0,0,0.06); border-radius: 10px; background: #fff;
}
.pd-ai-hist-search {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px; border-bottom: 1px solid rgba(0,0,0,0.04);
  color: rgba(0,0,0,0.3); position: sticky; top: 0; background: #fff;
}
.pd-ai-hist-search input {
  flex: 1; border: none; outline: none; font-size: 12px; font-family: inherit;
  background: transparent; color: rgba(0,0,0,0.7);
}
.pd-ai-hist-search input::placeholder { color: rgba(0,0,0,0.25); }
.pd-ai-hist-empty {
  padding: 16px; text-align: center; font-size: 12px; color: rgba(0,0,0,0.3);
}
.pd-ai-hist-item {
  display: flex; flex-direction: column; gap: 2px; width: 100%; text-align: left;
  padding: 8px 12px; border: none; border-bottom: 1px solid rgba(0,0,0,0.03);
  background: transparent; cursor: pointer;
  font-family: inherit; transition: background 0.1s;
}
.pd-ai-hist-item:hover { background: rgba(52,208,188,0.05); }
.pd-ai-hist-item.active { background: rgba(52,208,188,0.08); }
.pd-ai-hist-title { font-size: 12px; color: rgba(0,0,0,0.65); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pd-ai-hist-meta { font-size: 10px; color: rgba(0,0,0,0.3); }
.pd-ai-hist-snippet {
  font-size: 11px; color: rgba(0,0,0,0.35); line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-top: 1px;
}

/* Rate limit bar */
.pd-ai-rate {
  margin: 4px 16px 0; display: flex; align-items: center; gap: 8px;
}
.pd-ai-rate-bar {
  width: 60px; height: 3px; border-radius: 2px; background: rgba(0,0,0,0.06); flex-shrink: 0; overflow: hidden;
}
.pd-ai-rate-fill {
  display: block; height: 100%; border-radius: 2px; background: rgb(52,208,188); transition: width 0.3s;
}
.pd-ai-rate-text { font-size: 10px; color: rgba(0,0,0,0.3); white-space: nowrap; }
.pd-ai-context { margin: 8px 16px; padding: 8px 12px; border-radius: 8px; background: rgba(52,208,188,0.06); color: rgb(52,208,188); font-size: 12px; }
.pd-ai-chat { flex: 1; overflow-y: auto; padding: 8px 16px; }
.pd-msg { margin-bottom: 10px; display: flex; }
.pd-msg.user { justify-content: flex-end; }
.pd-msg-bubble { max-width: 85%; padding: 8px 12px; border-radius: 12px; font-size: 12px; line-height: 1.5; white-space: pre-wrap; }
.pd-msg.user .pd-msg-bubble { background: rgb(52,208,188); color: #fff; }
.pd-msg.assistant .pd-msg-bubble { background: rgba(0,0,0,0.04); color: rgba(0,0,0,0.7); }
.pd-ai-loading { font-size: 11px; color: rgba(0,0,0,0.3); padding: 8px 0; text-align: center; }
.pd-ai-input { display: flex; gap: 6px; padding: 12px 16px; border-top: 1px solid rgba(0,0,0,0.04); }
.pd-ai-input input { flex: 1; padding: 8px 12px; border: 1px solid rgba(0,0,0,0.06); border-radius: 10px; font-size: 12px; outline: none; font-family: inherit; }
.pd-ai-input button { width: 34px; height: 34px; border: none; border-radius: 10px; background: rgb(52,208,188); color: #fff; display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; }
.pd-ai-input button:disabled { opacity: 0.4; cursor: not-allowed; }

/* Delete confirmation */
.pd-confirm-overlay {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(0,0,0,0.3);
  display: flex; align-items: center; justify-content: center;
}
.pd-confirm-box {
  background: #fff; border-radius: 16px; padding: 24px; max-width: 360px; width: 90%;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
}
.pd-confirm-title { font-size: 16px; font-weight: 600; margin: 0 0 8px; color: rgba(0,0,0,0.85); }
.pd-confirm-body { font-size: 13px; color: rgba(0,0,0,0.5); margin: 0 0 20px; line-height: 1.5; }
.pd-confirm-actions { display: flex; justify-content: flex-end; gap: 8px; }
.pd-confirm-cancel {
  padding: 7px 18px; border: 1px solid rgba(0,0,0,0.1); border-radius: 10px;
  background: transparent; font-size: 13px; cursor: pointer; font-family: inherit; color: rgba(0,0,0,0.5);
}
.pd-confirm-ok {
  padding: 7px 18px; border: none; border-radius: 10px;
  background: rgb(255,77,79); color: #fff; font-size: 13px; cursor: pointer; font-family: inherit;
}
.pd-confirm-ok:disabled { opacity: 0.5; cursor: not-allowed; }

@media (max-width: 800px) {
  .public-detail { flex-direction: column; }
  .pd-toc { display: none; }
  .pd-main { padding-left: 20px; padding-right: 20px; height: auto; }
  .pd-ai { width: 100%; position: fixed; right: 0; top: auto; bottom: 0; height: 360px; }
}

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .pd-loading {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .pd-error {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .pd-error-hint {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .pd-toc-title {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .pd-toc-item {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .pd-toc-toggle,
html[data-theme='dark'] .pd-ai-toggle {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 94%, transparent);
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .pd-toc-toggle:hover,
html[data-theme='dark'] .pd-ai-toggle:hover {
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .pd-back {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .pd-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .pd-author-name {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .pd-meta {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .pd-content {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .pd-content :deep(code) {
  background: rgba(255,255,255,0.06);
}
html[data-theme='dark'] .pd-content :deep(pre) {
  background: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .pd-content :deep(blockquote) {
  color: rgba(248,250,252,0.55);
}
html[data-theme='dark'] .pd-ai-hdr-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .pd-ai-history {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-card, #0c0c0d);
}
html[data-theme='dark'] .pd-ai-hist-search {
  border-bottom-color: rgba(255,255,255,0.04);
  background: var(--cf-bg-card, #0c0c0d);
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .pd-ai-hist-search input {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .pd-ai-hist-search input::placeholder {
  color: rgba(248,250,252,0.25);
}
html[data-theme='dark'] .pd-ai-hist-empty {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .pd-ai-hist-item {
  border-bottom-color: rgba(255,255,255,0.03);
}
html[data-theme='dark'] .pd-ai-hist-title {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .pd-ai-hist-meta {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .pd-ai-hist-snippet {
  color: rgba(248,250,252,0.4);
}
html[data-theme='dark'] .pd-ai-rate-bar {
  background: rgba(255,255,255,0.08);
}
html[data-theme='dark'] .pd-ai-rate-text {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .pd-msg.assistant .pd-msg-bubble {
  background: rgba(255,255,255,0.08);
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .pd-ai-loading {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .pd-ai-input {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .pd-ai-input input {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .pd-ai-input input::placeholder {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .pd-confirm-box {
  background: var(--cf-bg-card, #0c0c0d);
  box-shadow: 0 8px 32px rgba(0,0,0,0.4);
}
html[data-theme='dark'] .pd-confirm-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .pd-confirm-body {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .pd-confirm-cancel {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
</style>
