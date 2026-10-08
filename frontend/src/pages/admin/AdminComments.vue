<script setup lang="ts">
import { ref, onMounted, h, watch } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NSpace, NTag, NPopconfirm, NTabs, NTabPane, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminComments,
  setCommentStatus,
  deleteAdminComment,
  restoreComment,
  purgeComment,
  batchSetCommentStatus,
  batchDeleteComment,
  batchRestoreComment,
  batchPurgeComment,
} from '@/api/admin';
import type { AdminCommentVO } from '@/types/admin';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const rows = ref<AdminCommentVO[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const postIdInput = ref('');
const authorIdInput = ref('');
const statusFilter = ref<number | null>(null);

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<number[]>([]);

const statusOptions: SelectOption[] = [
  { label: '正常', value: 1 },
  { label: '隐藏', value: 0 },
];

function parseNum(v: string): number | undefined {
  if (!v) return undefined;
  const n = Number(v);
  return Number.isNaN(n) ? undefined : n;
}

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const r = await getAdminComments({
      keyword: keyword.value || undefined,
      postId: parseNum(postIdInput.value),
      authorId: parseNum(authorIdInput.value),
      status: activeTab.value === 'active' && statusFilter.value != null ? statusFilter.value : undefined,
      trash: activeTab.value === 'trash',
      page: pageNum.value,
      size: pageSize.value,
    });
    rows.value = r.items;
    total.value = r.total;
  } catch {
    /* ignore */
  }
  loading.value = false;
}

async function handleSetStatus(row: AdminCommentVO, status: number) {
  try {
    await setCommentStatus(row.id, status);
    row.status = status;
    message.success(status === 1 ? '已恢复' : '已隐藏');
  } catch {
    message.error('操作失败');
  }
}

async function handleDelete(row: AdminCommentVO) {
  try {
    await deleteAdminComment(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: AdminCommentVO) {
  try {
    await restoreComment(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<AdminCommentVO | null>(null);
const purgeLoading = ref(false);
function openPurge(row: AdminCommentVO) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    const counts = await purgeComment(purgeTargetRow.value.id);
    rows.value = rows.value.filter((r) => r.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success(`已彻底删除，清理点赞 ${counts.reactions || 0} 条`);
    purgeModalShow.value = false;
  } catch {
    message.error('操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择评论'); return false; }
  return true;
}
async function batchHide() {
  if (!checkSelected()) return;
  try {
    await batchSetCommentStatus(checkedRowKeys.value, 0);
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 0; });
    message.success(`已批量隐藏 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchShow() {
  if (!checkSelected()) return;
  try {
    await batchSetCommentStatus(checkedRowKeys.value, 1);
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 1; });
    message.success(`已批量恢复显示 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const cnt = await batchDeleteComment(checkedRowKeys.value);
    const setIds = new Set(checkedRowKeys.value);
    rows.value = rows.value.filter(r => !setIds.has(r.id));
    total.value = Math.max(0, total.value - cnt);
    message.success(`已批量删除 ${cnt} 项到回收站`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchRestoreItems() {
  if (!checkSelected()) return;
  try {
    const cnt = await batchRestoreComment(checkedRowKeys.value);
    const setIds = new Set(checkedRowKeys.value);
    rows.value = rows.value.filter(r => !setIds.has(r.id));
    total.value = Math.max(0, total.value - cnt);
    message.success(`已批量恢复 ${cnt} 项`);
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
    const r = await batchPurgeComment(checkedRowKeys.value);
    const failedIds = new Set<number>(r.failed.map(f => Number(f.id)));
    rows.value = rows.value.filter(x => failedIds.has(x.id));
    total.value = Math.max(0, total.value - r.success);
    let msg = `已彻底删除 ${r.success} 项，级联清理 点赞 ${r.counts.reactions || 0}`;
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

// 去除简单 HTML 标签以便表格中显示纯文本
function plain(html: string): string {
  if (!html) return '';
  return html.replace(/<[^>]+>/g, '').slice(0, 200);
}

const commonColumns: DataTableColumns<AdminCommentVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  {
    title: '所属帖子', key: 'post', width: 200, ellipsis: { tooltip: true },
    render: (row) => h(
      'a',
      { href: `/posts/${row.postId}`, target: '_blank', style: 'color: var(--cf-primary)' },
      `#${row.postId} ${row.postTitle || ''}`,
    ),
  },
  {
    title: '作者', key: 'author', width: 110,
    render: (row) => row.author?.nickname || `ID:${row.authorId}`,
  },
  {
    title: '内容', key: 'content', width: 320, ellipsis: { tooltip: true },
    render: (row) => plain(row.content),
  },
  { title: '点赞', key: 'likeCount', width: 60 },
  {
    title: '时间', key: 'createdAt', width: 150,
    render: (row) => new Date(row.createdAt).toLocaleString(),
  },
];

const activeColumns: DataTableColumns<AdminCommentVO> = [
  ...commonColumns,
  {
    title: '状态', key: 'status', width: 70,
    render(row) {
      return h(NTag, { type: row.status === 1 ? 'success' : 'error', size: 'small' },
        { default: () => row.status === 1 ? '正常' : '隐藏' });
    },
  },
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          row.status === 1
            ? h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 0) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '隐藏' }),
                default: () => '确定隐藏该评论？',
              })
            : h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 1) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
                default: () => '确定恢复该评论？',
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

const trashColumns: DataTableColumns<AdminCommentVO> = [
  ...commonColumns,
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定恢复该评论？',
          }),
          h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => openPurge(row) },
            { default: () => '彻底删除' }),
        ],
      });
    },
  },
];

function search() { pageNum.value = 1; load(); }

watch(activeTab, () => {
  checkedRowKeys.value = [];
  pageNum.value = 1;
  load();
});

onMounted(() => load());

const purgeSummary = () => {
  if (purgeMode.value === 'batch') {
    const ids = checkedRowKeys.value;
    const shown = ids.slice(0, 5).map(id => `#${id}`).join('、');
    return `共 ${ids.length} 项：${shown}${ids.length > 5 ? ' …' : ''}`;
  }
  return purgeTargetRow.value
    ? `#${purgeTargetRow.value.id} - 帖子 #${purgeTargetRow.value.postId} - 作者 ${purgeTargetRow.value.author?.nickname || purgeTargetRow.value.authorId}`
    : '';
};
</script>

<template>
  <div class="admin-page">
    <h2>评论管理</h2>

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
        placeholder="搜索评论内容"
        style="width: 240px;"
        clearable
      />
      <NInput
        v-model:value="postIdInput"
        placeholder="所属帖子 ID"
        style="width: 140px;"
        clearable
      />
      <NInput
        v-model:value="authorIdInput"
        placeholder="作者 ID"
        style="width: 140px;"
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
        <NPopconfirm @positive-click="batchRestoreItems">
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
      :data="rows"
      :loading="loading"
      :bordered="false"
      :row-key="(row: AdminCommentVO) => row.id"
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
      @update:page="(p) => { pageNum = p; load(); }"
      @update:page-size="(s) => { pageSize = s; pageNum = 1; load(); }"
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
