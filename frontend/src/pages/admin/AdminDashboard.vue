<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NotificationsOutline } from '@vicons/ionicons5';
import { getDashboard } from '@/api/admin';
import type { DashboardVO, DailyStat, CategoryStat, AuditLogVO } from '@/types/admin';
import { useAuthStore } from '@/stores/auth';

const dashboard = ref<DashboardVO | null>(null);
const loading = ref(true);
const errMsg = ref('');

const auth = useAuthStore();
const currentRole = computed(() => auth.user?.role || localStorage.getItem('role') || '');

onMounted(async () => {
  try {
    dashboard.value = await getDashboard();
  } catch (e: any) {
    errMsg.value = e?.message || '加载失败';
  } finally {
    loading.value = false;
  }
});

const stats = computed(() => {
  const d = dashboard.value;
  if (!d) return [] as { label: string; value: string }[];
  return [
    { label: '总用户', value: String(d.userCount ?? 0) },
    { label: '总帖子', value: String(d.postCount ?? 0) },
    { label: '总空间', value: String(d.spaceCount ?? 0) },
    { label: '总评论', value: String(d.commentCount ?? 0) },
    { label: '今日新增帖子', value: String(d.todayPostCount ?? 0) },
    { label: '今日新增用户', value: String(d.todayUserCount ?? 0) },
  ];
});

const weeklyTrend = computed<DailyStat[]>(() => dashboard.value?.weeklyTrend ?? []);

const spaceCategoryDist = computed<CategoryStat[]>(
  () => dashboard.value?.spaceCategoryDist ?? [],
);

const recentLogs = computed<AuditLogVO[]>(
  () => dashboard.value?.recentAuditLogs ?? [],
);

/**
 * 计算折线图的 SVG path。三条线共用 viewBox `0 0 100 40`。
 * 用最大值做 y 归一化，避免小数量级也铺满高度。
 */
