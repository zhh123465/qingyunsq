<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import KnowledgeSidebar from './KnowledgeSidebar.vue';
import { aiRagChat, getRateLimitStatus } from '@/api/ai';
import { sendMessage, createConversation, listConversations, getConversationMessages, getKnowledgeBase } from '@/api/ai-workspace';
import { AI_MODELS, DEFAULT_MODEL } from '@/types/ai';
import type { RateLimitStatus } from '@/types/ai';
import type { MessageVO, ConversationVO } from '@/types/ai-workspace';

const route = useRoute();
const toast = useMessage();

const draft = ref('');
const loading = ref(false);
const messages = ref<{ role: string; content: string }[]>([]);
const selectedModel = ref(DEFAULT_MODEL);
const rateLimit = ref<RateLimitStatus | null>(null);
const modelMenuOpen = ref(false);

function selectModel(id: string) { selectedModel.value = id; modelMenuOpen.value = false; }
const chatStreamRef = ref<HTMLElement | null>(null);
const conv = ref<ConversationVO | null>(null);
const router = useRouter();
const kbId = computed(() => (route.query.kb as string) || '');
const kbName = ref('');

const sidebarCollapsed = inject('sidebarCollapsed', ref(false));
const panelWidth = ref(320);
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

const suggestions = [
  '总结最近上传内容',
  '帮我生成学习路线',
  '分析我的笔记',
  '生成思维导图',
];

async function handleSend() {
  const text = draft.value.trim();
  if (!text || loading.value) return;
  draft.value = '';
  loading.value = true;

  messages.value.push({ role: 'user', content: text });
  await scrollDown();

  try {
    if (conv.value) {
      const result = await sendMessage(conv.value.id, { content: text });
      if (result.assistantMessage) {
        messages.value.push({ role: 'assistant', content: result.assistantMessage.content });
      }
    } else {
      // No conversation yet — use aiRagChat directly
      const resp = await aiRagChat(
        [{ role: 'user', content: text }],
        undefined,
        selectedModel.value,
        ['web-search'],
      );
      messages.value.push({ role: 'assistant', content: resp.reply || '收到你的问题，但我暂时无法回答。' });
      await loadRateLimit();
    }
  } catch (e: any) {
    toast.error(e?.message || '发送失败');
  } finally {
    loading.value = false;
    await scrollDown();
  }
}

async function handleSuggestion(s: string) {
  draft.value = s;
  await handleSend();
}

async function scrollDown() {
  await nextTick();
  if (chatStreamRef.value) {
    chatStreamRef.value.scrollTop = chatStreamRef.value.scrollHeight;
  }
}

// Try to load existing conversation for this KB
async function loadRateLimit() {
  try { rateLimit.value = await getRateLimitStatus(); } catch { /* ignore */ }
}

function onDocClick() { modelMenuOpen.value = false; }

onMounted(async () => {
  document.addEventListener('click', onDocClick);
  loadRateLimit();
  if (kbId.value) {
    try {
      const kb = await getKnowledgeBase(kbId.value);
      kbName.value = kb.name;
    } catch { /* KB may have been deleted */ }
    try {
      const result = await listConversations(1, 10);
      const existing = result.items.find((c) => c.knowledgeBaseIds?.includes(kbId.value));
      if (existing) {
        conv.value = existing;
        const msgs = await getConversationMessages(existing.id);
        messages.value = msgs.map((m) => ({ role: m.role, content: m.content }));
        await scrollDown();
      }
    } catch { /* no existing */ }
  }
});

onUnmounted(() => { document.removeEventListener('click', onDocClick); });
</script>

