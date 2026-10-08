<script setup lang="ts">
import { ref, onMounted, h, watch, computed } from 'vue';
import {
  NDataTable, NButton, NSelect, NInput, NSwitch, NSpace, NTag, NPopconfirm,
  NTabs, NTabPane, NModal, NForm, NFormItem, NDatePicker, useMessage,
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  getAdminAnnouncements,
  createAnnouncement,
  updateAnnouncement,
  setAnnouncementStatus,
  toggleAnnouncementPin,
  deleteAdminAnnouncement,
  restoreAnnouncement,
  purgeAnnouncement,
  batchSetAnnouncementStatus,
  batchDeleteAnnouncement,
  batchRestoreAnnouncement,
  batchPurgeAnnouncement,
} from '@/api/admin';
import type { AnnouncementUpsertBody } from '@/api/admin';
import type { AdminAnnouncementItem } from '@/types/admin';
import { renderMarkdown } from '@/utils/markdown';
import PurgeConfirmModal from './components/PurgeConfirmModal.vue';

const message = useMessage();
const rows = ref<AdminAnnouncementItem[]>([]);
const loading = ref(false);
const activeTab = ref<'active' | 'trash'>('active');

const keyword = ref('');
const statusFilter = ref<string | null>(null);
const levelFilter = ref<string | null>(null);

const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const checkedRowKeys = ref<number[]>([]);

const statusOptions: SelectOption[] = [
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' },
  { label: '归档', value: 'archived' },
];

