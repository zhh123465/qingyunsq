<script setup lang="ts">
import { ref, onMounted, h, watch } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NSpace, NTag, NPopconfirm, NTabs, NTabPane, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminPosts,
  togglePin,
  toggleEssence,
  setPostStatus,
  deleteAdminPost,
  restorePost,
  purgePost,
  batchSetPostStatus,
  batchDeletePost,
  batchRestorePost,
  batchPurgePost,
} from '@/api/admin';
import type { PostVO } from '@/types/post';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const posts = ref<PostVO[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const statusFilter = ref<number | null>(null);
const scopeFilter = ref<string | null>(null);

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<number[]>([]);

type TagType = 'default' | 'success' | 'error' | 'warning' | 'info';

const statusOptions: SelectOption[] = [
  { label: '正常', value: 1 },
  { label: '隐藏', value: 2 },
];

const scopeOptions: SelectOption[] = [
  { label: '广场', value: 'SQUARE' },
  { label: '空间', value: 'SPACE' },
];

async function loadPosts() {
  if (loading.value) return;
  loading.value = true;
  try {
    const r = await getAdminPosts({
      keyword: keyword.value || undefined,
      status: activeTab.value === 'active' && statusFilter.value != null ? statusFilter.value : undefined,
      scope: scopeFilter.value || undefined,
      trash: activeTab.value === 'trash',
      page: pageNum.value,
      size: pageSize.value,
    });
    posts.value = r.items;
    total.value = r.total;
  } catch {
    // 忽略加载失败
  }
  loading.value = false;
}

async function handleTogglePin(row: PostVO) {
  try {
    await togglePin(row.id);
    row.isPinned = row.isPinned ? 0 : 1;
    message.success(row.isPinned ? '已置顶' : '已取消置顶');
  } catch {
    message.error('操作失败');
  }
}

async function handleToggleEssence(row: PostVO) {
  try {
    await toggleEssence(row.id);
    row.isEssence = row.isEssence ? 0 : 1;
    message.success(row.isEssence ? '已加精' : '已取消精华');
  } catch {
    message.error('操作失败');
  }
}

async function handleSetStatus(row: PostVO, status: number) {
  try {
    await setPostStatus(row.id, status);
    row.status = status;
    message.success('状态已更新');
  } catch {
    message.error('操作失败');
  }
}

async function handleDelete(row: PostVO) {
  try {
    await deleteAdminPost(row.id);
    posts.value = posts.value.filter((p) => p.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: PostVO) {
  try {
    await restorePost(row.id);
    posts.value = posts.value.filter((p) => p.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

// 单条彻底删除
const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<PostVO | null>(null);
const purgeLoading = ref(false);
function openPurge(row: PostVO) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    const counts = await purgePost(purgeTargetRow.value.id);
    posts.value = posts.value.filter((p) => p.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success(
      `已彻底删除，级联清理 评论 ${counts.comments || 0} 条、点赞 ${counts.reactions || 0} 条`,
    );
    purgeModalShow.value = false;
  } catch {
    message.error('操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择帖子'); return false; }
  return true;
}
async function batchHide() {
  if (!checkSelected()) return;
  try {
    await batchSetPostStatus(checkedRowKeys.value, 2);
    posts.value.forEach(p => { if (checkedRowKeys.value.includes(p.id)) p.status = 2; });
    message.success(`已批量隐藏 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchShow() {
  if (!checkSelected()) return;
  try {
    await batchSetPostStatus(checkedRowKeys.value, 1);
    posts.value.forEach(p => { if (checkedRowKeys.value.includes(p.id)) p.status = 1; });
    message.success(`已批量恢复显示 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const rows = await batchDeletePost(checkedRowKeys.value);
    const setIds = new Set(checkedRowKeys.value);
    posts.value = posts.value.filter(p => !setIds.has(p.id));
    total.value = Math.max(0, total.value - rows);
    message.success(`已批量删除 ${rows} 项到回收站`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchRestore() {
  if (!checkSelected()) return;
  try {
    const rows = await batchRestorePost(checkedRowKeys.value);
    const setIds = new Set(checkedRowKeys.value);
    posts.value = posts.value.filter(p => !setIds.has(p.id));
    total.value = Math.max(0, total.value - rows);
    message.success(`已批量恢复 ${rows} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
function openBatchPurge() {
  if (!checkSelected()) return;
  purgeMode.value = 'batch';
  purgeModalShow.value = true;
}
async function handleBatchPurge() {
  purgeLoading.value = true;
  try {
    const r = await batchPurgePost(checkedRowKeys.value);
    const failedIds = new Set<number>(r.failed.map(f => Number(f.id)));
    posts.value = posts.value.filter(p => failedIds.has(p.id));
    total.value = Math.max(0, total.value - r.success);
    let msg = `已彻底删除 ${r.success} 项，级联清理 评论 ${r.counts.comments || 0} / 点赞 ${r.counts.reactions || 0}`;
    if (r.failed.length) {
      msg += `；${r.failed.length} 项失败：` +
        r.failed.slice(0, 5).map(f => `#${f.id}`).join(', ');
    }
    message.success(msg);
    checkedRowKeys.value = [];
    purgeModalShow.value = false;
  } catch {
    message.error('操作失败');
  }
  purgeLoading.value = false;
}

const activeColumns: DataTableColumns<PostVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  { title: '标题', key: 'title', width: 180, ellipsis: { tooltip: true } },
  {
    title: '作者', key: 'author', width: 100,
    render(row) { return row.author?.nickname || '未知'; },
  },
  {
    title: '范围', key: 'scope', width: 70,
    render(row) {
      return h(NTag, { type: row.scope === 'SPACE' ? 'info' : 'default', size: 'small' },
        { default: () => row.scope });
    },
  },
  {
    title: '状态', key: 'status', width: 70,
    render(row) {
      const map: Record<number, { label: string; type: TagType }> = {
        0: { label: '待审', type: 'default' },
        1: { label: '正常', type: 'success' },
        2: { label: '隐藏', type: 'error' },
      };
      const s = map[row.status] || { label: '未知', type: 'default' };
      return h(NTag, { type: s.type, size: 'small' }, { default: () => s.label });
    },
  },
  {
    title: '操作', key: 'actions', width: 340,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NButton, { size: 'tiny', onClick: () => handleTogglePin(row) },
            { default: () => row.isPinned ? '取消置顶' : '置顶' }),
          h(NButton, { size: 'tiny', onClick: () => handleToggleEssence(row) },
            { default: () => row.isEssence ? '取消精华' : '加精' }),
          row.status === 1
            ? h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 2) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '隐藏' }),
                default: () => '确定隐藏该帖子？',
              })
            : h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 1) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
                default: () => '确定恢复该帖子？',
              }),
          h(NPopconfirm, { onPositiveClick: () => handleDelete(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'error' }, { default: () => '删除' }),
            default: () => '删除到回收站，可稍后恢复。是否继续？',
          }),
        ],
      });
    },
  },
];