function pathFor(field: 'newPosts' | 'newUsers' | 'newComments'): string {
  const days = weeklyTrend.value;
  if (!days.length) return '';
  const maxAll = Math.max(
    1,
    ...days.map((d) => Math.max(d.newPosts, d.newUsers, d.newComments)),
  );
  const step = 100 / Math.max(1, days.length - 1);
  return days
    .map((d, i) => {
      const x = i * step;
      const y = 40 - (d[field] / maxAll) * 36 - 2;
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');
}

const xLabels = computed(() =>
  weeklyTrend.value.map((d) => d.date.substring(5)), // "MM-DD"
);

/** 空间分类饼图 conic-gradient 字符串。 */
const spacePieGradient = computed(() => {
  const total = spaceCategoryDist.value.reduce((s, c) => s + c.count, 0);
  if (!total) return 'conic-gradient(#94a3b8 0 100%)';
  const palette = ['#3b82f6', '#10b981', '#8b5cf6', '#f59e0b', '#ef4444', '#6b7280'];
  let acc = 0;
  const parts = spaceCategoryDist.value.map((c, i) => {
    const from = (acc / total) * 100;
    acc += c.count;
    const to = (acc / total) * 100;
    return `${palette[i % palette.length]} ${from.toFixed(2)}% ${to.toFixed(2)}%`;
  });
  return `conic-gradient(${parts.join(', ')})`;
});

const spaceTotal = computed(() =>
  spaceCategoryDist.value.reduce((s, c) => s + c.count, 0),
);

const categoryLabels: Record<string, string> = {
  MAJOR: '专业',
  CLASS: '班级',
  CLUB: '社团',
  INTEREST: '兴趣',
};

function categoryLabel(c: string): string {
  return categoryLabels[c] || c;
}

function categoryColor(idx: number): string {
  const palette = ['#3b82f6', '#10b981', '#8b5cf6', '#f59e0b', '#ef4444', '#6b7280'];
  return palette[idx % palette.length];
}

function categoryPercent(count: number): string {
  const total = spaceTotal.value;
  if (!total) return '0%';
  return ((count / total) * 100).toFixed(1) + '%';
}

function formatTime(iso: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const actionLabels: Record<string, string> = {
  USER_BAN: '封禁用户',
  USER_UNBAN: '解禁用户',
  USER_ROLE_CHANGE: '修改角色',
  USER_BATCH_STATUS: '批量用户状态',
  POST_PIN: '帖子置顶',
  POST_ESSENCE: '帖子精华',
  POST_STATUS: '帖子状态',
  POST_FORCE_DELETE: '帖子删除',
  POST_RESTORE: '帖子恢复',
  POST_PURGE: '帖子彻底删除',
  SPACE_STATUS: '空间状态',
  SPACE_DISMISS: '空间解散',
  SPACE_RESTORE: '空间恢复',
  SPACE_PURGE: '空间彻底删除',
  RESOURCE_STATUS: '资源状态',
  RESOURCE_FORCE_DELETE: '资源删除',
  RESOURCE_RESTORE: '资源恢复',
  RESOURCE_PURGE: '资源彻底删除',
  NOTE_STATUS: '笔记状态',
  NOTE_FORCE_DELETE: '笔记删除',
  NOTE_RESTORE: '笔记恢复',
  NOTE_PURGE: '笔记彻底删除',
  CHECKIN_STATUS: '打卡状态',
  CHECKIN_FORCE_DELETE: '打卡删除',
  CHECKIN_RESTORE: '打卡恢复',
  CHECKIN_PURGE: '打卡彻底删除',
  COMMENT_STATUS: '评论状态',
  COMMENT_FORCE_DELETE: '评论删除',
  COMMENT_RESTORE: '评论恢复',
  COMMENT_PURGE: '评论彻底删除',
  REPORT_HANDLE: '举报处理',
  REPORT_BATCH_HANDLE: '批量举报处理',
  // === 批量操作 ===
  POST_BATCH_STATUS: '帖子批量状态',
  POST_BATCH_DELETE: '帖子批量删除',
  POST_BATCH_RESTORE: '帖子批量恢复',
  POST_BATCH_PURGE: '帖子批量彻底删除',
  SPACE_BATCH_STATUS: '空间批量状态',
  SPACE_BATCH_DISMISS: '空间批量删除',
  SPACE_BATCH_RESTORE: '空间批量恢复',
  SPACE_BATCH_PURGE: '空间批量彻底删除',
  RESOURCE_BATCH_STATUS: '资源批量状态',
  RESOURCE_BATCH_DELETE: '资源批量删除',
  RESOURCE_BATCH_RESTORE: '资源批量恢复',
  RESOURCE_BATCH_PURGE: '资源批量彻底删除',
  NOTE_BATCH_STATUS: '笔记批量状态',
  NOTE_BATCH_DELETE: '笔记批量删除',
  NOTE_BATCH_RESTORE: '笔记批量恢复',
  NOTE_BATCH_PURGE: '笔记批量彻底删除',
  CHECKIN_BATCH_STATUS: '打卡批量状态',
  CHECKIN_BATCH_DELETE: '打卡批量删除',
  CHECKIN_BATCH_RESTORE: '打卡批量恢复',
  CHECKIN_BATCH_PURGE: '打卡批量彻底删除',
  COMMENT_BATCH_STATUS: '评论批量状态',
  COMMENT_BATCH_DELETE: '评论批量删除',
  COMMENT_BATCH_RESTORE: '评论批量恢复',
  COMMENT_BATCH_PURGE: '评论批量彻底删除',
  // === 公告 ===
  ANNOUNCEMENT_CREATE: '创建公告',
  ANNOUNCEMENT_UPDATE: '编辑公告',
  ANNOUNCEMENT_STATUS: '公告状态',
  ANNOUNCEMENT_PIN: '公告置顶',
  ANNOUNCEMENT_FORCE_DELETE: '公告删除',
  ANNOUNCEMENT_RESTORE: '公告恢复',
  ANNOUNCEMENT_PURGE: '公告彻底删除',
  ANNOUNCEMENT_BATCH_STATUS: '公告批量状态',
  ANNOUNCEMENT_BATCH_DELETE: '公告批量删除',
  ANNOUNCEMENT_BATCH_RESTORE: '公告批量恢复',
  ANNOUNCEMENT_BATCH_PURGE: '公告批量彻底删除',
};

function actionLabel(a: string): string {
  return actionLabels[a] || a;
}
</script>

<template>
  <div class="admin-dashboard-page">
    <header class="top-bar">
      <div class="title">工作台</div>
      <div class="actions">
        <n-icon size="20">
          <NotificationsOutline />
        </n-icon>
        <div class="admin-profile">
          <div class="avatar" />
          <span>
            {{ dashboard?.tenantCode ? `租户 ${dashboard.tenantCode}` : '管理员' }}
            <span class="role-badge">{{ currentRole }}</span>
          </span>
        </div>
      </div>
    </header>

    <div
      v-if="errMsg"
      class="err-tip"
    >
      加载失败：{{ errMsg }}
    </div>

    <div class="dashboard-grid">
      <!-- Stats Row (6 张真实 count) -->
      <div class="stats-row">
        <div
          v-for="stat in stats"
          :key="stat.label"
          class="glass-card stat-box"
        >
          <div class="label">{{ stat.label }}</div>
          <div class="value-row">
            <span class="value">{{ loading ? '—' : stat.value }}</span>
          </div>
        </div>
      </div>

      <!-- Charts Row -->
      <div class="charts-row">
        <div class="glass-card chart-card flex-2">
          <div class="card-header">
            <h3>近 7 天数据趋势</h3>
            <div class="legend">
              <span><span class="dot" style="background:#38bdf8" />新增帖子</span>
              <span><span class="dot" style="background:#c084fc" />新增用户</span>
              <span><span class="dot" style="background:#10b981" />新增评论</span>
            </div>
          </div>
          <div class="chart-area mock-line-chart">
            <svg
              viewBox="0 0 100 40"
              class="full-svg"
              preserveAspectRatio="none"
            >
              <path
                :d="pathFor('newPosts')"
                fill="none"
                stroke="#38bdf8"
                stroke-width="1"
              />
              <path
                :d="pathFor('newUsers')"
                fill="none"
                stroke="#c084fc"
                stroke-width="1"
              />
              <path
                :d="pathFor('newComments')"
                fill="none"
                stroke="#10b981"
                stroke-width="1"
              />
            </svg>
            <div class="x-axis">
              <span
                v-for="x in xLabels"
                :key="x"
              >{{ x }}</span>
            </div>
          </div>
        </div>

        <div class="glass-card chart-card flex-1">
          <div class="card-header">
            <h3>空间分类分布</h3>
          </div>
          <div class="chart-area mock-pie-chart">
            <div
              class="donut"
              :style="{ background: spacePieGradient }"
            >
              <div class="inner-circle">
                <span class="total">{{ spaceTotal }}</span>
                <span class="sub">活跃空间</span>
              </div>
            </div>
            <div class="pie-legend">
              <div
                v-for="(c, idx) in spaceCategoryDist"
                :key="c.category"
                class="l-item"
              >
                <span
                  class="dot"
                  :style="{ background: categoryColor(idx) }"
                />
                {{ categoryLabel(c.category) }}
                <span class="pct">{{ categoryPercent(c.count) }}</span>
              </div>
              <div
                v-if="!spaceCategoryDist.length && !loading"
                class="empty-hint"
              >
                暂无空间数据
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Bottom Row: 最近操作日志 -->
      <div class="bottom-row">
        <div class="glass-card info-card wide">
          <div class="card-header">
            <h3>最近操作日志</h3>
            <router-link
              to="/admin/audit-logs"
              class="more"
            >更多 ›</router-link>
          </div>
          <div class="log-list">
            <div
              v-for="log in recentLogs"
              :key="log.id"
              class="log-item"
            >
              <div class="log-icon">
                <div class="avatar-small" />
              </div>
              <div class="log-content">
                <p>
                  <span class="user">{{ log.operatorName || `ID:${log.operatorId}` }}</span>
                  {{ actionLabel(log.action) }}
                  <span
                    v-if="log.targetType"
                    class="target"
                  >{{ log.targetType }}#{{ log.targetId }}</span>
                </p>
              </div>
              <div class="log-time">{{ formatTime(log.createdAt) }}</div>
            </div>
            <div
              v-if="!recentLogs.length && !loading"
              class="empty-hint"
            >
              暂无操作记录
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.admin-dashboard-page {
  min-height: 100vh;
  background: transparent;
  color: var(--cf-text-primary);
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: auto;

  .top-bar {
    height: 60px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0 32px;
    border-bottom: 1px solid var(--cf-border);

    .title { font-size: 18px; font-weight: 600; }
    .actions {
      display: flex; align-items: center; gap: 24px; color: var(--cf-text-secondary);
      .admin-profile {
        display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--cf-text-primary);
        .avatar { width: 32px; height: 32px; border-radius: 50%; background: var(--cf-primary); }
        .role-badge {
          margin-left: 6px;
          padding: 2px 8px;
          border-radius: 10px;
          background: rgba(56, 189, 248, 0.15);
          color: #0284c7;
          font-size: 12px;
        }
      }
    }
  }

  .err-tip {
    padding: 12px 32px;
    background: rgba(239, 68, 68, 0.08);
    color: #b91c1c;
    font-size: 13px;
  }

  .dashboard-grid {
    flex: 1;
    overflow-y: auto;
    padding: 24px 32px;
    display: flex;
    flex-direction: column;
    gap: 24px;

    .stats-row {
      display: grid;
      grid-template-columns: repeat(6, 1fr);
      gap: 20px;
      @media (max-width: 1400px) { grid-template-columns: repeat(3, 1fr); }
      @media (max-width: 768px) { grid-template-columns: repeat(2, 1fr); }

      .stat-box {
        padding: 20px;
        .label { color: var(--cf-text-secondary); font-size: 13px; margin-bottom: 10px; }
        .value-row {
          display: flex; align-items: baseline; gap: 12px;
          .value { font-size: 26px; font-weight: bold; color: var(--cf-text-primary); }
        }
      }
    }

    .charts-row {
      display: flex;
      gap: 24px;
      @media (max-width: 1024px) { flex-direction: column; }

      .chart-card {
        padding: 24px;
        display: flex; flex-direction: column;

        &.flex-2 { flex: 2; }
        &.flex-1 { flex: 1; }

        .card-header {
          display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px;
          h3 { margin: 0; font-size: 16px; font-weight: 500; }
          .legend {
            display: flex; gap: 16px; font-size: 12px; color: var(--cf-text-secondary);
            span { display: flex; align-items: center; gap: 6px; .dot { width: 8px; height: 8px; border-radius: 50%; } }
          }
        }

        .chart-area {
          flex: 1;
          display: flex;
          flex-direction: column;
          min-height: 200px;

          &.mock-line-chart {
            justify-content: flex-end;
            .full-svg { width: 100%; height: 180px; overflow: visible; }
            .x-axis {
              display: flex; justify-content: space-between; margin-top: 12px;
              span { font-size: 11px; color: var(--cf-text-muted); }
            }
          }

          &.mock-pie-chart {
            flex-direction: row; align-items: center; gap: 32px;
            .donut {
              width: 140px; height: 140px; border-radius: 50%;
              display: flex; align-items: center; justify-content: center;
              flex-shrink: 0;
              .inner-circle {
                width: 100px; height: 100px; border-radius: 50%; background: var(--cf-bg-card);
                display: flex; flex-direction: column; align-items: center; justify-content: center;
                .total { font-size: 20px; font-weight: bold; color: var(--cf-text-primary); }
                .sub { font-size: 12px; color: var(--cf-text-secondary); }
              }
            }
            .pie-legend {
              flex: 1; display: flex; flex-direction: column; gap: 10px;
              .l-item {
                display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--cf-text-secondary);
                .dot { width: 10px; height: 10px; border-radius: 50%; }
                .pct { margin-left: auto; color: var(--cf-text-primary); font-weight: 500; }
              }
              .empty-hint { color: var(--cf-text-muted); font-size: 13px; }
            }
          }
        }
      }
    }

    .bottom-row {
      display: grid;
      grid-template-columns: 1fr;
      gap: 24px;

      .info-card {
        padding: 24px;
        &.wide { min-height: 240px; }
        .card-header {
          display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;
          h3 { margin: 0; font-size: 16px; font-weight: 500; }
          .more { font-size: 13px; color: var(--cf-primary); text-decoration: none; }
        }

        .log-list {
          display: flex; flex-direction: column; gap: 14px;
          .log-item {
            display: flex; gap: 16px; align-items: flex-start;
            .avatar-small { width: 32px; height: 32px; border-radius: 50%; background: #38bdf8; flex-shrink: 0; }
            .log-content {
              flex: 1;
              p { margin: 0; font-size: 14px; color: var(--cf-text-secondary); }
              .user { color: var(--cf-text-primary); font-weight: 500; margin-right: 6px; }
              .target { color: var(--cf-text-muted); margin-left: 6px; font-size: 12px; }
            }
            .log-time { font-size: 12px; color: var(--cf-text-muted); flex-shrink: 0; }
          }
          .empty-hint { color: var(--cf-text-muted); font-size: 13px; text-align: center; padding: 20px 0; }
        }
      }
    }
  }
}
</style>
