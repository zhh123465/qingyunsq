<script setup lang="ts">
import { ref, onMounted, h, watch } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NInputNumber, NSwitch, NSpace, NTag, NPopconfirm,
  NTabs, NTabPane, NModal, NForm, NFormItem, NTooltip, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminNotes,
  setNoteStatus,
  approveNote,
  rejectNote,
  batchApproveNotes,
  deleteAdminNote,
  restoreNote,
  purgeNote,
  batchSetNoteStatus,
  batchDeleteNote,
  batchRestoreNote,
  batchPurgeNote,
  syncExternalNotes,
} from '@/api/admin';
import type { SyncExternalNotesRequest } from '@/api/admin';
import type { AdminNoteItem } from '@/types/admin';
import { NOTE_TAGS } from '@/data/note-tags';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const rows = ref<AdminNoteItem[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const statusFilter = ref<string | null>(null);
const ownerIdInput = ref<string>('');

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<string[]>([]);

const statusOptions: SelectOption[] = [
  { label: '待审核', value: 'pending' },
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' },
  { label: '已驳回', value: 'rejected' },
  { label: '已隐藏', value: 'hidden' },
];

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const ownerId = ownerIdInput.value ? Number(ownerIdInput.value) : undefined;
    const res = await getAdminNotes({
      keyword: keyword.value || undefined,
      status: activeTab.value === 'active' && statusFilter.value ? statusFilter.value : undefined,
      ownerId: ownerId && !Number.isNaN(ownerId) ? ownerId : undefined,
      trash: activeTab.value === 'trash',
      page: pageNum.value,
      size: pageSize.value,
    });
    rows.value = res.items || [];
    total.value = res.total || 0;
  } catch {
    /* ignore */
  }
  loading.value = false;
}

const statusTagMap: Record<string, { label: string; type: 'default' | 'success' | 'warning' | 'error' }> = {
  draft: { label: '草稿', type: 'default' },
  pending: { label: '待审核', type: 'warning' },
  published: { label: '已发布', type: 'success' },
  rejected: { label: '已驳回', type: 'error' },
  hidden: { label: '已隐藏', type: 'warning' },
};

// === 审核流（2026-07-16），照 AdminResources 同款 ===
async function handleApprove(row: AdminNoteItem) {
  try {
    await approveNote(row.id);
    row.status = 'published';
    row.reviewReason = '';
    message.success('已通过审核并发布，作者会收到通知');
  } catch {
    message.error('操作失败');
  }
}