const trashColumns: DataTableColumns<PostVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  { title: '标题', key: 'title', width: 200, ellipsis: { tooltip: true } },
  {
    title: '作者', key: 'author', width: 100,
    render(row) { return row.author?.nickname || '未知'; },
  },
  {
    title: '范围', key: 'scope', width: 70,
    render(row) {
      return h(NTag, { type: row.scope === 'SPACE' ? 'info' : 'default', size: 'small' },
        { default: () => row.scope });
    },
  },
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定从回收站恢复该帖子？',
          }),
          h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => openPurge(row) },
            { default: () => '彻底删除' }),
        ],
      });
    },
  },
];

function search() {
  pageNum.value = 1;
  loadPosts();
}

watch(activeTab, () => {
  checkedRowKeys.value = [];
  pageNum.value = 1;
  loadPosts();
});

onMounted(() => loadPosts());

const purgeSummary = () => {
  if (purgeMode.value === 'batch') {
    const ids = checkedRowKeys.value;
    const shown = ids.slice(0, 5).map(id => `#${id}`).join('、');
    return `共 ${ids.length} 项：${shown}${ids.length > 5 ? ' …' : ''}`;
  }
  return purgeTargetRow.value ? `#${purgeTargetRow.value.id} ${purgeTargetRow.value.title || ''}` : '';
};
</script>

