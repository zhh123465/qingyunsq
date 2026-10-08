<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { NButton, NDatePicker, NIcon, NInput, NModal, useMessage } from 'naive-ui';
import {
  AddOutline,
  BarChartOutline,
  BookOutline,
  CalendarOutline,
  CheckmarkCircleOutline,
  ChevronDownOutline,
  ChevronForwardOutline,
  FlameOutline,
  FootstepsOutline,
  PeopleOutline,
  SettingsOutline,
  SunnyOutline,
  TimeOutline,
} from '@vicons/ionicons5';
import { createChallenge, getChallenges } from '@/api/checkin';
import type { CheckinChallengeVO } from '@/types/checkin';

const router = useRouter();
const message = useMessage();

const challenges = ref<CheckinChallengeVO[]>([]);
const loading = ref(false);
const activeNavIndex = ref(0);
const rankExpanded = ref(false);
const calendarCursor = ref(new Date());
const createVisible = ref(false);
const createName = ref('');
const createDescription = ref('');
const createRange = ref<[number, number] | null>(null);
const createSubmitting = ref(false);
const dataDetailVisible = ref(false);

const navItems = [
  ['今日打卡', CalendarOutline],
  ['我的打卡', TimeOutline],
  ['打卡日历', CalendarOutline],
  ['打卡数据', BarChartOutline],
] as const;

const HABIT_ICONS = [SunnyOutline, BookOutline, FlameOutline, FootstepsOutline, BookOutline];
const HABIT_COLORS = ['sun', 'blue', 'green', 'runner', 'orange'] as const;

const visibleChallenges = computed(() => challenges.value.slice(0, 5));
const totalCompleted = computed(() => visibleChallenges.value.filter((item) => (item.myTotalDays || 0) > 0).length);
const completionRate = computed(() => Math.round((totalCompleted.value / Math.max(visibleChallenges.value.length, 1)) * 100));
const totalDays = computed(() => visibleChallenges.value.reduce((sum, item) => sum + (item.myTotalDays || 0), 0));

const weekdayLabels = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
const todayInfo = computed(() => {
  const date = new Date();
  return {
    year: date.getFullYear(),
    month: date.getMonth(),
    day: date.getDate(),
    weekday: weekdayLabels[date.getDay()],
  };
});
const heroDateLabel = computed(() => `${todayInfo.value.year} 年 ${todayInfo.value.month + 1} 月\n${todayInfo.value.day} 日 · ${todayInfo.value.weekday}`);
const calendarMonthLabel = computed(() => `${calendarCursor.value.getFullYear()}.${String(calendarCursor.value.getMonth() + 1).padStart(2, '0')}`);
const calendarDays = computed(() => {
  const year = calendarCursor.value.getFullYear();
  const month = calendarCursor.value.getMonth();
  const today = todayInfo.value;
  const firstDay = new Date(year, month, 1).getDay();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const prevMonthDays = new Date(year, month, 0).getDate();
  const totalCells = Math.ceil((firstDay + daysInMonth) / 7) * 7;

  return Array.from({ length: totalCells }, (_, index) => {
    const currentDay = index - firstDay + 1;
    if (currentDay <= 0) {
      return { label: prevMonthDays + currentDay, current: false, today: false };
    }
    if (currentDay > daysInMonth) {
      return { label: currentDay - daysInMonth, current: false, today: false };
    }
    return {
      label: currentDay,
      current: true,
      today: year === today.year && month === today.month && currentDay === today.day,
    };
  });
});

async function load() {
  loading.value = true;
  try {
    challenges.value = await getChallenges({ limit: 30 });
  } catch {
    challenges.value = [];
  } finally {
    loading.value = false;
  }
}

function iconFor(index: number) {
  return HABIT_ICONS[index % HABIT_ICONS.length] || CheckmarkCircleOutline;
}

function habitColor(index: number) {
  return HABIT_COLORS[index % HABIT_COLORS.length] || 'green';
}

function goDetail(id: number) {
  router.push(`/checkin/${id}`);
}

