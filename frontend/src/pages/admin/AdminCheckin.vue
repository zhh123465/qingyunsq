<script setup lang="ts">
import { ref, onMounted, h, watch } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NSpace, NTag, NPopconfirm, NTabs, NTabPane, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminCheckins,
  setCheckinStatus,
  deleteAdminCheckin,
  restoreCheckin,
  purgeCheckin,
  batchSetCheckinStatus,
  batchDeleteCheckin,
  batchRestoreCheckin,
  batchPurgeCheckin,
} from '@/api/admin';
import type { CheckinChallengeVO } from '@/types/checkin';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const rows = ref<CheckinChallengeVO[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const statusFilter = ref<number | null>(null);

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<number[]>([]);

const statusOptions: SelectOption[] = [
  { label: '正常', value: 1 },
  { label: '隐藏', value: 2 },
];

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const r = await getAdminCheckins({
      keyword: keyword.value || undefined,
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

async function handleSetStatus(row: CheckinChallengeVO, status: number) {
  try {
    await setCheckinStatus(row.id, status);
    row.status = status;
    message.success(status === 1 ? '已恢复' : '已隐藏');
  } catch {
    message.error('操作失败');
  }
}

async function handleDelete(row: CheckinChallengeVO) {
  try {
    await deleteAdminCheckin(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: CheckinChallengeVO) {
  try {
    await restoreCheckin(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<CheckinChallengeVO | null>(null);
const purgeLoading = ref(false);
function openPurge(row: CheckinChallengeVO) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    const counts = await purgeCheckin(purgeTargetRow.value.id);
    rows.value = rows.value.filter((r) => r.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success(`已彻底删除，级联清理打卡记录 ${counts.records || 0} 条`);
    purgeModalShow.value = false;
  } catch {
    message.error('操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择挑战'); return false; }
  return true;
}
async function batchHide() {
  if (!checkSelected()) return;
  try {
    await batchSetCheckinStatus(checkedRowKeys.value, 2);
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 2; });
    message.success(`已批量隐藏 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchShow() {
  if (!checkSelected()) return;
  try {
    await batchSetCheckinStatus(checkedRowKeys.value, 1);
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 1; });
    message.success(`已批量恢复 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const cnt = await batchDeleteCheckin(checkedRowKeys.value);
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
    const cnt = await batchRestoreCheckin(checkedRowKeys.value);
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
    const r = await batchPurgeCheckin(checkedRowKeys.value);
    const failedIds = new Set<number>(r.failed.map(f => Number(f.id)));
    rows.value = rows.value.filter(x => failedIds.has(x.id));
    total.value = Math.max(0, total.value - r.success);
    let msg = `已彻底删除 ${r.success} 项，级联清理打卡记录 ${r.counts.records || 0} 条`;
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

const statusTagMap: Record<number, { label: string; type: 'success' | 'warning' | 'error' | 'default' }> = {
  1: { label: '正常', type: 'success' },
  2: { label: '隐藏', type: 'warning' },
  0: { label: '回收站', type: 'error' },
};

const commonColumns: DataTableColumns<CheckinChallengeVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  { title: '名称', key: 'name', width: 200, ellipsis: { tooltip: true } },
  {
    title: '创建者', key: 'creator', width: 100,
    render: (row) => row.creator?.nickname || `ID:${row.creatorId}`,
  },
  { title: '开始', key: 'startDate', width: 110 },
  { title: '结束', key: 'endDate', width: 110 },
  { title: '成员', key: 'memberCount', width: 60 },
];

const activeColumns: DataTableColumns<CheckinChallengeVO> = [
  ...commonColumns,
  {
    title: '状态', key: 'status', width: 80,
    render(row) {
      const s = statusTagMap[row.status] || { label: '未知', type: 'default' as const };
      return h(NTag, { type: s.type, size: 'small' }, { default: () => s.label });
    },
  },
  {
    title: '操作', key: 'actions', width: 280,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NButton, {
            size: 'tiny',
            onClick: () => window.open(`/checkin/${row.id}`, '_blank'),
          }, { default: () => '查看' }),
          row.status === 1
            ? h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 2) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '隐藏' }),
                default: () => '前台不可见。是否隐藏？',
              })
            : h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 1) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
                default: () => '恢复正常状态？',
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

const trashColumns: DataTableColumns<CheckinChallengeVO> = [
  ...commonColumns,
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定恢复该挑战？',
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
  return purgeTargetRow.value ? `#${purgeTargetRow.value.id} ${purgeTargetRow.value.name || ''}` : '';
};
</script>

<template>
  <div class="admin-page">
    <h2>打卡管理</h2>

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
        placeholder="搜索挑战名称"
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
      :row-key="(row: CheckinChallengeVO) => row.id"
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