<template>
  <div class="chat-layout" :class="{ 'panel-collapsed': isPanelCollapsed }" :style="{ '--panel-width': panelWidth + 'px' }">
    <KnowledgeSidebar />

    <!-- Main Content -->
    <main class="chat-main">
      <div v-if="messages.length === 0" class="chat-hero">
        <!-- Logo -->
        <div class="chat-logo">
          <svg viewBox="0 0 24 24" width="56" height="56" fill="none" stroke="rgb(52,208,188)" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
        </div>
        <h2 class="chat-hero-title">今天想了解什么？</h2>
        <p class="chat-hero-subtitle">检索您的个人知识库或开始新的对话</p>

        <!-- Search input -->
        <div class="chat-input-wrap">
          <input
            v-model="draft"
            class="chat-input"
            placeholder="输入您的问题，例如：'总结最近的学习笔记'..."
            @keydown.enter="handleSend"
          />
          <div class="chat-input-actions">
            <div class="model-pill" @click.stop="modelMenuOpen = !modelMenuOpen">
              <span class="model-pill-name">{{ AI_MODELS.find(m=>m.id===selectedModel)?.name || selectedModel }}</span>
              <svg viewBox="0 0 24 24" width="11" height="11" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
            </div>
            <div v-if="modelMenuOpen" class="model-menu" @click.stop>
              <button v-for="m in AI_MODELS" :key="m.id" :class="['model-menu-item', { active: selectedModel === m.id }]" @click="selectModel(m.id)">
                <span><strong>{{ m.name }}</strong><small>{{ m.provider }}</small></span>
                <span v-if="m.tier === 'pro'" class="model-badge-pro">Pro</span>
                <svg v-if="selectedModel === m.id" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
              </button>
            </div>
            <button class="chat-send-btn" :disabled="!draft.trim() || loading" @click="handleSend">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
            </button>
          </div>
        </div>

        <!-- Suggestions -->
        <div class="chat-suggestions">
          <button
            v-for="s in suggestions"
            :key="s"
            class="chat-suggestion-btn"
            @click="handleSuggestion(s)"
          >
            {{ s }}
          </button>
        </div>

        <!-- Rate limit info -->
        <div v-if="rateLimit" class="rate-limit-info">
          <p class="rate-limit-text">
            限流说明：Pro 模型每人每小时 10 次，普通模型 20 次。您当前模型剩余 <strong>{{ selectedModel.includes('pro') ? rateLimit.pro.remaining : rateLimit.normal.remaining }}</strong> 次
          </p>
        </div>
      </div>

      <!-- Chat messages -->
      <div v-else ref="chatStreamRef" class="chat-messages">
        <div
          v-for="(msg, i) in messages"
          :key="i"
          :class="['chat-msg', msg.role === 'user' ? 'chat-msg--user' : 'chat-msg--ai']"
        >
          <div class="chat-msg-bubble">{{ msg.content }}</div>
        </div>
        <div v-if="loading" class="chat-loading">AI 正在思考...</div>
      </div>

      <!-- Chat input (when messages exist) -->
      <div v-if="messages.length > 0" class="chat-bottom-bar">
        <div class="chat-input-bottom">
          <input v-model="draft" placeholder="继续对话..." @keydown.enter="handleSend" />
          <div class="model-pill" @click.stop="modelMenuOpen = !modelMenuOpen">
            <span class="model-pill-name">{{ AI_MODELS.find(m=>m.id===selectedModel)?.name || selectedModel }}</span>
            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="6 9 12 15 18 9"/></svg>
          </div>
          <div v-if="modelMenuOpen" class="model-menu" @click.stop>
            <button v-for="m in AI_MODELS" :key="m.id" :class="['model-menu-item', { active: selectedModel === m.id }]" @click="selectModel(m.id)">
              <span>
                <strong>{{ m.name }}</strong>
                <small>{{ m.provider }}</small>
              </span>
              <span v-if="m.tier === 'pro'" class="model-badge-pro">Pro</span>
              <svg v-if="selectedModel === m.id" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>
            </button>
          </div>
          <button :disabled="!draft.trim() || loading" @click="handleSend">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
          </button>
        </div>
        <div v-if="rateLimit" class="chat-bottom-rate">
          剩余 {{ selectedModel.includes('pro') ? rateLimit.pro.remaining : rateLimit.normal.remaining }} 次
        </div>
      </div>
    </main>

    <!-- Right Panel resize handle -->
    <div v-if="!isPanelCollapsed" class="panel-resize-handle" :style="{ right: panelWidth + 'px' }" @mousedown="startResize" />

    <!-- Right AI Panel -->
    <aside class="chat-panel" :class="{ collapsed: isPanelCollapsed }" :style="{ width: panelWidth + 'px' }">
      <template v-if="!isPanelCollapsed">
        <div class="chat-panel-header">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 2l2.4 7.2h7.6l-6 4.8 2.4 7.2-6-4.8-6 4.8 2.4-7.2-6-4.8h7.6z"/></svg>
          <span>AI 上下文</span>
        </div>
        <div class="chat-panel-section">
          <h4>当前知识库</h4>
          <router-link v-if="kbId" :to="`/ai/libraries/${kbId}`" class="chat-panel-kb">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
            <span>已选择: <strong>{{ kbName || '知识库' }}</strong></span>
          </router-link>
          <p v-else class="chat-panel-hint">
            <router-link to="/ai/libraries" class="panel-link">从"我的知识库"</router-link>选择一个知识库以增强对话
          </p>
        </div>
        <div class="chat-panel-section">
          <h4>AI 建议</h4>
          <div class="chat-panel-card chat-panel-card--gradient">
            <p class="chat-panel-card-label">智能总结</p>
            <p class="chat-panel-card-text">选择知识库后，我可以帮您总结内容、提炼要点</p>
          </div>
          <router-link :to="kbId ? `/ai/libraries/${kbId}` : '/ai/libraries'" class="chat-panel-card chat-panel-card--clickable">
            <p class="chat-panel-card-text">尝试上传文档或笔记来构建您的知识库</p>
          </router-link>
        </div>
        <div class="chat-panel-input">
          <input v-model="draft" placeholder="向 AI 追问..." @keydown.enter="handleSend" />
          <button @click="handleSend" :disabled="!draft.trim() || loading">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
          </button>
        </div>
      </template>
      <button class="panel-toggle-btn" @click="isPanelCollapsed = !isPanelCollapsed" :title="isPanelCollapsed ? '展开面板' : '收起面板'">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
          <polyline v-if="isPanelCollapsed" points="15 18 9 12 15 6" />
          <polyline v-else points="9 18 15 12 9 6" />
        </svg>
      </button>
    </aside>
  </div>