function goCreate() {
  const today = new Date();
  const end = new Date(today);
  end.setDate(today.getDate() + 30);
  createName.value = '';
  createDescription.value = '';
  createRange.value = [today.getTime(), end.getTime()];
  createVisible.value = true;
}

function handleNav(index: number) {
  activeNavIndex.value = index;
}

function shiftCalendar(delta: number) {
  const next = new Date(calendarCursor.value);
  next.setMonth(next.getMonth() + delta);
  calendarCursor.value = next;
}

function formatDate(ts: number) {
  const date = new Date(ts);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function closeCreateModal() {
  if (!createSubmitting.value) createVisible.value = false;
}

async function submitCreate() {
  if (!createName.value.trim() || !createRange.value) {
    message.warning('请填写打卡名称和日期范围');
    return;
  }
  const [start, end] = createRange.value;
  if (end < start) {
    message.warning('结束日期不能早于开始日期');
    return;
  }
  createSubmitting.value = true;
  try {
    const challenge = await createChallenge({
      name: createName.value.trim(),
      description: createDescription.value.trim() || undefined,
      startDate: formatDate(start),
      endDate: formatDate(end),
    });
    challenges.value = [challenge, ...challenges.value.filter((item) => item.id !== challenge.id)];
    createVisible.value = false;
    message.success('创建成功');
  } catch {
    message.error('创建失败');
  } finally {
    createSubmitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="checkin-page">
    <aside class="checkin-left">
      <section class="apple-card nav-card">
        <button
          v-for="(item, index) in navItems"
          :key="item[0]"
          class="left-link"
          :class="{ active: index === activeNavIndex }"
          @click="handleNav(index)"
        >
          <n-icon size="18"><component :is="item[1]" /></n-icon>
          {{ item[0] }}
        </button>
      </section>

      <section class="apple-card habits-panel">
        <div class="panel-head">
          <h3>我的习惯</h3>
        </div>
        <div v-if="visibleChallenges.length === 0" class="empty-habits">
          <p>还没有打卡习惯</p>
          <button class="add-habit" @click="goCreate">
            <n-icon size="17"><AddOutline /></n-icon>
            添加习惯
          </button>
        </div>
        <div v-for="(challenge, index) in visibleChallenges" :key="challenge.id" class="habit-row" @click="goDetail(challenge.id)">
          <span class="habit-icon" :class="habitColor(index)">
            <n-icon size="21"><component :is="iconFor(index)" /></n-icon>
          </span>
          <div style="cursor:pointer;flex:1;">
            <strong>{{ challenge.name }}</strong>
            <p>已坚持 {{ challenge.myConsecutiveDays || challenge.myTotalDays || 0 }} 天</p>
          </div>
        </div>
        <button v-if="visibleChallenges.length > 0" class="add-habit" @click="goCreate">
          <n-icon size="17"><AddOutline /></n-icon>
          添加习惯
        </button>
      </section>
    </aside>

    <main class="checkin-main">
      <section class="hero-card">
        <div>
          <h1>今日打卡</h1>
          <p>每天进步一点点，未来遇见更好的自己</p>
          <div class="hero-stats">
            <div><strong>{{ visibleChallenges.length }}</strong><span>今日目标</span></div>
            <div><strong>{{ totalCompleted }}</strong><span>已完成</span></div>
            <div><strong>{{ completionRate }}%</strong><span>完成度</span></div>
            <div><strong>{{ totalDays }}</strong><span>累计打卡</span></div>
          </div>
        </div>
        <div class="hero-date">{{ heroDateLabel }}</div>
      </section>

      <section class="today-section">
        <div class="section-title-row">
          <h2>今日习惯打卡</h2>
          <button @click="goCreate">+ 新建</button>
        </div>
        <div v-if="loading" class="loading-state"><n-spin size="large" /></div>
        <div v-else-if="visibleChallenges.length === 0" class="loading-state">
          <p style="color:var(--cf-text-muted)">暂无打卡挑战，点击右上角新建</p>
        </div>
        <div v-else class="habit-cards">
          <article
            v-for="(challenge, index) in visibleChallenges"
            :key="challenge.id"
            class="habit-card"
            @click="goDetail(challenge.id)"
          >
            <span class="habit-big-icon" :class="habitColor(index)">
              <n-icon size="34"><component :is="iconFor(index)" /></n-icon>
            </span>
            <h3>{{ challenge.name }}</h3>
            <p>{{ challenge.description || '' }}</p>
            <div class="check-state" :class="{ pending: (challenge.myTotalDays || 0) === 0 }">
              <n-icon v-if="(challenge.myTotalDays || 0) > 0" size="28"><CheckmarkCircleOutline /></n-icon>
            </div>
            <strong class="state-text" :style="{ color: (challenge.myTotalDays || 0) > 0 ? 'var(--cf-primary)' : 'var(--cf-text-muted)' }">
              {{ (challenge.myTotalDays || 0) > 0 ? '已坚持' : '未开始' }}
            </strong>
            <small>连续 {{ challenge.myConsecutiveDays || 0 }} 天</small>
          </article>
        </div>
      </section>
    </main>

    <aside class="checkin-right">
      <section class="apple-card data-card">
        <div class="panel-head">
          <h3>我的打卡数据</h3>
          <button @click="dataDetailVisible = true">查看详情 <n-icon size="12"><ChevronForwardOutline /></n-icon></button>
        </div>
        <div class="data-numbers">
          <div><strong>{{ totalDays }}</strong><span>累计打卡天数</span></div>
          <div><strong>{{ visibleChallenges.length }}</strong><span>进行中的习惯</span></div>
          <div><strong>{{ completionRate }}%</strong><span>打卡完成率</span></div>
        </div>
      </section>

      <section class="apple-card calendar-card">
        <div class="calendar-head">
          <h3>打卡日历</h3>
          <div><button @click="shiftCalendar(-1)"><ChevronDownOutline style="transform:rotate(90deg)" /></button><strong>{{ calendarMonthLabel }}</strong><button @click="shiftCalendar(1)"><ChevronDownOutline style="transform:rotate(-90deg)" /></button></div>
        </div>
        <div class="week-row"><span>日</span><span>一</span><span>二</span><span>三</span><span>四</span><span>五</span><span>六</span></div>
        <div class="days-grid">
          <span v-for="(day, index) in calendarDays" :key="`${day.label}-${index}`" :class="{ muted: !day.current, active: day.today }">
            {{ day.label }}
          </span>
        </div>
      </section>

      <section class="quote-card">
        <span>"</span>
        <p>自律给我自由，<br />坚持成就更好的自己。</p>
        <strong>— 青云阁</strong>
      </section>
    </aside>

    <NModal
      v-model:show="createVisible"
      preset="card"
      class="create-challenge-modal"
      title="创建打卡挑战"
      :bordered="false"
      :mask-closable="!createSubmitting"
      @close="closeCreateModal"
    >
      <div class="create-form">
        <label>挑战名称</label>
        <NInput v-model:value="createName" placeholder="例如：每日背单词" maxlength="64" show-count />
        <label>日期范围</label>
        <NDatePicker
          v-model:value="createRange"
          type="daterange"
          clearable
          :is-date-disabled="(ts: number) => ts < Date.now() - 86400000"
        />
        <label>简介</label>
        <NInput
          v-model:value="createDescription"
          type="textarea"
          placeholder="简单描述一下挑战目标或规则"
          maxlength="500"
          show-count
          :autosize="{ minRows: 4, maxRows: 6 }"
        />
        <div class="create-actions">
          <NButton :disabled="createSubmitting" @click="closeCreateModal">取消</NButton>
          <NButton type="primary" :loading="createSubmitting" @click="submitCreate">创建打卡</NButton>
        </div>
      </div>
    </NModal>

    <NModal
      v-model:show="dataDetailVisible"
      preset="card"
      title="我的打卡数据"
      class="checkin-data-modal"
      style="width: min(520px, calc(100vw - 32px));"
    >
      <div class="data-detail-list">
        <article>
          <strong>{{ totalDays }}</strong>
          <span>累计打卡天数</span>
        </article>
        <article>
          <strong>{{ completionRate }}%</strong>
          <span>打卡完成率</span>
        </article>
        <article>
          <strong>{{ visibleChallenges.length }}</strong>
          <span>进行中的习惯</span>
        </article>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
/* ===== Layout ===== */
.checkin-page {
  min-height: calc(100vh - 112px);
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr) 360px;
  gap: 28px;
  padding: 8px 0 40px;
  color: var(--cf-text-primary);
  background: var(--cf-page-bg);
}

.checkin-left,
.checkin-right {
  position: sticky;
  top: 8px;
  align-self: start;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.checkin-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/* ===== Cards ===== */
.apple-card,
.hero-card,
.habit-card,
.quote-card {
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 16px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.03);
  transition: all 0.2s;
}

.nav-card,
.habits-panel,
.data-card,
.calendar-card {
  padding: 18px;
}

/* ===== Left nav ===== */
.left-link {
  width: 100%;
  padding: 9px 12px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  margin-bottom: 2px;
  transition: all 0.15s;
}

.left-link:hover,
.left-link.active {
  background: rgba(52, 208, 188, 0.06);
  color: rgb(52, 208, 188);
}

/* ===== Panel heads ===== */
.panel-head,
.section-title-row,
.calendar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.panel-head h3,
.section-title-row h2,
.calendar-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.7);
}

