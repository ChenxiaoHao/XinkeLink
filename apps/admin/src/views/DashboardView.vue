<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, CalendarDays, ChartNoAxesCombined, CircleAlert, FileText, MessageSquareText, RefreshCw, ShieldAlert, UsersRound } from '@lucide/vue'
import TrendChart from '../components/dashboard/TrendChart.vue'
import { readDashboard, type DashboardData } from '../services/admin-dashboard'
import { errorMessage } from '../services/admin-http'
const router = useRouter()
const range = ref<'7天' | '30天' | '本学期'>('7天')
const activityFilter = ref('全部动态')
const rangeOptions = ['7天', '30天', '本学期'] as const
const data = ref<DashboardData | null>(null)
const loadError = ref('')
const loading = ref(false)
let sequence = 0
const dateLabel = computed(() => new Intl.DateTimeFormat('zh-CN', { dateStyle: 'full', timeZone: 'Asia/Shanghai' }).format(new Date()))
const greeting = computed(() => { const hour = Number(new Intl.DateTimeFormat('en', { hour: 'numeric', hourCycle: 'h23', timeZone: 'Asia/Shanghai' }).format(new Date())); return hour < 11 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好' })
const metricCards = computed(() => [
  { label: '总用户数', value: data.value?.metrics.totalUsers.toLocaleString() ?? '—', unit: '人', trend: '全局有效用户', icon: UsersRound, danger: false },
  { label: '今日新增帖子', value: data.value?.metrics.todayPosts.toLocaleString() ?? '—', unit: '篇', trend: '按北京时间统计', icon: FileText, danger: false },
  { label: '待处理举报数', value: data.value?.metrics.pendingReports.toLocaleString() ?? '—', unit: '起', trend: '查看处理队列', icon: ShieldAlert, danger: true },
])
const destinations: Record<string, { label: string; path: string; kind: string }> = { post: { label: '帖子管理', path: '/admin/posts', kind: '论坛发帖' }, report: { label: '举报审核', path: '/admin/reports', kind: '社区举报' }, announcement: { label: '公告管理', path: '/admin/announcements', kind: '平台公告' }, comment: { label: '评论管理', path: '/admin/comments', kind: '评论互动' } }
const todos = computed(() => (data.value?.todos ?? []).slice(0, 3).map(item => { const destination = destinations[item.kind] ?? destinations.report!; return { id: item.id, priority: ({ high: '高优先级', medium: '中优先级', low: '普通优先级' } as Record<string, string>)[item.priority] ?? item.priority, text: item.title, meta: `${destination.label} · #${item.id}`, action: '查看', path: destination.path } }))
const activities = computed(() => (data.value?.recentActivity ?? []).map(item => { const destination = destinations[item.kind] ?? destinations.report!; return { time: new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Shanghai', hour12: false }).format(new Date(item.createdAt)), kind: destination.kind, detail: item.summary, state: '已记录', target: destination.label, path: destination.path } }))
const visibleActivities = computed(() => activityFilter.value === '全部动态' ? activities.value : activities.value.filter(activity => activity.kind === activityFilter.value))
const filterOptions = ['全部动态', '论坛发帖', '社区举报']
const chartPoints = computed(() => data.value?.trends.map(item => item.postCount) ?? [])
const chartLabels = computed(() => data.value?.trends.map(item => item.date.slice(5)) ?? [])
const unavailableReason = (key: string) => data.value?.unavailableMetrics.find(item => item.key === key)?.reason ?? '暂未采集'
async function load() {
  const request = ++sequence; loading.value = true; loadError.value = ''
  try { const next = await readDashboard(range.value === '7天' ? '7d' : range.value === '30天' ? '30d' : 'semester'); if (request === sequence) data.value = next }
  catch (error) { if (request === sequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (request === sequence) loading.value = false }
}
onMounted(load)
watch(range, load)
function open(path: string) { void router.push(path) }
</script>

<template>
  <div class="dashboard-view" data-testid="dashboard-view" v-loading="loading">
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <section class="welcome-card" aria-label="欢迎信息">
      <div class="welcome-copy">
        <h1 class="welcome-title">数据概览</h1>
        <div class="welcome-subtitle">{{ greeting }}，查看社区动态与待处理事项。</div>
      </div>
      <div class="welcome-meta">
        <span class="meta-pill"><CalendarDays aria-hidden="true" />{{ dateLabel }}</span>
        <span class="meta-pill"><RefreshCw aria-hidden="true" />服务器统计</span>
      </div>
    </section>

    <section class="metrics-grid" :style="{ '--metric-count': metricCards.length }" aria-label="数据概览">
      <article v-for="metric in metricCards" :key="metric.label" class="metric-card">
        <div class="metric-top">
          <div><div class="metric-label">{{ metric.label }}</div><span class="metric-note">服务器数据</span></div>
          <span class="metric-icon" :class="{ 'is-danger': metric.danger }"><component :is="metric.icon" aria-hidden="true" /></span>
        </div>
        <div class="metric-value" :class="{ 'is-danger-text': metric.danger }">{{ metric.value }}<small>{{ metric.unit }}</small></div>
        <div class="metric-foot" :class="{ 'is-alert': metric.danger }"><CircleAlert v-if="metric.danger" /><ChartNoAxesCombined v-else />{{ metric.trend }}</div>
      </article>
    </section>

    <div class="dashboard-middle">
      <section class="panel-card trend-panel" aria-labelledby="trend-title">
        <header class="panel-header">
          <h2 id="trend-title" class="panel-title">每日发帖量</h2>
          <div class="trend-controls" aria-label="趋势时间范围">
            <button v-for="option in rangeOptions" :key="option" class="range-button" :class="{ 'is-active': range === option }" type="button" @click="range = option">{{ option }}</button>
          </div>
        </header>
        <div class="trend-panel-body">
          <TrendChart :range="range" :points="chartPoints" :labels="chartLabels" />
          <p class="chart-data-note" :title="unavailableReason('dau')">按北京时间统计 · 日活、环比与停留时长暂未采集</p>
        </div>
      </section>

      <section class="panel-card todo-panel" aria-labelledby="todo-title">
        <header class="panel-header">
          <h2 id="todo-title" class="panel-title">待处理事项</h2>
          <button class="text-action" type="button" @click="open('/admin/reports')">查看全部 {{ data?.metrics.pendingReports ?? '—' }} 项</button>
        </header>
        <div v-if="todos.length" class="todo-panel-body">
          <article v-for="todo in todos" :key="todo.id" class="todo-item">
            <div class="todo-copy"><div class="todo-title"><i class="todo-dot" />{{ todo.priority }}</div><div class="todo-description">{{ todo.text }}</div><div class="todo-meta">{{ todo.meta }}</div></div>
            <button class="text-action" type="button" @click="open(todo.path)">{{ todo.action }} <ArrowRight :size="12" /></button>
          </article>
        </div>
        <div v-else class="todo-empty">暂无待处理事项</div>
      </section>
    </div>

    <section class="panel-card activity-panel" aria-labelledby="activity-title">
      <header class="panel-header">
        <h2 id="activity-title" class="panel-title"><MessageSquareText aria-hidden="true" />最近平台动态 <span class="demo-badge"><span class="environment-dot" />服务器记录</span></h2>
        <div class="activity-toolbar">
          <button v-for="filter in filterOptions" :key="filter" class="filter-button" :class="{ 'is-active': activityFilter === filter }" type="button" @click="activityFilter = filter">{{ filter }}</button>
          <button class="text-action" type="button" @click="open('/admin/logs')">查看操作日志 <ArrowRight :size="12" /></button>
        </div>
      </header>
      <div class="activity-table-wrap">
        <table class="activity-table">
          <thead><tr><th>发生时间</th><th>业务分类</th><th>动态详情</th><th>记录状态</th><th>快捷入口</th></tr></thead>
          <tbody>
            <tr v-for="activity in visibleActivities" :key="activity.time + activity.detail">
              <td>{{ activity.time }}</td><td><span class="activity-tag">{{ activity.kind }}</span></td><td>{{ activity.detail }}</td><td><span class="status-text">{{ activity.state }}</span></td>
              <td><button class="text-action" type="button" @click="open(activity.path)">{{ activity.target }} <ArrowRight :size="12" /></button></td>
            </tr>
            <tr v-if="visibleActivities.length === 0"><td colspan="5" class="activity-empty">暂无对应动态</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.metric-value.is-danger-text { color: var(--xlk-accent-rose); }
.todo-count { padding: 3px 8px; color: var(--xlk-accent-rose); background: var(--xlk-accent-rose-soft); border-radius: 999px; font-size: 12px; font-weight: 600; }
.activity-empty { padding: 26px !important; color: var(--xlk-color-muted) !important; text-align: center; }
@media (max-width: 1200px) {
  .activity-panel .panel-header { align-items: flex-start; flex-direction: column; padding: 14px 18px; }
  .activity-toolbar { width: 100%; overflow-x: auto; }
}
</style>