const rejectModalShow = ref(false);
const rejectTargetRow = ref<AdminNoteItem | null>(null);
const rejectReason = ref('');
const rejectLoading = ref(false);
function openReject(row: AdminNoteItem) {
  rejectTargetRow.value = row;
  rejectReason.value = '';
  rejectModalShow.value = true;
}
async function handleReject() {
  if (!rejectTargetRow.value) return false;
  const reason = rejectReason.value.trim();
  if (!reason) { message.warning('请填写驳回原因'); return false; }
  rejectLoading.value = true;
  try {
    await rejectNote(rejectTargetRow.value.id, reason);
    rejectTargetRow.value.status = 'rejected';
    rejectTargetRow.value.reviewReason = reason;
    message.success('已驳回，作者会收到通知');
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
    const r = await batchApproveNotes(checkedRowKeys.value);
    const okIds = new Set(checkedRowKeys.value.filter(id => !r.failed.includes(id)));
    rows.value.forEach(row => { if (okIds.has(row.id)) { row.status = 'published'; row.reviewReason = ''; } });
    let msg = `已批量通过 ${r.success} 项`;
    if (r.failed.length) msg += `；${r.failed.length} 项失败`;
    message.success(msg);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}

async function changeStatus(row: AdminNoteItem, target: string) {
  try {
    await setNoteStatus(row.id, target);
    row.status = target;
    message.success('状态已更新');
  } catch {
    message.error('操作失败');
  }
}

async function handleDelete(row: AdminNoteItem) {
  try {
    await deleteAdminNote(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: AdminNoteItem) {
  try {
    await restoreNote(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<AdminNoteItem | null>(null);
const purgeLoading = ref(false);
function openPurge(row: AdminNoteItem) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    await purgeNote(purgeTargetRow.value.id);
    rows.value = rows.value.filter((r) => r.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已彻底删除，包括存储对象');
    purgeModalShow.value = false;
  } catch {
    message.error('操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择笔记'); return false; }
  return true;
}
async function batchHide() {
  if (!checkSelected()) return;
  try {
    await batchSetNoteStatus(checkedRowKeys.value, 'hidden');
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 'hidden'; });
    message.success(`已批量隐藏 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchPublish() {
  if (!checkSelected()) return;
  try {
    await batchSetNoteStatus(checkedRowKeys.value, 'published');
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 'published'; });
    message.success(`已批量发布 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const cnt = await batchDeleteNote(checkedRowKeys.value);
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
    const cnt = await batchRestoreNote(checkedRowKeys.value);
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
    const r = await batchPurgeNote(checkedRowKeys.value);
    const failedIds = new Set<string>(r.failed.map(f => String(f.id)));
    rows.value = rows.value.filter(x => failedIds.has(x.id));
    total.value = Math.max(0, total.value - r.success);
    let msg = `已彻底删除 ${r.success} 项`;
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

const commonColumns: DataTableColumns<AdminNoteItem> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 100, ellipsis: { tooltip: true } },
  { title: '标题', key: 'title', width: 220, ellipsis: { tooltip: true } },
  {
    title: '作者', key: 'ownerName', width: 120,
    render: (row) => row.ownerName || `ID:${row.ownerId}`,
  },
  { title: '阅读', key: 'viewCount', width: 70 },
  {
    title: '更新', key: 'updatedAt', width: 160,
    render: (row) => new Date(row.updatedAt).toLocaleString(),
  },
];

const activeColumns: DataTableColumns<AdminNoteItem> = [
  ...commonColumns,
  {
    title: '状态', key: 'status', width: 90,
    render(row) {
      const s = statusTagMap[row.status] || { label: row.status, type: 'default' as const };
      const tag = h(NTag, { type: s.type, size: 'small' }, { default: () => s.label });
      // 已驳回行悬浮显示驳回原因
      if (row.status === 'rejected' && row.reviewReason) {
        return h(NTooltip, null, {
          trigger: () => tag,
          default: () => `驳回原因：${row.reviewReason}`,
        });
      }
      return tag;
    },
  },
  {
    title: '操作', key: 'actions', width: 360,
    render(row) {
      const buttons = [
        h(NButton, {
          size: 'tiny',
          onClick: () => window.open(`/learning/notes/${row.id}`, '_blank'),
        }, { default: () => '预览' }),
      ];
      // 审核流：待审/已驳回可「通过」，待审可「驳回」
      if (row.status === 'pending' || row.status === 'rejected') {
        buttons.push(h(NPopconfirm, { onPositiveClick: () => handleApprove(row) }, {
          trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '通过' }),
          default: () => '通过后笔记将公开可见，作者会收到通知。确认？',
        }));
      }
      if (row.status === 'pending') {
        buttons.push(h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => openReject(row) },
          { default: () => '驳回' }));
      }
      if (row.status === 'published') {
        buttons.push(h(NPopconfirm, { onPositiveClick: () => changeStatus(row, 'hidden') }, {
          trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '隐藏' }),
          default: () => '前台将不可见，仅管理端可看。是否隐藏？',
        }));
      }
      if (row.status === 'hidden') {
        buttons.push(h(NPopconfirm, { onPositiveClick: () => changeStatus(row, 'published') }, {
          trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
          default: () => '恢复发布状态？',
        }));
      }
      buttons.push(h(NPopconfirm, { onPositiveClick: () => handleDelete(row) }, {
        trigger: () => h(NButton, { size: 'tiny', type: 'error' }, { default: () => '删除' }),
        default: () => '删除到回收站，可稍后恢复。是否继续？',
      }));
      return h(NSpace, null, { default: () => buttons });
    },
  },
];