<template>
  <div class="admin-page">
    <h2>帖子管理</h2>

    <NTabs
      v-model:value="activeTab"
      type="line"
      animated
      class="admin-tabs"
    >
      <NTabPane
        name="active"
        tab="正常"
      />
      <NTabPane
        name="trash"
        tab="回收站"
      />
    </NTabs>

    <NSpace class="admin-filterbar">
      <NInput
        v-model:value="keyword"
        placeholder="搜索标题/内容"
        style="width: 240px;"
        clearable
      />
      <NSelect
        v-if="activeTab === 'active'"
        v-model:value="statusFilter"
        :options="statusOptions"
        placeholder="状态"
        style="width: 100px;"
        clearable
      />
      <NSelect
        v-model:value="scopeFilter"
        :options="scopeOptions"
        placeholder="范围"
        style="width: 100px;"
        clearable
      />
      <NButton
        type="primary"
        @click="search"
      >
        搜索
      </NButton>
    </NSpace>

    <NSpace
      v-if="checkedRowKeys.length"
      class="admin-batch-bar"
    >
      <span>已选 {{ checkedRowKeys.length }} 项</span>
      <template v-if="activeTab === 'active'">
        <NButton
          size="small"
          type="warning"
          @click="batchHide"
        >批量隐藏</NButton>
        <NButton
          size="small"
          type="success"
          @click="batchShow"
        >批量恢复显示</NButton>
        <NPopconfirm @positive-click="batchDelete">
          <template #trigger>
            <NButton
              size="small"
              type="error"
            >批量删除到回收站</NButton>
          </template>
          确认将选中 {{ checkedRowKeys.length }} 项移入回收站？
        </NPopconfirm>
      </template>
      <template v-else>
        <NPopconfirm @positive-click="batchRestore">
          <template #trigger>
            <NButton
              size="small"
              type="success"
            >批量恢复</NButton>
          </template>
          确认恢复选中的 {{ checkedRowKeys.length }} 项？
        </NPopconfirm>
        <NButton
          size="small"
          type="error"
          ghost
          @click="openBatchPurge"
        >批量彻底删除</NButton>
      </template>
    </NSpace>

    <NDataTable
      v-model:checked-row-keys="checkedRowKeys"
      :columns="activeTab === 'active' ? activeColumns : trashColumns"
      :data="posts"
      :loading="loading"
      :bordered="false"
      :row-key="(row: PostVO) => row.id"
      :pagination="{
        page: pageNum,
        pageSize: pageSize,
        itemCount: total,
        pageSizes: [10, 20, 50],
        showSizePicker: true,
        showQuickJumper: true,
        prefix: ({ itemCount }) => `共 ${itemCount} 条`,
      }"
      remote
      @update:page="(p) => { pageNum = p; loadPosts(); }"
      @update:page-size="(s) => { pageSize = s; pageNum = 1; loadPosts(); }"
    />

    <PurgeConfirmModal
      v-model:show="purgeModalShow"
      :summary="purgeSummary()"
      :loading="purgeLoading"
      @confirm="purgeMode === 'batch' ? handleBatchPurge() : handlePurge()"
    />
  </div>
</template>

<style scoped>
.admin-tabs { margin-bottom: 16px; }
.admin-batch-bar {
  margin-bottom: 12px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(56, 189, 248, 0.08);
  border: 1px solid rgba(56, 189, 248, 0.18);
}
</style>
