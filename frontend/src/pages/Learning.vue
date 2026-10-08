<script setup lang="ts">
import { onMounted, ref, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { tutorials as staticTutorials, categoryLabels, iconColors } from '@/data/tutorials';
import { listPublicNotes, deleteNote } from '@/api/ai-workspace';
import { listTutorials, reorderTutorials, reorderPublicNotes } from '@/api/learning';
import { useAuthStore } from '@/stores/auth';
import { NOTE_TAGS } from '@/data/note-tags';
import type { NoteVO, PageResult } from '@/types/ai-workspace';
import type { TutorialVO } from '@/types/learning';

const NOTE_PAGE_SIZE = 12;

const router = useRouter();
const toast = useMessage();
const authStore = useAuthStore();
const notes = ref<NoteVO[]>([]);
const notesLoading = ref(false);
const notesLoadingMore = ref(false);
const activeCategory = ref('');
const activeTab = ref<'tutorials' | 'notes'>('tutorials');
const notePage = ref(1);
const noteTotal = ref(0);
const noteSubTab = ref<'all' | 'mine'>('all');
const activeNoteTag = ref('全部');
const syncedTutorials = ref<TutorialVO[]>([]);

// 管理员判断：TENANT_ADMIN / SUPER_ADMIN 可看到"编辑排序"按钮
const isAdmin = computed(
  () => authStore.user?.role === 'TENANT_ADMIN' || authStore.user?.role === 'SUPER_ADMIN',
);

// 编辑排序状态：false=非编辑；'tutorials'/'notes'=当前 tab 在编辑
type EditingSort = false | 'tutorials' | 'notes';
const editingSort = ref<EditingSort>(false);
const tutorialSortDraft = ref<TutorialVO[]>([]);
const noteSortDraft = ref<NoteVO[]>([]);
const savingSort = ref(false);

const allTutorials = computed(() => {
  if (syncedTutorials.value.length > 0) return syncedTutorials.value;
  // fallback to static data until synced
  return staticTutorials.map((t) => ({
    id: t.id, title: t.title, slug: t.id, description: t.description,
    source: t.source, sourceUrl: t.url, category: t.category, icon: t.icon,
    lessonCount: 0,
  }));
});

const categories = computed(() => {
  const cats = new Set(allTutorials.value.map((t) => t.category));
  return Array.from(cats);
});

const filteredTutorials = computed(() => {
  if (!activeCategory.value) return allTutorials.value;
  return allTutorials.value.filter((t) => t.category === activeCategory.value);
});

// 编辑模式下渲染 draft；否则渲染 filteredTutorials
const displayTutorials = computed(() =>
  editingSort.value === 'tutorials' ? tutorialSortDraft.value : filteredTutorials.value,
);
const displayNotes = computed(() =>
  editingSort.value === 'notes' ? noteSortDraft.value : notes.value,
);

const tutorialCount = computed(() => allTutorials.value.length);

// 教程排序只对后端同步教程有意义（静态 fallback 没有对应记录）
const canEditTutorials = computed(() => syncedTutorials.value.length > 0);

const hasMoreNotes = computed(() => notes.value.length < noteTotal.value);

async function loadTutorials() {
  try {
    syncedTutorials.value = await listTutorials();
  } catch { /* use static fallback */ }
}

function openTutorial(tut: { id: string; lessonCount: number }) {
  if (editingSort.value === 'tutorials') return; // 编辑模式屏蔽点击
  if (tut.lessonCount > 0) {
    router.push(`/learning/tutorial/${tut.id}`);
  } else {
    // Static fallback: open external
    const st = staticTutorials.find((s) => s.id === tut.id);
    if (st) window.open(st.url, '_blank', 'noopener');
  }
}

async function loadNotes(reset = true) {
  if (reset) {
    notesLoading.value = true;
    notePage.value = 1;
  } else {
    notesLoadingMore.value = true;
    notePage.value += 1;
  }
  try {
    const params: any = { page: notePage.value, pageSize: NOTE_PAGE_SIZE };
    if (activeNoteTag.value !== '全部') params.tag = activeNoteTag.value;
    if (noteSubTab.value === 'mine') params.mine = true;
    const res: PageResult<NoteVO> = await listPublicNotes(params);
    if (reset) {
      notes.value = res.items;
    } else {
      notes.value.push(...res.items);
    }
    noteTotal.value = res.total;
  } catch {
    if (reset) notes.value = [];
  }
  finally {
    notesLoading.value = false;
    notesLoadingMore.value = false;
  }
}

async function handleDeleteNote(id: string) {
  try {
    await deleteNote(id);
    notes.value = notes.value.filter((n) => n.id !== id);
    noteTotal.value = Math.max(0, noteTotal.value - 1);
    toast.success('已删除');
  } catch (e: any) {
    toast.error(e?.message || '删除失败');
  }
}

function selectNoteTag(tag: string) {
  if (editingSort.value === 'notes') return; // 编辑模式禁用过滤器
  activeNoteTag.value = tag;
}

// 切换子 tab 或标签时重新加载（重置到第 1 页）
watch([noteSubTab, activeNoteTag], () => loadNotes(true));

function openNote(id: string) {
  if (editingSort.value === 'notes') return; // 编辑模式屏蔽点击
  router.push(`/learning/notes/${id}`);
}

// ========== 编辑排序 ==========

function enterEditSort() {
  if (!isAdmin.value) return;
  if (activeTab.value === 'tutorials') {
    if (!canEditTutorials.value) {
      toast.warning('教程尚未从后端同步，无法编辑排序');
      return;
    }
    // 清空 category 过滤，进入编辑基于全量
    activeCategory.value = '';
    tutorialSortDraft.value = [...allTutorials.value];
    editingSort.value = 'tutorials';
  } else {
    if (notes.value.length === 0) {
      toast.warning('当前没有可排序的笔记');
      return;
    }
    // 基于当前已加载的笔记（用户想排更多可先"加载更多"再进入编辑）
    noteSortDraft.value = [...notes.value];
    editingSort.value = 'notes';
  }
}

function cancelEditSort() {
  editingSort.value = false;
  tutorialSortDraft.value = [];
  noteSortDraft.value = [];
}

async function saveEditSort() {
  if (savingSort.value) return;
  savingSort.value = true;
  try {
    if (editingSort.value === 'tutorials') {
      const ids = tutorialSortDraft.value.map((t) => t.id);
      await reorderTutorials(ids);
      await loadTutorials();
      toast.success('教程顺序已更新');
    } else if (editingSort.value === 'notes') {
      const ids = noteSortDraft.value.map((n) => n.id);
      await reorderPublicNotes(ids);
      await loadNotes(true);
      toast.success('笔记顺序已更新');
    }
    editingSort.value = false;
    tutorialSortDraft.value = [];
    noteSortDraft.value = [];
  } catch (e: any) {
    toast.error(e?.message || '保存失败');
  } finally {
    savingSort.value = false;
  }
}

function currentDraft(): any[] {
  return editingSort.value === 'tutorials'
    ? (tutorialSortDraft.value as any[])
    : (noteSortDraft.value as any[]);
}
function swap(arr: any[], i: number, j: number) {
  if (i < 0 || j < 0 || i >= arr.length || j >= arr.length) return;
  [arr[i], arr[j]] = [arr[j], arr[i]];
}
function moveUp(index: number) { swap(currentDraft(), index, index - 1); }
function moveDown(index: number) { swap(currentDraft(), index, index + 1); }
function moveTop(index: number) {
  const arr = currentDraft();
  if (index <= 0 || index >= arr.length) return;
  const [item] = arr.splice(index, 1);
  arr.unshift(item);
}
function moveBottom(index: number) {
  const arr = currentDraft();
  if (index < 0 || index >= arr.length - 1) return;
  const [item] = arr.splice(index, 1);
  arr.push(item);
}

// 切换 Tab 时若在编辑中，自动退出
watch(activeTab, () => {
  if (editingSort.value) cancelEditSort();
});

function iconSvg(key: string): string {
  const map: Record<string, string> = {
    html: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 18.178l-6.36 3.342 1.214-7.07L1.66 9.208l7.104-1.032L12 1.85l3.236 6.326 7.104 1.032-5.194 5.242 1.214 7.07z"/></svg>',
    css: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M12 18.178l-6.36 3.342 1.214-7.07L1.66 9.208l7.104-1.032L12 1.85l3.236 6.326 7.104 1.032-5.194 5.242 1.214 7.07z"/></svg>',
    js: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><rect x="2" y="2" width="20" height="20" rx="2" fill="#f7df1e"/><text x="12" y="17" text-anchor="middle" font-size="12" font-weight="bold" fill="#000">JS</text></svg>',
    python: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><text x="12" y="16" text-anchor="middle" font-size="13" font-weight="bold">Py</text></svg>',
    java: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><text x="12" y="16" text-anchor="middle" font-size="11" font-weight="bold">Jv</text></svg>',
    db: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>',
    linux: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" y1="9" x2="9.01" y2="9"/><line x1="15" y1="9" x2="15.01" y2="9"/></svg>',
    git: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z"/></svg>',
    docker: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="1" y="3" width="22" height="18" rx="2"/><circle cx="12" cy="12" r="5"/></svg>',
    vue: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 22 8.5 22 15.5 12 22 2 15.5 2 8.5"/></svg>',
    react: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="2"/><ellipse cx="12" cy="12" rx="10" ry="4"/><ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(60 12 12)"/><ellipse cx="12" cy="12" rx="10" ry="4" transform="rotate(120 12 12)"/></svg>',
    algo: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l2.4 7.2h7.6l-6 4.8 2.4 7.2-6-4.8-6 4.8 2.4-7.2-6-4.8h7.6z"/></svg>',
  };
  return map[key] || map.html;
}

function truncate(text: string, max: number): string {
  if (!text || text.length <= max) return text || '';
  return text.slice(0, max) + '...';
}

onMounted(() => { loadNotes(); loadTutorials(); });
</script>

<template>
  <div class="learning-page">
    <!-- Main Content -->
    <main class="learning-main">
      <!-- Tab Bar -->
      <div class="learning-tabs">
        <button
          class="learning-tab"
          :class="{ active: activeTab === 'tutorials' }"
          :disabled="!!editingSort"
          @click="activeTab = 'tutorials'"
        >
          精选教程
          <span class="tab-badge">{{ tutorialCount }}</span>
        </button>
        <button
          class="learning-tab"
          :class="{ active: activeTab === 'notes' }"
          :disabled="!!editingSort"
          @click="activeTab = 'notes'"
        >
          学习笔记
          <span class="tab-badge">{{ noteTotal }}</span>
        </button>
        <!-- 管理员：编辑排序入口 / 保存 / 取消 -->
        <div v-if="isAdmin" class="learning-tabs-admin">
          <template v-if="!editingSort">
            <button
              class="sort-edit-btn"
              :disabled="activeTab === 'tutorials' && !canEditTutorials"
              :title="activeTab === 'tutorials' && !canEditTutorials ? '教程尚未从后端同步' : ''"
              @click="enterEditSort()"
            >
              编辑排序
            </button>
          </template>
          <template v-else>
            <span class="sort-editing-tip">编辑排序中…</span>
            <button class="sort-cancel-btn" :disabled="savingSort" @click="cancelEditSort()">取消</button>
            <button class="sort-save-btn" :disabled="savingSort" @click="saveEditSort()">
              {{ savingSort ? '保存中…' : '保存' }}
            </button>
          </template>
        </div>
      </div>

      <!-- Tutorials Tab Panel -->
      <div v-if="activeTab === 'tutorials'" class="tab-panel">
        <!-- Category filters -->
        <div class="category-filters">
          <button
            :class="{ active: !activeCategory }"
            :disabled="editingSort === 'tutorials'"
            @click="activeCategory = ''"
          >全部</button>
          <button
            v-for="cat in categories"
            :key="cat"
            :class="{ active: activeCategory === cat }"
            :disabled="editingSort === 'tutorials'"
            @click="activeCategory = cat"
          >
            {{ categoryLabels[cat] || cat }}
          </button>
        </div>
        <!-- Tutorial cards -->
        <div class="tutorial-grid">
          <div
            v-for="(t, idx) in displayTutorials"
            :key="t.id"
            class="tutorial-card"
            :class="{ 'sort-editing': editingSort === 'tutorials' }"
            @click="openTutorial(t)"
          >
            <div class="tutorial-icon" :style="{ color: iconColors[t.icon] || '#52d0bc', background: (iconColors[t.icon] || '#52d0bc') + '12' }">
              <span v-html="iconSvg(t.icon)" />
            </div>
            <div class="tutorial-info">
              <h3>{{ t.title }} <small v-if="t.lessonCount > 0" class="synced-badge">已同步 {{ t.lessonCount }} 章</small></h3>
              <p>{{ t.description }}</p>
              <div class="tutorial-meta">
                <span class="tutorial-source">{{ t.source }}</span>
                <span class="tutorial-cat">{{ categoryLabels[t.category] || t.category }}</span>
              </div>
            </div>
            <!-- 编辑排序按钮组 -->
            <div v-if="editingSort === 'tutorials'" class="sort-actions" @click.stop>
              <button title="置顶" :disabled="idx === 0" @click.stop="moveTop(idx)">⏫</button>
              <button title="上移" :disabled="idx === 0" @click.stop="moveUp(idx)">⬆️</button>
              <button title="下移" :disabled="idx === displayTutorials.length - 1" @click.stop="moveDown(idx)">⬇️</button>
              <button title="置底" :disabled="idx === displayTutorials.length - 1" @click.stop="moveBottom(idx)">⏬</button>
            </div>
          </div>
        </div>
      </div>

      <!-- Notes Tab Panel -->
      <div v-if="activeTab === 'notes'" class="tab-panel">
        <!-- Sub tabs: 全部 / 我的 -->
        <div class="note-subtabs">
          <button
            :class="{ active: noteSubTab === 'all' }"
            :disabled="editingSort === 'notes'"
            @click="noteSubTab = 'all'"
          >全部笔记</button>
          <button
            :class="{ active: noteSubTab === 'mine' }"
            :disabled="editingSort === 'notes'"
            @click="noteSubTab = 'mine'"
          >我的笔记</button>
        </div>
        <!-- Tag filter chips -->
        <div class="note-tag-filters">
          <button
            v-for="tag in ['全部', ...NOTE_TAGS]"
            :key="tag"
            :class="{ active: activeNoteTag === tag }"
            :disabled="editingSort === 'notes'"
            @click="selectNoteTag(tag)"
          >{{ tag }}</button>
        </div>
        <div v-if="notesLoading" class="notes-loading">加载中...</div>
        <div v-else-if="notes.length === 0" class="notes-empty">
          <p>{{ noteSubTab === 'mine' ? '你还没有发布笔记' : '还没有公开发布的笔记' }}</p>
          <button class="notes-goto-btn" @click="router.push('/ai/notes')">去写一篇</button>
        </div>
        <div v-else class="notes-list">
          <article
            v-for="(note, idx) in displayNotes"
            :key="note.id"
            class="note-card"
            :class="{ 'sort-editing': editingSort === 'notes' }"
            @click="openNote(note.id)"
          >
            <div class="note-card-header">
              <h3>
                {{ note.title || '未命名笔记' }}
                <span
                  v-if="note.sourceUrl"
                  class="note-card-ext"
                  title="外部同步笔记，标注了原始出处"
                >↗</span>
              </h3>
              <button
                v-if="editingSort !== 'notes' && note.ownerId === authStore.user?.id"
                class="note-card-del"
                title="删除"
                @click.stop="handleDeleteNote(note.id)"
              >
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6m3 0V4a2 2 0 012-2h4a2 2 0 012 2v2"/></svg>
              </button>
            </div>
            <p class="note-excerpt">{{ truncate(note.content?.replace(/[#*`>\-\[\]!\(\)]/g, '').trim() || '', 120) }}</p>
            <!-- Tag chips -->
            <div v-if="note.tags?.length" class="note-card-tags">
              <span
                v-for="tag in note.tags"
                :key="tag"
                class="note-card-tag"
                @click.stop="selectNoteTag(tag)"
              >{{ tag }}</span>
            </div>
            <div class="note-meta">
              <!-- Author -->
              <span class="note-author" @click.stop="editingSort === 'notes' ? null : router.push('/users/' + note.ownerId)">
                <span class="note-author-avatar">
                  <img v-if="note.ownerAvatar" :src="note.ownerAvatar" width="18" height="18" />
                  <span v-else class="note-author-avatar-fallback">{{ note.ownerName?.charAt(0) || '?' }}</span>
                </span>
                {{ note.ownerName || '未知用户' }}
              </span>
              <span>{{ note.updatedAt?.slice(0, 10) }}</span>
              <span>{{ note.viewCount || 0 }} 次阅读</span>
              <span
                v-if="note.sourceName"
                class="note-source-meta"
                :title="'原文出处：' + note.sourceUrl"
              >原文 · {{ note.sourceName }}</span>
            </div>
            <!-- 编辑排序按钮组 -->
            <div v-if="editingSort === 'notes'" class="sort-actions" @click.stop>
              <button title="置顶" :disabled="idx === 0" @click.stop="moveTop(idx)">⏫</button>
              <button title="上移" :disabled="idx === 0" @click.stop="moveUp(idx)">⬆️</button>
              <button title="下移" :disabled="idx === displayNotes.length - 1" @click.stop="moveDown(idx)">⬇️</button>
              <button title="置底" :disabled="idx === displayNotes.length - 1" @click.stop="moveBottom(idx)">⏬</button>
            </div>
          </article>
        </div>
        <!-- 加载更多 / 底部提示（编辑模式不显示） -->
        <div v-if="!notesLoading && notes.length > 0 && editingSort !== 'notes'" class="notes-footer">
          <button
            v-if="hasMoreNotes"
            class="load-more-btn"
            :disabled="notesLoadingMore"
            @click="loadNotes(false)"
          >
            {{ notesLoadingMore ? '加载中…' : '加载更多' }}
          </button>
          <p v-else class="no-more-tip">— 没有更多了 —</p>
        </div>
      </div>
    </main>

    <!-- Right Sidebar -->
    <aside class="learning-sidebar">
      <div class="sidebar-card">
        <h3>学习统计</h3>
        <div class="stat-item">
          <span class="stat-val">{{ tutorialCount }}</span>
          <span class="stat-label">收录教程</span>
        </div>
        <div class="stat-item">
          <span class="stat-val">{{ noteTotal }}</span>
          <span class="stat-label">学习笔记</span>
        </div>
      </div>
      <div class="sidebar-card">
        <h3>快捷入口</h3>
        <button class="sidebar-link-btn" @click="router.push('/ai/notes')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          写笔记
        </button>
        <button class="sidebar-link-btn" @click="router.push('/ai/libraries')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 19a2 2 0 01-2 2H4a2 2 0 01-2-2V5a2 2 0 012-2h5l2 3h9a2 2 0 012 2z"/></svg>
          知识库
        </button>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.learning-page {
  display: flex;
  gap: 28px;
  max-width: 1200px;
  margin: 0 auto;
  padding: 32px 24px;
  min-height: calc(100vh - 64px);
}
.learning-main { flex: 1; min-width: 0; }
.learning-sidebar {
  width: 240px;
  flex-shrink: 0;
  position: sticky;
  top: 20px;
  align-self: start;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 68px;
}

/* Tab Switcher */
.learning-tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 24px;
  align-items: center;
}
.learning-tabs-admin {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.sort-edit-btn,
.sort-cancel-btn,
.sort-save-btn {
  padding: 6px 14px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  border: 1px solid rgba(0,0,0,0.08);
  transition: all 0.15s;
}
.sort-edit-btn { background: transparent; color: rgba(0,0,0,0.6); }
.sort-edit-btn:hover:not(:disabled) {
  background: rgba(52,208,188,0.08);
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.3);
}
.sort-edit-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.sort-cancel-btn { background: transparent; color: rgba(0,0,0,0.5); }
.sort-cancel-btn:hover:not(:disabled) { background: rgba(0,0,0,0.04); }
.sort-save-btn {
  background: rgb(52,208,188);
  color: #fff;
  border-color: rgb(52,208,188);
}
.sort-save-btn:hover:not(:disabled) { background: rgb(42,190,170); }
.sort-save-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.sort-editing-tip {
  font-size: 12px;
  color: rgb(52,208,188);
  font-weight: 500;
  padding-right: 4px;
}
.learning-tab:disabled { opacity: 0.5; cursor: not-allowed; }
.category-filters button:disabled,
.note-subtabs button:disabled,
.note-tag-filters button:disabled { opacity: 0.5; cursor: not-allowed; }
.learning-tab {
  padding: 10px 24px;
  border: none;
  border-radius: 12px;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
  color: rgba(0,0,0,0.45);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.learning-tab:hover {
  color: rgb(52,208,188);
  background: rgba(52,208,188,0.06);
}
.learning-tab.active {
  color: #fff;
  background: rgb(52,208,188);
}
.learning-tab .tab-badge {
  font-size: 12px;
  font-weight: 500;
  background: rgba(0,0,0,0.08);
  padding: 1px 8px;
  border-radius: 999px;
  line-height: 1.5;
  min-width: 18px;
  text-align: center;
}
.learning-tab.active .tab-badge {
  background: rgba(255,255,255,0.2);
  color: #fff;
}
.tab-panel {
  /* inherits existing spacing from children */
}

/* Category filters */
.category-filters { display: flex; gap: 6px; margin-bottom: 18px; flex-wrap: wrap; }
.category-filters button {
  padding: 5px 14px;
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 20px;
  background: transparent;
  font-size: 12px;
  color: rgba(0,0,0,0.5);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.category-filters button.active, .category-filters button:hover {
  background: rgba(52,208,188,0.08);
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.3);
}

/* Tutorial grid */
.tutorial-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.tutorial-card {
  display: flex;
  gap: 16px;
  padding: 18px;
  border: 1px solid rgba(0,0,0,0.05);
  border-radius: 16px;
  background: #fff;
  cursor: pointer;
  color: inherit;
  transition: all 0.2s;
  position: relative;
}
.tutorial-card.sort-editing,
.note-card.sort-editing {
  border: 1px dashed rgba(52,208,188,0.5);
  background: rgba(52,208,188,0.03);
  cursor: default;
}
.tutorial-card.sort-editing:hover,
.note-card.sort-editing:hover { transform: none; box-shadow: none; }

/* Sort action buttons (卡片右上角) */
.sort-actions {
  position: absolute;
  top: 8px;
  right: 8px;
  display: inline-flex;
  gap: 4px;
  background: rgba(255,255,255,0.95);
  padding: 4px 6px;
  border-radius: 10px;
  border: 1px solid rgba(0,0,0,0.06);
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  z-index: 2;
}
.sort-actions button {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 3px 5px;
  border-radius: 6px;
  transition: background 0.12s;
}
.sort-actions button:hover:not(:disabled) { background: rgba(52,208,188,0.12); }
.sort-actions button:disabled { opacity: 0.3; cursor: not-allowed; }

/* Notes footer: 加载更多 / 没有更多 */
.notes-footer {
  display: flex;
  justify-content: center;
  padding: 20px 0 8px;
}
.load-more-btn {
  padding: 8px 24px;
  border: 1px solid rgba(52,208,188,0.3);
  border-radius: 20px;
  background: transparent;
  color: rgb(52,208,188);
  font-size: 13px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.load-more-btn:hover:not(:disabled) { background: rgba(52,208,188,0.08); }
.load-more-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.no-more-tip { color: rgba(0,0,0,0.3); font-size: 12px; margin: 0; }
.synced-badge {
  font-weight: 500; font-size: 10px; color: #52d0bc;
  background: rgba(52,208,188,0.08); padding: 2px 6px; border-radius: 4px;
}
.tutorial-card:hover {
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
  border-color: rgba(52,208,188,0.2);
  transform: translateY(-1px);
}
.tutorial-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.tutorial-info { flex: 1; min-width: 0; }
.tutorial-info h3 { font-size: 15px; font-weight: 600; margin: 0 0 4px; color: rgba(0,0,0,0.85); }
.tutorial-info p { font-size: 13px; color: rgba(0,0,0,0.45); margin: 0 0 10px; line-height: 1.5; }
.tutorial-meta { display: flex; gap: 8px; align-items: center; }
.tutorial-source { font-size: 11px; font-weight: 500; color: rgb(52,208,188); background: rgba(52,208,188,0.08); padding: 2px 8px; border-radius: 4px; }
.tutorial-cat { font-size: 11px; color: rgba(0,0,0,0.3); }

/* Notes */
.note-subtabs {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}
.note-subtabs button {
  padding: 6px 18px;
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 20px;
  background: transparent;
  font-size: 12px;
  color: rgba(0,0,0,0.5);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.note-subtabs button.active, .note-subtabs button:hover {
  background: rgba(52,208,188,0.08);
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.3);
}
.note-tag-filters {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.note-tag-filters button {
  padding: 3px 10px;
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 14px;
  background: transparent;
  font-size: 11px;
  color: rgba(0,0,0,0.4);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.note-tag-filters button.active, .note-tag-filters button:hover {
  background: rgba(52,208,188,0.08);
  color: rgb(52,208,188);
  border-color: rgba(52,208,188,0.25);
}
.notes-list { display: flex; flex-direction: column; gap: 10px; }
.notes-loading, .notes-empty { text-align: center; padding: 40px; color: rgba(0,0,0,0.35); font-size: 14px; }
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
.note-card {
  padding: 16px 18px;
  border: 1px solid rgba(0,0,0,0.05);
  border-radius: 14px;
  background: #fff;
  cursor: pointer;
  transition: all 0.15s;
  position: relative;
}
.note-card:hover { border-color: rgba(52,208,188,0.2); background: rgba(52,208,188,0.02); }
.note-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.note-card-header h3 { font-size: 14px; font-weight: 600; margin: 0; color: rgba(0,0,0,0.8); display: inline-flex; align-items: center; gap: 6px; }
.note-card-ext {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: rgba(52,208,188,0.14);
  color: rgb(52,208,188);
  font-size: 11px;
  font-weight: 500;
  line-height: 1;
}
.note-source-meta {
  color: rgb(52,208,188);
  font-weight: 500;
  padding: 1px 8px;
  border-radius: 8px;
  background: rgba(52,208,188,0.07);
}
.note-card-del {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(0,0,0,0.2);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}
.note-card-del:hover { background: rgba(255,77,79,0.08); color: rgb(255,77,79); }
.note-excerpt { font-size: 12px; color: rgba(0,0,0,0.4); margin: 0 0 8px; line-height: 1.5; }
.note-card-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 8px; }
.note-card-tag {
  font-size: 10px;
  padding: 1px 8px;
  border-radius: 10px;
  background: rgba(52,208,188,0.06);
  color: rgb(52,208,188);
  cursor: pointer;
  transition: all 0.15s;
}
.note-card-tag:hover { background: rgba(52,208,188,0.15); }
.note-meta { display: flex; gap: 12px; font-size: 11px; color: rgba(0,0,0,0.3); align-items: center; }
.note-author {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  transition: color 0.15s;
}
.note-author:hover { color: rgb(52,208,188); }
.note-author-avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.note-author-avatar img { width: 100%; height: 100%; object-fit: cover; }
.note-author-avatar-fallback {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(52,208,188,0.12);
  color: rgb(52,208,188);
  font-size: 10px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Sidebar */
.sidebar-card {
  background: #fff;
  border: 1px solid rgba(0,0,0,0.05);
  border-radius: 16px;
  padding: 18px;
}
.sidebar-card h3 { font-size: 14px; font-weight: 600; margin: 0 0 14px; color: rgba(0,0,0,0.7); }
.stat-item { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.stat-val { font-size: 20px; font-weight: 700; color: rgb(52,208,188); min-width: 40px; }
.stat-label { font-size: 12px; color: rgba(0,0,0,0.4); }
.sidebar-link-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: 10px;
  background: transparent;
  color: rgba(0,0,0,0.55);
  font-size: 13px;
  cursor: pointer;
  margin-bottom: 6px;
  font-family: inherit;
  transition: all 0.15s;
}
.sidebar-link-btn:hover { background: rgba(52,208,188,0.05); color: rgb(52,208,188); border-color: rgba(52,208,188,0.2); }

@media (max-width: 900px) {
  .learning-page { flex-direction: column; }
  .learning-sidebar { width: 100%; position: static; padding-top: 0; }
  .tutorial-grid { grid-template-columns: 1fr; }
  .learning-tabs {
    overflow-x: auto;
    scrollbar-width: none;
  }
  .learning-tabs::-webkit-scrollbar { display: none; }
  .learning-tab { white-space: nowrap; }
}

/* ===== Dark Mode Overrides ===== */
html[data-theme='dark'] .learning-tab {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .learning-tab .tab-badge {
  background: rgba(255,255,255,0.12);
}
html[data-theme='dark'] .category-filters button,
html[data-theme='dark'] .note-subtabs button,
html[data-theme='dark'] .note-tag-filters button {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .tutorial-card,
html[data-theme='dark'] .note-card,
html[data-theme='dark'] .sidebar-card {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-card-border, rgba(255,255,255,0.07));
}
html[data-theme='dark'] .tutorial-card:hover {
  box-shadow: 0 8px 30px rgba(0,0,0,0.3);
}
html[data-theme='dark'] .tutorial-info h3,
html[data-theme='dark'] .note-card-header h3 {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .tutorial-info p,
html[data-theme='dark'] .note-excerpt,
html[data-theme='dark'] .stat-label {
  color: var(--cf-text-muted, rgba(248,250,252,0.52));
}
html[data-theme='dark'] .tutorial-cat,
html[data-theme='dark'] .note-meta,
html[data-theme='dark'] .notes-loading,
html[data-theme='dark'] .notes-empty {
  color: rgba(248,250,252,0.45);
}
html[data-theme='dark'] .sidebar-card h3 {
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .sidebar-link-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .note-card-del {
  color: rgba(248,250,252,0.18);
}
/* Sort actions dark mode */
html[data-theme='dark'] .sort-actions {
  background: rgba(12,12,13,0.92);
  border-color: rgba(255,255,255,0.08);
  box-shadow: 0 2px 8px rgba(0,0,0,0.4);
}
html[data-theme='dark'] .tutorial-card.sort-editing,
html[data-theme='dark'] .note-card.sort-editing {
  border-color: rgba(52,208,188,0.4);
  background: rgba(52,208,188,0.05);
}
html[data-theme='dark'] .sort-edit-btn,
html[data-theme='dark'] .sort-cancel-btn {
  border-color: var(--cf-border, rgba(255,255,255,0.08));
  color: var(--cf-text-secondary, rgba(248,250,252,0.76));
}
html[data-theme='dark'] .load-more-btn {
  background: transparent;
}
html[data-theme='dark'] .no-more-tip { color: rgba(248,250,252,0.35); }
</style>