.section-title-row h2 {
  font-size: 16px;
  color: rgba(0, 0, 0, 0.8);
}

.panel-head button,
.section-title-row button {
  padding: 0;
  border: none;
  background: transparent;
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  transition: color 0.15s;
}

.panel-head button:hover,
.section-title-row button:hover {
  color: rgb(52, 208, 188);
}

.section-title-row button {
  padding: 6px 16px;
  border-radius: 999px;
  background: rgba(52, 208, 188, 0.1);
  color: rgb(52, 208, 188);
  font-weight: 600;
}

.section-title-row button:hover {
  background: rgba(52, 208, 188, 0.18);
}

/* ===== Left habits panel ===== */
.habit-row {
  min-height: 52px;
  padding: 8px 6px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.habit-row:hover {
  background: rgba(52, 208, 188, 0.04);
}

.habit-row strong {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.8);
}

.habit-row p {
  margin: 3px 0 0;
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
}

.habit-icon,
.habit-big-icon {
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  flex-shrink: 0;
}

.habit-icon { width: 34px; height: 34px; }

/* Habit color palette (unified around primary + accent tones) */
.sun     { background: linear-gradient(135deg, #ffc86a, #ffa838); }
.blue    { background: linear-gradient(135deg, #7ec9e0, #4fa8c5); }
.green   { background: linear-gradient(135deg, rgb(74, 220, 194), rgb(38, 178, 155)); }
.runner  { background: linear-gradient(135deg, #a4b8ff, #7089ea); }
.orange  { background: linear-gradient(135deg, #ffa085, #ff7d5c); }

.add-habit {
  width: 100%;
  padding: 9px 12px;
  margin-top: 8px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 10px;
  background: transparent;
  color: rgba(0, 0, 0, 0.55);
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.15s;
}

.add-habit:hover {
  background: rgba(52, 208, 188, 0.06);
  color: rgb(52, 208, 188);
  border-color: rgba(52, 208, 188, 0.3);
}

.empty-habits {
  text-align: center;
  padding: 12px 0 4px;
  color: rgba(0, 0, 0, 0.4);
  font-size: 13px;
}

.empty-habits p {
  margin: 0 0 10px;
}

/* ===== Hero card ===== */
.hero-card {
  position: relative;
  overflow: hidden;
  padding: 28px 32px;
  background: linear-gradient(120deg, rgba(52, 208, 188, 0.08) 0%, rgba(255, 255, 255, 0.9) 60%);
}

.hero-card h1 {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}

.hero-card > div > p {
  margin: 0;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.5);
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 26px;
  padding-top: 20px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.hero-stats div {
  text-align: center;
}

.hero-stats strong {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: rgb(52, 208, 188);
}

.hero-stats span {
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
}

.hero-date {
  position: absolute;
  right: 24px;
  top: 24px;
  padding: 5px 12px;
  border-radius: 999px;
  background: rgba(52, 208, 188, 0.1);
  color: rgb(52, 208, 188);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
  white-space: pre-line;
  text-align: right;
}

/* ===== Today section ===== */
.today-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.habit-cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(128px, 1fr));
  gap: 14px;
}

.habit-card {
  min-height: 196px;
  padding: 20px 14px;
  text-align: center;
  cursor: pointer;
}

.habit-card:hover {
  transform: translateY(-2px);
  border-color: rgba(52, 208, 188, 0.2);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.06);
}

.habit-big-icon {
  width: 42px;
  height: 42px;
  margin: 0 auto 12px;
}

.habit-card h3 {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.85);
}

.habit-card p,
.habit-card small {
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
}

.habit-card p {
  margin: 0;
  min-height: 18px;
}

.check-state {
  width: 40px;
  height: 40px;
  margin: 18px auto 6px;
  border-radius: 50%;
  color: #fff;
  background: rgb(52, 208, 188);
  display: grid;
  place-items: center;
  box-shadow: 0 4px 12px rgba(0, 216, 191, 0.18);
}

.check-state.pending {
  background: transparent;
  border: 2px solid rgba(0, 0, 0, 0.08);
  box-shadow: none;
}

.state-text {
  display: block;
  color: rgb(52, 208, 188);
  font-size: 12px;
  font-weight: 600;
  margin: 6px 0 2px;
}

/* ===== Data card (right) ===== */
.data-numbers {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 4px 0 0;
  text-align: center;
}

.data-numbers strong {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: rgb(52, 208, 188);
}

.data-numbers span {
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
}

/* ===== Calendar card ===== */
.calendar-head div {
  display: flex;
  align-items: center;
  gap: 12px;
}

.calendar-head strong {
  font-size: 13px;
  font-weight: 600;
  color: rgba(0, 0, 0, 0.7);
}

.calendar-head button {
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(0, 0, 0, 0.4);
  font-size: 14px;
  cursor: pointer;
  display: inline-grid;
  place-items: center;
  transition: all 0.15s;
}

.calendar-head button:hover {
  background: rgba(52, 208, 188, 0.08);
  color: rgb(52, 208, 188);
}

.week-row,
.days-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  text-align: center;
}

.week-row {
  margin: 14px 0 8px;
  color: rgba(0, 0, 0, 0.4);
  font-size: 12px;
  font-weight: 500;
}

.days-grid {
  gap: 2px;
}

.days-grid span {
  min-height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: rgba(0, 0, 0, 0.65);
  font-size: 13px;
  font-weight: 500;
}

.days-grid span.muted {
  color: rgba(0, 0, 0, 0.2);
}

.days-grid span.active {
  width: 30px;
  height: 30px;
  margin: 0 auto;
  color: #fff;
  background: rgb(52, 208, 188);
  font-weight: 600;
}

/* ===== Quote card ===== */
.quote-card {
  padding: 24px;
  background: linear-gradient(145deg, rgba(52, 208, 188, 0.06), rgba(255, 255, 255, 0.9));
}

.quote-card span {
  color: rgb(52, 208, 188);
  font-size: 36px;
  line-height: 0.6;
  font-weight: 700;
}

.quote-card p {
  margin: 6px 0 14px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.7;
  color: rgba(0, 0, 0, 0.7);
}

.quote-card strong {
  display: block;
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  font-weight: 500;
  text-align: right;
}

/* ===== States & modal ===== */
.loading-state {
  padding: 40px;
  text-align: center;
  color: rgba(0, 0, 0, 0.35);
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.create-form label {
  color: rgba(0, 0, 0, 0.6);
  font-size: 13px;
  font-weight: 600;
}

.data-detail-list {
  display: grid;
  gap: 10px;
}

.data-detail-list article {
  padding: 16px;
  border: 1px solid rgba(0, 0, 0, 0.05);
  border-radius: 14px;
  background: rgba(0, 0, 0, 0.02);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.data-detail-list strong {
  color: rgb(52, 208, 188);
  font-size: 22px;
  font-weight: 700;
}

.data-detail-list span {
  color: rgba(0, 0, 0, 0.65);
  font-weight: 600;
  font-size: 13px;
}

.create-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}

:global(.create-challenge-modal.n-card),
:global(.checkin-data-modal.n-card) {
  width: min(520px, calc(100vw - 32px));
  border-radius: 18px;
}

/* ===== Responsive ===== */
@media (max-width: 1320px) {
  .checkin-page {
    grid-template-columns: 230px minmax(0, 1fr) 320px;
    gap: 20px;
  }
  .habit-cards {
    grid-template-columns: repeat(3, minmax(150px, 1fr));
  }
}

@media (max-width: 1080px) {
  .checkin-page {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .checkin-right {
    display: none;
  }
}

@media (max-width: 760px) {
  .checkin-page {
    display: flex;
    flex-direction: column;
  }
  .checkin-left,
  .checkin-right {
    position: static;
  }
  .habits-panel {
    display: none;
  }
  .hero-stats,
  .habit-cards {
    grid-template-columns: 1fr 1fr;
  }
  .hero-date {
    display: none;
  }
}

/* ===== Dark mode ===== */
html[data-theme='dark'] .apple-card,
html[data-theme='dark'] .hero-card,
html[data-theme='dark'] .habit-card,
html[data-theme='dark'] .quote-card {
  background: var(--cf-bg-card, #0c0c0d);
  border-color: var(--cf-card-border, rgba(255, 255, 255, 0.07));
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
}
html[data-theme='dark'] .hero-card {
  background: linear-gradient(120deg, rgba(52, 208, 188, 0.12) 0%, rgba(15, 23, 42, 0.92) 60%);
}
html[data-theme='dark'] .quote-card {
  background: linear-gradient(145deg, rgba(52, 208, 188, 0.1), rgba(15, 23, 42, 0.92));
}
html[data-theme='dark'] .habit-card:hover {
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
  border-color: rgba(52, 208, 188, 0.3);
}
html[data-theme='dark'] .hero-card h1,
html[data-theme='dark'] .section-title-row h2,
html[data-theme='dark'] .habit-row strong,
html[data-theme='dark'] .habit-card h3,
html[data-theme='dark'] .quote-card p {
  color: var(--cf-text-primary, #f8fafc);
}
html[data-theme='dark'] .hero-card > div > p,
html[data-theme='dark'] .habit-row p,
html[data-theme='dark'] .habit-card p,
html[data-theme='dark'] .habit-card small,
html[data-theme='dark'] .hero-stats span,
html[data-theme='dark'] .data-numbers span,
html[data-theme='dark'] .week-row,
html[data-theme='dark'] .empty-habits,
html[data-theme='dark'] .loading-state {
  color: rgba(248, 250, 252, 0.5);
}
html[data-theme='dark'] .left-link {
  color: var(--cf-text-secondary, rgba(248, 250, 252, 0.68));
}
html[data-theme='dark'] .panel-head h3,
html[data-theme='dark'] .calendar-head h3,
html[data-theme='dark'] .calendar-head strong,
html[data-theme='dark'] .create-form label,
html[data-theme='dark'] .data-detail-list span {
  color: var(--cf-text-secondary, rgba(248, 250, 252, 0.76));
}
html[data-theme='dark'] .days-grid span {
  color: rgba(248, 250, 252, 0.68);
}
html[data-theme='dark'] .days-grid span.muted {
  color: rgba(248, 250, 252, 0.22);
}
html[data-theme='dark'] .hero-stats {
  border-top-color: rgba(255, 255, 255, 0.06);
}
html[data-theme='dark'] .check-state.pending {
  border-color: rgba(255, 255, 255, 0.1);
}
html[data-theme='dark'] .add-habit {
  border-color: rgba(255, 255, 255, 0.08);
  color: rgba(248, 250, 252, 0.65);
}
html[data-theme='dark'] .quote-card strong {
  color: rgba(248, 250, 252, 0.55);
}
html[data-theme='dark'] .data-detail-list article {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.06);
}
</style>