</template>

<style scoped>
/* Layout */
.chat-layout {
  display: flex;
  min-height: 100vh;
  }
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  min-width: 0;
  margin-right: var(--panel-width, 320px);
  transition: margin-right 0.25s;
}
.chat-layout.panel-collapsed .chat-main { margin-right: 36px; }
.chat-panel {
  width: 320px;
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
.chat-panel.collapsed {
  width: 36px !important;
  padding: 0;
  overflow: hidden;
}
/* sidebar collapsed margin adjustment */
.sidebar.collapsed ~ .chat-main { margin-left: 36px; }

/* Shared panel toggle + resize handle (used across AI pages) */
.panel-toggle-btn {
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
  right: var(--panel-width, 320px);
  transition: right 0.25s, color 0.15s;
}
.panel-toggle-btn:hover { color: rgb(52,208,188); border-color: rgba(52,208,188,0.4); background: #fff; }
.chat-panel.collapsed .panel-toggle-btn { right: 36px; }

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
.panel-resize-handle:hover { background: rgba(52,208,188,0.25); }

/* Hero */
.chat-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  max-width: 700px;
}
.chat-logo {
  margin-bottom: 20px;
  opacity: 0.6;
}
.chat-hero-title {
  font-size: 28px;
  font-weight: 600;
  color: rgba(0,0,0,0.85);
  margin: 0 0 6px;
}
.chat-hero-subtitle {
  font-size: 15px;
  color: rgba(0,0,0,0.4);
  margin: 0 0 32px;
}

/* Search input */
.chat-input-wrap {
  display: flex;
  align-items: center;
  width: 100%;
  background: #fff;
  border-radius: 999px;
  box-shadow: 0 2px 16px rgba(0,0,0,0.06);
  padding: 8px;
  transition: box-shadow 0.3s;
}
.chat-input-wrap:focus-within {
  box-shadow: 0 2px 20px rgba(52,208,188,0.15);
}
.chat-input {
  flex: 1;
  height: 40px;
  padding: 0 16px;
  border: none;
  border-radius: 999px;
  background: transparent;
  font-size: 15px;
  outline: none;
  min-width: 0;
}
.chat-input-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  position: relative;
}
.chat-send-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: rgb(52,208,188);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}
.chat-send-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.chat-send-btn:hover:not(:disabled) { transform: scale(1.05); }