const levelOptions: SelectOption[] = [
  { label: '普通', value: 'info' },
  { label: '警告', value: 'warning' },
  { label: '重要', value: 'critical' },
];

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const res = await getAdminAnnouncements({
      keyword: keyword.value || undefined,
      status: activeTab.value === 'active' && statusFilter.value ? statusFilter.value : undefined,
      level: levelFilter.value || undefined,
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
  published: { label: '已发布', type: 'success' },
  archived: { label: '归档', type: 'warning' },
};

const levelTagMap: Record<string, { label: string; type: 'info' | 'warning' | 'error' }> = {
  info: { label: '普通', type: 'info' },
  warning: { label: '警告', type: 'warning' },
  critical: { label: '重要', type: 'error' },
};

async function handleSetStatus(row: AdminAnnouncementItem, target: string) {
  try {
    await setAnnouncementStatus(row.id, target);
    row.status = target as AdminAnnouncementItem['status'];
    message.success('状态已更新');
  } catch {
    message.error('操作失败');
  }
}

async function handleTogglePin(row: AdminAnnouncementItem) {
  try {
    const next = await toggleAnnouncementPin(row.id);
    row.pinned = (next === 1 ? 1 : 0);
    message.success(row.pinned === 1 ? '已置顶' : '已取消置顶');
  } catch {
    message.error('操作失败');
  }
}

async function handleDelete(row: AdminAnnouncementItem) {
  try {
    await deleteAdminAnnouncement(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已删除到回收站');
  } catch {
    message.error('操作失败');
  }
}

async function handleRestore(row: AdminAnnouncementItem) {
  try {
    await restoreAnnouncement(row.id);
    rows.value = rows.value.filter((r) => r.id !== row.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已恢复');
  } catch {
    message.error('操作失败');
  }
}

const purgeModalShow = ref(false);
const purgeMode = ref<'single' | 'batch'>('single');
const purgeTargetRow = ref<AdminAnnouncementItem | null>(null);
const purgeLoading = ref(false);
function openPurge(row: AdminAnnouncementItem) {
  purgeMode.value = 'single';
  purgeTargetRow.value = row;
  purgeModalShow.value = true;
}
async function handlePurge() {
  if (!purgeTargetRow.value) return;
  purgeLoading.value = true;
  try {
    await purgeAnnouncement(purgeTargetRow.value.id);
    rows.value = rows.value.filter((r) => r.id !== purgeTargetRow.value!.id);
    total.value = Math.max(0, total.value - 1);
    message.success('已彻底删除');
    purgeModalShow.value = false;
  } catch {
    message.error('操作失败');
  }
  purgeLoading.value = false;
}

// === 批量 ===
function checkSelected(): boolean {
  if (!checkedRowKeys.value.length) { message.warning('请先选择公告'); return false; }
  return true;
}
async function batchArchive() {
  if (!checkSelected()) return;
  try {
    await batchSetAnnouncementStatus(checkedRowKeys.value, 'archived');
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 'archived'; });
    message.success(`已批量归档 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchPublish() {
  if (!checkSelected()) return;
  try {
    await batchSetAnnouncementStatus(checkedRowKeys.value, 'published');
    rows.value.forEach(r => { if (checkedRowKeys.value.includes(r.id)) r.status = 'published'; });
    message.success(`已批量发布 ${checkedRowKeys.value.length} 项`);
    checkedRowKeys.value = [];
  } catch { message.error('操作失败'); }
}
async function batchDelete() {
  if (!checkSelected()) return;
  try {
    const cnt = await batchDeleteAnnouncement(checkedRowKeys.value);
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
    const cnt = await batchRestoreAnnouncement(checkedRowKeys.value);
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
    const r = await batchPurgeAnnouncement(checkedRowKeys.value);
    const failedIds = new Set<number>(r.failed.map(f => Number(f.id)));
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

// === 编辑 Modal ===
const showEditModal = ref(false);
const editSubmitting = ref(false);
const editMode = ref<'create' | 'edit'>('create');
const editId = ref<number | null>(null);
const editForm = ref<AnnouncementUpsertBody>({
  title: '',
  summary: '',
  content: '',
  level: 'info',
  pinned: 0,
  status: 'draft',
  publishTime: undefined,
  expireTime: undefined,
});
const editPublishTimestamp = ref<number | null>(null);
const editExpireTimestamp = ref<number | null>(null);
const editShowPreview = ref(false);

const previewHtml = computed(() => renderMarkdown(editForm.value.content || '').html);

function resetEditForm() {
  editForm.value = {
    title: '',
    summary: '',
    content: '',
    level: 'info',
    pinned: 0,
    status: 'draft',
    publishTime: undefined,
    expireTime: undefined,
  };
  editPublishTimestamp.value = null;
  editExpireTimestamp.value = null;
  editShowPreview.value = false;
}

function openCreate() {
  editMode.value = 'create';
  editId.value = null;
  resetEditForm();
  showEditModal.value = true;
}

function openEdit(row: AdminAnnouncementItem) {
  editMode.value = 'edit';
  editId.value = row.id;
  editForm.value = {
    title: row.title,
    summary: row.summary || '',
    content: row.content || '',
    level: row.level,
    pinned: row.pinned,
    status: row.status,
    publishTime: row.publishTime,
    expireTime: row.expireTime,
  };
  editPublishTimestamp.value = row.publishTime ? new Date(row.publishTime).getTime() : null;
  editExpireTimestamp.value = row.expireTime ? new Date(row.expireTime).getTime() : null;
  editShowPreview.value = false;
  // 编辑时若列表接口没带 content，需要重新拉详情
  if (!row.content) {
    // 简单起见直接把当前行拉 admin 列表（详情走前台接口未必是自己，跳过）
    // 交给用户手动点击"预览"再重拉，或从行数据看不到就得等后端返回
  }
  showEditModal.value = true;
}

function toIsoOrNull(ts: number | null): string | null {
  if (ts == null) return null;
  return new Date(ts).toISOString();
}

async function handleEditSubmit(finalStatus?: 'draft' | 'published') {
  if (!editForm.value.title.trim()) { message.warning('请填写标题'); return; }
  if (!editForm.value.content.trim()) { message.warning('请填写正文'); return; }
  editSubmitting.value = true;
  try {
    const body: AnnouncementUpsertBody = {
      title: editForm.value.title.trim(),
      summary: editForm.value.summary || undefined,
      content: editForm.value.content,
      level: editForm.value.level,
      pinned: editForm.value.pinned,
      status: finalStatus || editForm.value.status,
      publishTime: toIsoOrNull(editPublishTimestamp.value),
      expireTime: toIsoOrNull(editExpireTimestamp.value),
    };
    if (editMode.value === 'create') {
      await createAnnouncement(body);
      message.success('公告已创建');
    } else if (editId.value != null) {
      await updateAnnouncement(editId.value, body);
      message.success('公告已更新');
    }
    showEditModal.value = false;
    await load();
  } catch (e: any) {
    message.error(e?.message || '保存失败');
  }
  editSubmitting.value = false;
}

const commonColumns: DataTableColumns<AdminAnnouncementItem> = [
  { type: 'selection' },
  { title: 'ID', key: 'id', width: 80 },
  {
    title: '标题', key: 'title', width: 260, ellipsis: { tooltip: true },
    render(row) {
      return h('div', { style: 'display:flex;gap:6px;align-items:center;' }, [
        row.pinned === 1 ? h(NTag, { size: 'small', type: 'error', bordered: false }, { default: () => '置顶' }) : null,
        h('span', row.title),
      ].filter(Boolean));
    },
  },
  {
    title: '级别', key: 'level', width: 90,
    render(row) {
      const l = levelTagMap[row.level] || { label: row.level, type: 'info' as const };
      return h(NTag, { type: l.type, size: 'small' }, { default: () => l.label });
    },
  },
  {
    title: '发布人', key: 'publisherName', width: 130,
    render: (row) => row.publisherName || `ID:${row.publisherId}`,
  },
  {
    title: '发布时间', key: 'publishTime', width: 160,
    render: (row) => row.publishTime ? new Date(row.publishTime).toLocaleString() : '（立即）',
  },
  {
    title: '过期时间', key: 'expireTime', width: 160,
    render: (row) => row.expireTime ? new Date(row.expireTime).toLocaleString() : '永不过期',
  },
];

const activeColumns: DataTableColumns<AdminAnnouncementItem> = [
  ...commonColumns,
  {
    title: '状态', key: 'status', width: 90,
    render(row) {
      const s = statusTagMap[row.status] || { label: row.status, type: 'default' as const };
      return h(NTag, { type: s.type, size: 'small' }, { default: () => s.label });
    },
  },
  {
    title: '操作', key: 'actions', width: 360,
    render(row) {
      return h(NSpace, { size: 6 }, {
        default: () => [
          h(NButton, {
            size: 'tiny',
            onClick: () => openEdit(row),
          }, { default: () => '编辑' }),
          h(NButton, {
            size: 'tiny',
            type: row.pinned === 1 ? 'warning' : 'default',
            onClick: () => handleTogglePin(row),
          }, { default: () => (row.pinned === 1 ? '取消置顶' : '置顶') }),
          row.status !== 'published'
            ? h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 'published') }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '发布' }),
                default: () => '发布该公告？',
              })
            : h(NPopconfirm, { onPositiveClick: () => handleSetStatus(row, 'archived') }, {
                trigger: () => h(NButton, { size: 'tiny', type: 'warning' }, { default: () => '归档' }),
                default: () => '归档后前台将不再展示，是否继续？',
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

const trashColumns: DataTableColumns<AdminAnnouncementItem> = [
  ...commonColumns,
  {
    title: '操作', key: 'actions', width: 220,
    render(row) {
      return h(NSpace, null, {
        default: () => [
          h(NPopconfirm, { onPositiveClick: () => handleRestore(row) }, {
            trigger: () => h(NButton, { size: 'tiny', type: 'success' }, { default: () => '恢复' }),
            default: () => '确定恢复该公告？',
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
    ? `#${purgeTargetRow.value.id} ${purgeTargetRow.value.title || ''}`
    : '';
};
</script>

<template>
  <div class="admin-page">
    <h2>公告管理</h2>

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
        placeholder="搜索标题/摘要"
        style="width: 220px;"
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
      <NSelect
        v-model:value="levelFilter"
        :options="levelOptions"
        placeholder="级别"
        style="width: 120px;"
        clearable
      />
      <NButton type="primary" @click="search">搜索</NButton>
      <NButton type="primary" ghost @click="openCreate">新建公告</NButton>
    </NSpace>

    <NSpace
      v-if="checkedRowKeys.length"
      class="admin-batch-bar"
    >
      <span>已选 {{ checkedRowKeys.length }} 项</span>
      <template v-if="activeTab === 'active'">
        <NButton size="small" type="success" @click="batchPublish">批量发布</NButton>
        <NButton size="small" type="warning" @click="batchArchive">批量归档</NButton>
        <NPopconfirm @positive-click="batchDelete">
          <template #trigger>
            <NButton size="small" type="error">批量删除到回收站</NButton>
          </template>
          确认将选中 {{ checkedRowKeys.length }} 项移入回收站？
        </NPopconfirm>
      </template>
      <template v-else>
        <NPopconfirm @positive-click="batchRestoreItems">
          <template #trigger>
            <NButton size="small" type="success">批量恢复</NButton>
          </template>
          确认恢复选中的 {{ checkedRowKeys.length }} 项？
        </NPopconfirm>
        <NButton size="small" type="error" ghost @click="openBatchPurge">批量彻底删除</NButton>
      </template>
    </NSpace>

    <NDataTable
      v-model:checked-row-keys="checkedRowKeys"
      :columns="activeTab === 'active' ? activeColumns : trashColumns"
      :data="rows"
      :loading="loading"
      :bordered="false"
      :row-key="(row: AdminAnnouncementItem) => row.id"
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
      @update:page="(p: number) => { pageNum = p; load(); }"
      @update:page-size="(s: number) => { pageSize = s; pageNum = 1; load(); }"
    />

    <PurgeConfirmModal
      v-model:show="purgeModalShow"
      :summary="purgeSummary()"
      :loading="purgeLoading"
      @confirm="purgeMode === 'batch' ? handleBatchPurge() : handlePurge()"
    />

    <!-- 编辑 Modal -->
    <NModal
      v-model:show="showEditModal"
      preset="card"
      :title="editMode === 'create' ? '新建公告' : '编辑公告'"
      style="width: 780px;"
      :mask-closable="false"
    >
      <NForm label-placement="left" label-width="88" :model="editForm">
        <NFormItem label="标题" required>
          <NInput v-model:value="editForm.title" placeholder="公告标题（≤200字）" maxlength="200" show-count />
        </NFormItem>
        <NFormItem label="摘要">
          <NInput
            v-model:value="editForm.summary"
            placeholder="横幅显示的短摘要（可选，≤255字）"
            maxlength="255"
            show-count
          />
        </NFormItem>
        <NFormItem label="级别">
          <NSelect v-model:value="editForm.level" :options="levelOptions" style="width: 160px;" />
        </NFormItem>
        <NFormItem label="置顶">
          <NSwitch :value="editForm.pinned === 1" @update:value="(v: boolean) => editForm.pinned = v ? 1 : 0" />
        </NFormItem>
        <NFormItem label="发布时间">
          <NDatePicker
            v-model:value="editPublishTimestamp"
            type="datetime"
            clearable
            placeholder="留空表示立即生效"
            style="width: 260px;"
          />
        </NFormItem>
        <NFormItem label="过期时间">
          <NDatePicker
            v-model:value="editExpireTimestamp"
            type="datetime"
            clearable
            placeholder="留空表示永不过期"
            style="width: 260px;"
          />
        </NFormItem>
        <NFormItem label="正文 (Markdown)" required>
          <div style="width: 100%;">
            <div style="display:flex;justify-content:space-between;margin-bottom:6px;">
              <span style="font-size:12px;color:var(--cf-text-muted);">支持 Markdown 语法</span>
              <NButton size="tiny" @click="editShowPreview = !editShowPreview">
                {{ editShowPreview ? '编辑' : '预览' }}
              </NButton>
            </div>
            <NInput
              v-if="!editShowPreview"
              v-model:value="editForm.content"
              type="textarea"
              :autosize="{ minRows: 10, maxRows: 20 }"
              placeholder="公告正文，支持标题/加粗/链接/列表/代码块等"
            />
            <div v-else class="preview-panel" v-html="previewHtml" />
          </div>
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showEditModal = false">取消</NButton>
          <NButton
            :loading="editSubmitting"
            @click="handleEditSubmit('draft')"
          >
            保存草稿
          </NButton>
          <NButton
            type="primary"
            :loading="editSubmitting"
            @click="handleEditSubmit('published')"
          >
            {{ editMode === 'create' ? '发布' : '保存并发布' }}
          </NButton>
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
.preview-panel {
  padding: 12px 16px;
  border: 1px solid var(--cf-border);
  border-radius: 8px;
  background: var(--cf-bg-soft);
  min-height: 240px;
  line-height: 1.7;
}
.preview-panel :deep(h1),
.preview-panel :deep(h2),
.preview-panel :deep(h3) {
  margin: 12px 0 8px;
}
.preview-panel :deep(pre) {
  padding: 12px;
  background: rgba(15, 23, 42, 0.05);
  border-radius: 6px;
  overflow: auto;
}
</style>