const trashColumns: DataTableColumns<AdminNoteItem> = [
  ...commonColumns,
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定恢复该笔记？',
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

// ============ 同步外部笔记（VitePress / cnblogs / 单页） ============
const showSyncModal = ref(false);
const syncSubmitting = ref(false);
const syncForm = ref<SyncExternalNotesRequest>({
  source: 'single',
  rootUrl: '',
  recursive: false,
  sourceName: '',
  sourceAuthor: '',
  tags: [],
  ownerId: 1,
});
const syncSourceOptions: SelectOption[] = [
  { label: '单页（single）', value: 'single' },
  { label: 'VitePress 站点（vitepress）', value: 'vitepress' },
  { label: '博客园主页（cnblogs）', value: 'cnblogs' },
];
const noteTagOptions = NOTE_TAGS.map(t => ({ label: t, value: t }));

function openSyncModal() {
  syncForm.value = {
    source: 'single', rootUrl: '', recursive: false,
    sourceName: '', sourceAuthor: '', tags: [], ownerId: 1,
  };
  showSyncModal.value = true;
}

async function handleSyncSubmit() {
  if (!syncForm.value.rootUrl || !/^https?:\/\/.+/.test(syncForm.value.rootUrl)) {
    message.warning('请填写合法的 http(s) URL');
    return;
  }
  if (!syncForm.value.sourceName || !syncForm.value.sourceAuthor) {
    message.warning('请填写原站显示名和原作者');
    return;
  }
  syncSubmitting.value = true;
  try {
    const r = await syncExternalNotes(syncForm.value);
    let msg = `已同步 ${r.synced} 篇笔记`;
    if (r.failed.length) {
      msg += `；${r.failed.length} 篇失败：` +
        r.failed.slice(0, 5).map(f => f.url.split('/').pop() || f.url).join(', ');
    }
    message.success(msg);
    showSyncModal.value = false;
    pageNum.value = 1;
    await load();
  } catch (e: any) {
    message.error(e?.message || '同步失败');
  }
  syncSubmitting.value = false;
}

const purgeSummary = () => {
  if (purgeMode.value === 'batch') {
    const ids = checkedRowKeys.value;
    const shown = ids.slice(0, 5).map(id => `#${id}`).join('、');
    return `共 ${ids.length} 项：${shown}${ids.length > 5 ? ' …' : ''}`;
  }
  return purgeTargetRow.value
    ? `${purgeTargetRow.value.id} ${purgeTargetRow.value.title || ''} - ${purgeTargetRow.value.ownerName || ''}`
    : '';
};
</script>

<template>
  <div class="admin-page">
    <h2>笔记管理</h2>

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
        placeholder="搜索标题"
        style="width: 220px;"
        clearable
      />
      <NInput
        v-model:value="ownerIdInput"
        placeholder="作者 ID"
        style="width: 140px;"
        clearable
      />
      <NSelect
        v-if="activeTab === 'active'"
        v-model:value="statusFilter"
        :options="statusOptions"
        placeholder="状态"
        style="width: 120px;"
        clearable
      />
      <NButton
        type="primary"
        @click="search"
      >
        搜索
      </NButton>
      <NButton
        type="primary"
        ghost
        @click="openSyncModal"
      >
        同步外部笔记
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
          确认通过选中的 {{ checkedRowKeys.length }} 项？通过后公开可见，作者会收到通知。
        </NPopconfirm>
        <NButton
          size="small"
          type="warning"
          @click="batchHide"
        >批量隐藏</NButton>
        <NButton
          size="small"
          type="success"
          @click="batchPublish"
        >批量发布</NButton>
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
      :row-key="(row: AdminNoteItem) => row.id"
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

    <!-- 驳回笔记 Modal（审核流 2026-07-16） -->
    <NModal
      v-model:show="rejectModalShow"
      preset="dialog"
      title="驳回笔记"
      positive-text="确认驳回"
      negative-text="取消"
      :loading="rejectLoading"
      @positive-click="handleReject"
    >
      <p style="margin: 0 0 10px;">
        驳回「{{ rejectTargetRow?.title }}」，原因会通过站内通知发给作者：
      </p>
      <NInput
        v-model:value="rejectReason"
        type="textarea"
        placeholder="例如：内容与学习无关 / 含有违规信息 / 疑似抄袭未注明出处"
        maxlength="255"
        show-count
      />
    </NModal>

    <!-- 同步外部笔记 Modal -->
    <NModal
      v-model:show="showSyncModal"
      preset="card"
      title="同步外部笔记"
      style="width: 560px;"
      :mask-closable="false"
    >
      <NForm
        label-placement="left"
        label-width="90"
        :model="syncForm"
      >
        <NFormItem label="来源类型">
          <NSelect
            v-model:value="syncForm.source"
            :options="syncSourceOptions"
          />
        </NFormItem>
        <NFormItem label="源 URL">
          <NInput
            v-model:value="syncForm.rootUrl"
            placeholder="https://blog-wheat-one-34.vercel.app/MyVault/xxx/"
          />
        </NFormItem>
        <NFormItem
          v-if="syncForm.source === 'vitepress'"
          label="递归子目录"
        >
          <NSwitch v-model:value="syncForm.recursive" />
          <span style="margin-left: 12px; color: rgba(0,0,0,0.4); font-size: 12px;">
            开启后会深入子目录抓取全部页面（单次上限 50 篇）
          </span>
        </NFormItem>
        <NFormItem label="原站显示名">
          <NInput
            v-model:value="syncForm.sourceName"
            placeholder="如 cnblogs.com/LFmin"
          />
        </NFormItem>
        <NFormItem label="原作者">
          <NInput
            v-model:value="syncForm.sourceAuthor"
            placeholder="如 LFmin"
          />
        </NFormItem>
        <NFormItem label="标签">
          <NSelect
            v-model:value="syncForm.tags"
            multiple
            filterable
            :options="noteTagOptions"
            placeholder="选择 1~8 个学习笔记标签"
          />
        </NFormItem>
        <NFormItem label="Owner ID">
          <NInputNumber
            v-model:value="syncForm.ownerId"
            :min="1"
            style="width: 120px;"
          />
          <span style="margin-left: 12px; color: rgba(0,0,0,0.4); font-size: 12px;">
            通常填 1（超管）；每条笔记在学习页会显示此账号头像作为"上传者"，出处 banner 里另标注原作者。
          </span>
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showSyncModal = false">取消</NButton>
          <NButton
            type="primary"
            :loading="syncSubmitting"
            @click="handleSyncSubmit"
          >开始同步</NButton>
        </NSpace>
      </template>
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