/* Suggestions */
.chat-suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 28px;
}
.chat-suggestion-btn {
  padding: 10px 18px;
  border-radius: 999px;
  border: 1px solid rgba(0,0,0,0.1);
  background: transparent;
  color: rgba(0,0,0,0.55);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.25s;
  font-family: inherit;
}
.chat-suggestion-btn:hover {
  border-color: rgb(52,208,188);
  color: rgb(52,208,188);
}

/* Messages */
.chat-messages {
  flex: 1;
  width: 100%;
  max-width: 700px;
  overflow-y: auto;
  padding: 8px 0;
}
.chat-msg { margin-bottom: 16px; display: flex; }
.chat-msg--user { justify-content: flex-end; }
.chat-msg--ai { justify-content: flex-start; }
.chat-msg-bubble {
  max-width: 80%;
  padding: 12px 18px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.chat-msg--user .chat-msg-bubble {
  background: rgb(52,208,188);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.chat-msg--ai .chat-msg-bubble {
  background: rgba(0,0,0,0.04);
  color: rgba(0,0,0,0.85);
  border-bottom-left-radius: 4px;
}
.chat-loading { text-align: center; font-size: 13px; color: rgba(0,0,0,0.3); padding: 12px; }

/* Chat input bottom */
.chat-bottom-bar { width: 100%; max-width: 700px; }
.chat-bottom-rate { font-size: 11px; color: rgba(0,0,0,0.3); text-align: center; margin-top: 8px; }

/* Model pill — sleek dropdown like ChatGPT/Claude */
.model-pill {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 10px; border: 1px solid rgba(0,0,0,0.1); border-radius: 999px;
  background: rgba(255,255,255,0.8); cursor: pointer; font-size: 12px;
  color: rgba(0,0,0,0.55); transition: all 0.15s; user-select: none;
  flex-shrink: 0;
}
.model-pill:hover { border-color: rgba(52,208,188,0.4); color: rgb(52,208,188); }
.model-pill-name { font-weight: 500; }
.model-menu {
  position: absolute; right: 0; bottom: 100%; margin-bottom: 8px;
  background: #fff; border: 1px solid rgba(0,0,0,0.08); border-radius: 14px;
  box-shadow: 0 8px 30px rgba(0,0,0,0.1); padding: 6px; min-width: 220px; z-index: 100;
}
.model-menu-item {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 9px 12px; border: none; border-radius: 10px;
  background: transparent; cursor: pointer; font-family: inherit;
  font-size: 13px; color: rgba(0,0,0,0.6); transition: all 0.1s; text-align: left;
}
.model-menu-item:hover { background: rgba(52,208,188,0.06); }
.model-menu-item.active { background: rgba(52,208,188,0.1); color: rgb(52,208,188); }
.model-menu-item span { flex: 1; display: flex; flex-direction: column; }
.model-menu-item strong { font-size: 13px; font-weight: 600; color: rgba(0,0,0,0.8); }
.model-menu-item small { font-size: 10px; color: rgba(0,0,0,0.35); }
.model-badge-pro {
  font-size: 9px; font-weight: 600; padding: 1px 5px; border-radius: 4px;
  background: rgba(52,208,188,0.1); color: rgb(52,208,188);
}

/* Rate limit info */
.rate-limit-info { margin-top: 16px; text-align: center; }
.rate-limit-text { font-size: 11px; color: rgba(0,0,0,0.3); margin: 0; }
.rate-limit-text strong { color: rgb(52,208,188); }

.chat-input-bottom {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  max-width: 700px;
  padding: 8px;
  background: #fff;
  border-radius: 999px;
  border: 1px solid rgba(0,0,0,0.08);
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
}
.chat-input-bottom input {
  flex: 1;
  padding: 7px 16px;
  border: none;
  border-radius: 999px;
  font-size: 14px;
  outline: none;
  font-family: inherit;
  background: transparent;
  min-width: 0;
}
.chat-input-bottom button {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: rgb(52,208,188);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}
.chat-input-bottom button:disabled { opacity: 0.4; cursor: not-allowed; }

/* Right Panel */
.chat-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgb(52,208,188);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 20px;
}
.chat-panel-section { margin-bottom: 20px; }
.chat-panel-section h4 {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: rgba(0,0,0,0.3);
  margin: 0 0 10px;
}
.chat-panel-kb {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  background: rgba(255,255,255,0.6);
  border-radius: 14px;
  font-size: 14px;
  color: rgba(0,0,0,0.65);
  text-decoration: none;
  cursor: pointer;
  transition: background 0.15s;
}
.chat-panel-kb:hover { background: rgba(255,255,255,0.85); }
.chat-panel-hint { font-size: 13px; color: rgba(0,0,0,0.35); margin: 0; }
.panel-link {
  color: rgb(52,208,188);
  text-decoration: none;
  font-weight: 500;
}
.panel-link:hover { text-decoration: underline; }
.chat-panel-card {
  padding: 14px;
  border-radius: 16px;
  background: rgba(255,255,255,0.6);
  border: 1px solid rgba(255,255,255,0.8);
  margin-bottom: 8px;
}
.chat-panel-card--gradient {
  background: linear-gradient(135deg, rgb(52,208,188), rgb(0,107,95));
  color: #fff;
  border: none;
}
.chat-panel-card--gradient .chat-panel-card-label { color: rgba(255,255,255,0.7); }
.chat-panel-card--gradient .chat-panel-card-text { color: #fff; }
.chat-panel-card--clickable {
  display: block;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.15s;
  color: inherit;
}
.chat-panel-card--clickable:hover { background: rgba(255,255,255,0.85); }
.chat-panel-card-label { font-size: 11px; opacity: 0.7; margin: 0 0 3px; }
.chat-panel-card-text { font-size: 13px; margin: 0; line-height: 1.4; }

.chat-panel-input {
  margin-top: auto;
  display: flex;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid rgba(0,0,0,0.04);
}
.chat-panel-input input {
  flex: 1;
  padding: 10px 14px;
  border-radius: 14px;
  border: none;
  background: rgba(255,255,255,0.6);
  font-size: 13px;
  outline: none;
  font-family: inherit;
}
.chat-panel-input button {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: none;
  background: rgb(52,208,188);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}
.chat-panel-input button:disabled { opacity: 0.4; cursor: not-allowed; }

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .chat-hero-title {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .chat-hero-subtitle {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .chat-input-wrap {
  background: var(--cf-bg-card, #0c0c0d);
  box-shadow: 0 2px 16px rgba(0,0,0,0.3);
}
html[data-theme='dark'] .chat-suggestion-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .chat-msg--ai .chat-msg-bubble {
  background: rgba(255,255,255,0.08);
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .chat-loading,
html[data-theme='dark'] .chat-bottom-rate,
html[data-theme='dark'] .rate-limit-text {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .model-pill {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  background: color-mix(in srgb, var(--cf-bg-card, #0c0c0d) 94%, transparent);
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .model-menu {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  box-shadow: 0 8px 30px rgba(0,0,0,0.4);
}
html[data-theme='dark'] .model-menu-item {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .model-menu-item strong {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .model-menu-item small {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .chat-input-bottom {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  box-shadow: 0 2px 12px rgba(0,0,0,0.2);
}
html[data-theme='dark'] .chat-input-bottom input,
html[data-theme='dark'] .chat-input {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .chat-input-bottom input::placeholder,
html[data-theme='dark'] .chat-input::placeholder {
  color: rgba(248,250,252,0.35);
}
html[data-theme='dark'] .chat-panel-section h4 {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .chat-panel-kb {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .chat-panel-kb:hover {
  background: var(--cf-bg-muted, rgba(255,255,255,0.09));
}
html[data-theme='dark'] .chat-panel-hint {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .chat-panel-card:not(.chat-panel-card--gradient) {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  border-color: var(--cf-border, rgba(255,255,255,0.08));
}
html[data-theme='dark'] .chat-panel-card--clickable:not(.chat-panel-card--gradient):hover {
  background: var(--cf-bg-muted, rgba(255,255,255,0.09));
}
html[data-theme='dark'] .chat-panel-input {
  border-top-color: rgba(255,255,255,0.04);
}
html[data-theme='dark'] .chat-panel-input input {
  background: var(--cf-bg-soft, rgba(255,255,255,0.06));
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .chat-panel-input input::placeholder {
  color: rgba(248,250,252,0.35);
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
