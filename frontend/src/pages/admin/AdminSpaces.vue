<script setup lang="ts">
import { ref, onMounted, h, watch } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NSpace, NTag, NPopconfirm, NTabs, NTabPane, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminSpaces,
  setSpaceStatus,
  adminDeleteSpace,
  restoreSpace,
  purgeSpace,
  batchSetSpaceStatus,
  batchDismissSpace,
  batchRestoreSpace,
  batchPurgeSpace,
} from '@/api/admin';
import type { SpaceVO } from '@/types/space';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const spaces = ref<SpaceVO[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const categoryFilter = ref<string | null>(null);
const statusFilter = ref<number | null>(null);

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<number[]>([]);

const categoryOptions: SelectOption[] = [
  { label: '专业', value: 'MAJOR' },
  { label: '班级', value: 'CLASS' },
  { label: '社团', value: 'CLUB' },
  { label: '兴趣', value: 'INTEREST' },
];

const statusOptions: SelectOption[] = [
  { label: '正常', value: 1 },
  { label: '已禁用', value: 0 },
];

async function loadSpaces() {
  if (loading.value) return;
  loading.value = true;
  try {
    const r = await getAdminSpaces({
      keyword: keyword.value || undefined,
      category: categoryFilter.value || undefined,
      status: activeTab.value === 'active' && statusFilter.value != null ? statusFilter.value : undefined,
      trash: activeTab.value === 'trash',
      page: pageNum.value,
      size: pageSize.value,
    });
    spaces.value = r.items;
    total.value = r.total;
  } catch {
    // 忽略加载失败
  }
  loading.value = false;
}

async function handleSetStatus(row: SpaceVO, status: number) {
  try {
    await setSpaceStatus(row.id, status);
    row.status = status;
    message.success(status === 1 ? '已启用' : '已禁用');
  } catch {
    message.error('操作失败');
  }
}

async function handleDismiss(row: SpaceVO) {
  try {
    await adminDeleteSpace(row.id);
    spaces.value = spaces.value.filter((s) => s.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: SpaceVO) {
  try {
    await restoreSpace(row.id);
    spaces.value = spaces.value.filter((s) => s.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<SpaceVO | null>(null);
const purgeLoading = ref(false);
function openPurge(row: SpaceVO) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    const counts = await purgeSpace(purgeTargetRow.value.id);
    spaces.value = spaces.value.filter((s) => s.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success(`已彻底删除，级联清理 成员 ${counts.members || 0} 条`);
    purgeModalShow.value = false;
  } catch (e: any) {
    message.error(e?.message || '操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择空间'); return false; }
  return true;
}
async function batchEnable() {
  if (!checkSelected()) return;
  try {
    await batchSetSpaceStatus(checkedRowKeys.value, 1);
    spaces.value.forEach(s => { if (checkedRowKeys.value.includes(s.id)) s.status = 1; });
    message.success(`已批量启用 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDisable() {
  if (!checkSelected()) return;
  try {
    await batchSetSpaceStatus(checkedRowKeys.value, 0);
    spaces.value.forEach(s => { if (checkedRowKeys.value.includes(s.id)) s.status = 0; });
    message.success(`已批量禁用 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const rows = await batchDismissSpace(checkedRowKeys.value);
    const setIds = new Set(checkedRowKeys.value);
    spaces.value = spaces.value.filter(s => !setIds.has(s.id));
    total.value = Math.max(0, total.value - rows);
    message.success(`已批量删除 ${rows} 项到回收站`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchRestoreItems() {
  if (!checkSelected()) return;
  try {
    const rows = await batchRestoreSpace(checkedRowKeys.value);
    const setIds = new Set(checkedRowKeys.value);
    spaces.value = spaces.value.filter(s => !setIds.has(s.id));
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
    const r = await batchPurgeSpace(checkedRowKeys.value);
    const failedIds = new Set<number>(r.failed.map(f => Number(f.id)));
    spaces.value = spaces.value.filter(s => failedIds.has(s.id));
    total.value = Math.max(0, total.value - r.success);
    let msg = `已彻底删除 ${r.success} 项，级联清理 成员 ${r.counts.members || 0}`;
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

const activeColumns: DataTableColumns<SpaceVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  { title: '名称', key: 'name', width: 160, ellipsis: { tooltip: true } },
  {
    title: '所属者', key: 'owner', width: 100,
    render(row) { return row.owner?.nickname || '未知'; },
  },
  {
    title: '分类', key: 'category', width: 70,
    render(row) {
      const map: Record<string, string> = { MAJOR: '专业', CLASS: '班级', CLUB: '社团', INTEREST: '兴趣' };
      return h(NTag, { size: 'small' }, { default: () => map[row.category] || row.category });
    },
  },
  { title: '成员', key: 'memberCount', width: 60 },
  { title: '帖子', key: 'postCount', width: 60 },
  {
    title: '状态', key: 'status', width: 70,
    render(row) {
      return h(NTag, { type: row.status === 1 ? 'success' : 'error', size: 'small' },
        { default: () => row.status === 1 ? '正常' : '已禁用' });
    },
  },
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          row.status === 1
            ? h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 0) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '禁用' }),
                default: () => '确定禁用该空间？',
              })
            : h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 1) }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '启用' }),
                default: () => '确定启用该空间？',
              }),
          h(NPopconfirm, { onPositiveClick: () => handleDismiss(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'error' }, { default: () => '删除' }),
            default: () => '删除到回收站，可稍后恢复。是否继续？',
          }),
        ],
      });
    },
  },
];

const trashColumns: DataTableColumns<SpaceVO> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 70 },
  { title: '名称', key: 'name', width: 200, ellipsis: { tooltip: true } },
  {
    title: '所属者', key: 'owner', width: 100,
    render(row) { return row.owner?.nickname || '未知'; },
  },
  {
    title: '分类', key: 'category', width: 70,
    render(row) {
      const map: Record<string, string> = { MAJOR: '专业', CLASS: '班级', CLUB: '社团', INTEREST: '兴趣' };
      return h(NTag, { size: 'small' }, { default: () => map[row.category] || row.category });
    },
  },
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定从回收站恢复该空间？',
          }),
          h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => openPurge(row) },
            { default: () => '彻底删除' }),
        ],
      });
    },
  },
];

function search() { pageNum.value = 1; loadSpaces(); }

watch(activeTab, () => {
  checkedRowKeys.value = [];
  pageNum.value = 1;
  loadSpaces();
});

onMounted(() => loadSpaces());

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
    <h2>空间管理</h2>

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
        placeholder="搜索空间名称"
        style="width: 200px;"
        clearable
      />
      <NSelect
        v-model:value="categoryFilter"
        :options="categoryOptions"
        placeholder="分类"
        style="width: 100px;"
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
          type="success"
          @click="batchEnable"
        >批量启用</NButton>
        <NButton
          size="small"
          type="warning"
          @click="batchDisable"
        >批量禁用</NButton>
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
      :data="spaces"
      :loading="loading"
      :bordered="false"
      :row-key="(row: SpaceVO) => row.id"
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
      @update:page="(p) => { pageNum = p; loadSpaces(); }"
      @update:page-size="(s) => { pageSize = s; pageNum = 1; loadSpaces(); }"
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
