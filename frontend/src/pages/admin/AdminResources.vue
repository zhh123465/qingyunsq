<script setup lang="ts">
import { ref, onMounted, h, watch } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NSpace, NTag, NPopconfirm, NTabs, NTabPane, NModal, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminResources,
  setResourceStatus,
  deleteAdminResource,
  restoreResource,
  purgeResource,
  batchSetResourceStatus,
  batchDeleteResource,
  batchRestoreResource,
  batchPurgeResource,
  approveResource,
  rejectResource,
  batchApproveResource,
} from '@/api/admin';
import type { AdminResourceVO } from '@/types/admin';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const rows = ref<AdminResourceVO[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const visibilityFilter = ref<string | null>(null);
const statusFilter = ref<number | null>(null);

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<number[]>([]);

const visibilityOptions: SelectOption[] = [
  { label: '公开', value: 'PUBLIC' },
  { label: '空间', value: 'SPACE' },
  { label: '私有', value: 'PRIVATE' },
];

const statusOptions: SelectOption[] = [
  { label: '待审核', value: 2 },
  { label: '已发布', value: 1 },
  { label: '已驳回', value: 3 },
  { label: '隐藏', value: 0 },
];

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const r = await getAdminResources({
      keyword: keyword.value || undefined,
      visibility: visibilityFilter.value || undefined,
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

async function handleSetStatus(row: AdminResourceVO, status: number) {
  try {
    await setResourceStatus(row.id, status);
    row.status = status;
    message.success(status === 1 ? '已恢复' : '已隐藏');
  } catch {
    message.error('操作失败');
  }
}

// === 审核（2026-07-13）===
async function handleApprove(row: AdminResourceVO) {
  try {
    await approveResource(row.id);
    row.status = 1;
    row.reviewReason = null;
    message.success('已通过并发布');
  } catch {
    message.error('操作失败');
  }
}

const rejectModalShow = ref(false);
const rejectTargetRow = ref<AdminResourceVO | null>(null);
const rejectReason = ref('');
const rejectLoading = ref(false);
function openReject(row: AdminResourceVO) {
  rejectTargetRow.value = row;
  rejectReason.value = '';
  rejectModalShow.value = true;
}
async function handleReject(): Promise<boolean> {
  // preset=dialog 的 positive-click 返回 false 可阻止弹窗自动关闭（校验失败/请求失败时保留输入）
  if (!rejectTargetRow.value) return false;
  const reason = rejectReason.value.trim();
  if (!reason) { message.warning('请填写驳回原因'); return false; }
  rejectLoading.value = true;
  try {
    await rejectResource(rejectTargetRow.value.id, reason);
    rejectTargetRow.value.status = 3;
    rejectTargetRow.value.reviewReason = reason;
    message.success('已驳回，上传者会收到通知');
    rejectLoading.value = false;
    return true;
  } catch {
    message.error('操作失败');
    rejectLoading.value = false;
    return false;
  }
}

async function batchApprove() {
  if (!checkSelected()) return;
  try {
    const r = await batchApproveResource(checkedRowKeys.value);
    const okIds = new Set(checkedRowKeys.value.filter(id => !r.failed.includes(id)));
    rows.value.forEach(row => { if (okIds.has(row.id)) { row.status = 1; row.reviewReason = null; } });
    let msg = `已批量通过 ${r.success} 项`;
    if (r.failed.length) msg += `；${r.failed.length} 项失败`;
    message.success(msg);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}

async function handleDelete(row: AdminResourceVO) {
  try {
    await deleteAdminResource(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: AdminResourceVO) {
  try {
    await restoreResource(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<AdminResourceVO | null>(null);
const purgeLoading = ref(false);
function openPurge(row: AdminResourceVO) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    const counts = await purgeResource(purgeTargetRow.value.id);
    rows.value = rows.value.filter((r) => r.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success(
      `已彻底删除，清理点赞收藏 ${counts.reactions || 0} 条 + 存储对象`,
    );
    purgeModalShow.value = false;
  } catch (e: any) {
    message.error(e?.message || '操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择资源'); return false; }
  return true;
}
async function batchHide() {
  if (!checkSelected()) return;
  try {
    await batchSetResourceStatus(checkedRowKeys.value, 0);
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 0; });
    message.success(`已批量隐藏 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchShow() {
  if (!checkSelected()) return;
  try {
    await batchSetResourceStatus(checkedRowKeys.value, 1);
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 1; });
    message.success(`已批量恢复显示 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const cnt = await batchDeleteResource(checkedRowKeys.value);
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
    const cnt = await batchRestoreResource(checkedRowKeys.value);
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
    const r = await batchPurgeResource(checkedRowKeys.value);
    const failedIds = new Set<number>(r.failed.map(f => Number(f.id)));
    rows.value = rows.value.filter(x => failedIds.has(x.id));
    total.value = Math.max(0, total.value - r.success);
    let msg = `已彻底删除 ${r.success} 项，级联清理 点赞收藏 ${r.counts.reactions || 0}`;
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

function formatSize(bytes: number): string {
  if (!bytes) return '-';
  const kb = bytes / 1024;
  if (kb < 1024) return kb.toFixed(1) + ' KB';
  const mb = kb / 1024;
  if (mb < 1024) return mb.toFixed(2) + ' MB';
  return (mb / 1024).toFixed(2) + ' GB';
}

const visibilityLabel: Record<string, string> = {
  PUBLIC: '公开', SPACE: '空间', PRIVATE: '私有',
};

const commonColumns: DataTableColumns<AdminResourceVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  { title: '文件名', key: 'fileName', width: 220, ellipsis: { tooltip: true } },
  { title: '类型', key: 'fileType', width: 70 },
  {
    title: '大小', key: 'fileSize', width: 90,
    render: (row) => formatSize(row.fileSize),
  },
  {
    title: '上传者', key: 'uploader', width: 100,
    render: (row) => row.uploader?.nickname || `ID:${row.uploaderId}`,
  },
  {
    title: '可见性', key: 'visibility', width: 80,
    render: (row) => h(NTag, { size: 'small' }, { default: () => visibilityLabel[row.visibility] || row.visibility }),
  },
  { title: '下载', key: 'downloadCount', width: 70 },
];

const statusTagMap: Record<number, { type: 'success' | 'error' | 'warning' | 'default'; label: string }> = {
  1: { type: 'success', label: '已发布' },
  0: { type: 'error', label: '隐藏' },
  2: { type: 'warning', label: '待审核' },
  3: { type: 'error', label: '已驳回' },
};

const activeColumns: DataTableColumns<AdminResourceVO> = [
  ...commonColumns,
  {
    title: '状态', key: 'status', width: 80,
    render(row) {
      const tag = statusTagMap[row.status] || { type: 'default' as const, label: String(row.status) };
      return h(NTag, { type: tag.type, size: 'small' }, { default: () => tag.label });
    },
  },
  {
    title: '操作', key: 'actions', width: 300,
    render(row) {
      const buttons = [
        h(NButton, {
          size: 'tiny',
          onClick: () => window.open(`/resources/${row.id}`, '_blank'),
        }, { default: () => '查看' }),
      ];
      if (row.status === 2 || row.status === 3) {
        buttons.push(
          h(NPopconfirm, { onPositiveClick: () => handleApprove(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '通过' }),
            default: () => '通过后立即对外发布，是否继续？',
          }),
        );
      }
      if (row.status === 2) {
        buttons.push(
          h(NButton, { size: 'tiny', type: 'error', onClick: () => openReject(row) }, { default: () => '驳回' }),
        );
      }
      if (row.status === 1) {
        buttons.push(
          h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 0) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '隐藏' }),
            default: () => '确定隐藏该资源？',
          }),
        );
      }
      if (row.status === 0) {
        buttons.push(
          h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 1) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定恢复该资源？',
          }),
        );
      }
      buttons.push(
        h(NPopconfirm, { onPositiveClick: () => handleDelete(row) }, {
          trigger: () => h(NButton, { size: 'tiny', type: 'error' }, { default: () => '删除' }),
          default: () => '删除到回收站，可稍后恢复。是否继续？',
        }),
      );
      return h(NSpace, null, { default: () => buttons });
    },
  },
];

const trashColumns: DataTableColumns<AdminResourceVO> = [
  ...commonColumns,
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定恢复该资源？',
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
    ? `#${purgeTargetRow.value.id} ${purgeTargetRow.value.fileName || ''} - ${purgeTargetRow.value.uploader?.nickname || ''}`
    : '';
};
</script>

<template>
  <div class="admin-page">
    <h2>资源管理</h2>

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
        placeholder="搜索文件名/描述"
        style="width: 240px;"
        clearable
      />
      <NSelect
        v-model:value="visibilityFilter"
        :options="visibilityOptions"
        placeholder="可见性"
        style="width: 110px;"
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
        <NPopconfirm @positive-click="batchApprove">
          <template #trigger>
            <NButton
              size="small"
              type="success"
            >批量通过审核</NButton>
          </template>
          将选中 {{ checkedRowKeys.length }} 项全部通过并发布？
        </NPopconfirm>
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
      :row-key="(row: AdminResourceVO) => row.id"
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

    <NModal
      v-model:show="rejectModalShow"
      preset="dialog"
      title="驳回资源"
      positive-text="确认驳回"
      negative-text="取消"
      :loading="rejectLoading"
      @positive-click="handleReject"
    >
      <p style="margin: 0 0 10px;">
        驳回「{{ rejectTargetRow?.fileName }}」，原因会通过站内通知发给上传者：
      </p>
      <NInput
        v-model:value="rejectReason"
        type="textarea"
        placeholder="例如：文件内容与描述不符 / 疑似含有恶意程序 / 侵犯版权"
        maxlength="255"
        show-count
      />
    </NModal>
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
