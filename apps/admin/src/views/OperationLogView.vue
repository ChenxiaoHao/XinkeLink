<script setup lang="ts">
import { shortReference } from '../utils/admin-display'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage } from 'element-plus'
import { Download, FileClock, RotateCcw, ShieldCheck, ShieldX, TriangleAlert, Eye } from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import { useAdminAuditStore, type AuditEvent, type AuditFilters, type AuditModule, type AuditOutcome } from '../stores/admin-audit'
import { toSafeAuditRow } from '../utils/admin-audit'

const store = useAdminAuditStore()
const page = ref(1)
const pageSize = ref(10)
const filters = reactive<AuditFilters>({ keyword: '', actor: '', module: 'all', action: '', outcome: 'all' })
const loadError = ref('')
const loading = ref(false)
const selectedEvent = ref<AuditEvent | null>(null)
const drawerVisible = ref(false)
const result = computed(() => store.query(filters, page.value, pageSize.value))
const safeRows = computed(() => result.value.items.map(event => ({ event, safe: toSafeAuditRow(event) })))
const todayCount = computed(() => store.stats.today)
const metricItems = computed(() => [
  { label: '累计操作记录', value: store.stats.total, unit: '条', note: '服务器持久化记录', icon: FileClock },
  { label: '成功操作', value: store.stats.success, unit: '条', note: `今日新增 ${todayCount.value} 条`, icon: ShieldCheck },
  { label: '业务拒绝', value: store.stats.businessRejected, unit: '条', note: '校验未通过或状态不允许', icon: TriangleAlert },
  { label: '执行异常', value: store.stats.executionErrors, unit: '条', note: '原业务异常仍按原方式处理', icon: ShieldX, danger: store.stats.executionErrors > 0 },
])
const selectedSafe = computed(() => selectedEvent.value ? toSafeAuditRow(selectedEvent.value) : null)
const outcomeLabel: Record<AuditOutcome, string> = { success: '操作成功', business_rejected: '业务拒绝', execution_error: '执行异常' }
const moduleLabel: Record<AuditModule, string> = {
  posts: '帖子管理', comments: '评论管理', reports: '举报审核',
  users: '用户管理', announcements: '公告管理', notifications: '通知管理', settings: '系统设置', lucky_bags: '福袋（历史）', ai_knowledge: 'AI 知识库',
}
const actionLabel: Record<string, string> = {
  set_status: '调整展示状态', restrictions: '调整权限', ban: '封禁用户', release: '解除限制', resolve: '处理举报', create: '新建草稿', edit: '编辑内容', reset: '恢复默认值',
  hide: '隐藏', restore: '恢复', delete: '删除',
  restrict_publish: '限制发布', restrict_comment: '限制评论', restrict_message: '限制私信',
  release_publish: '解除发布限制', release_comment: '解除评论限制', release_message: '解除私信限制',
  temporary_ban: '临时封禁', permanent_ban: '永久封禁', lift_restrictions: '解除用户限制',
  reject: '驳回举报', hide_post: '隐藏帖子', hide_comment: '隐藏评论',
  restrict_user: '限制用户', warn_user: '警告用户', hide_message: '隐藏私信内容',
  save_draft: '保存草稿', update_draft: '编辑草稿', update: '编辑内容', publish: '发布公告',
  withdraw: '撤回公告', save: '保存设置', save_rejected: '设置校验拒绝', reset_defaults: '恢复默认值', send: '站内发送',
}
const outcomeClass: Record<AuditOutcome, string> = { success: 'outcome-success', business_rejected: 'outcome-rejected', execution_error: 'outcome-error' }

watch(filters, () => { page.value = 1; load() }, { deep: true })
watch([page, pageSize], load)
onMounted(load)
let loadSequence = 0
async function load() {
  const sequence = ++loadSequence; loading.value = true; loadError.value = ''
  try { await store.load({ ...filters }, page.value, pageSize.value) } catch (error) { if (sequence === loadSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } } finally { if (sequence === loadSequence) loading.value = false }
}

function clearFilters() {
  Object.assign(filters, { keyword: '', actor: '', module: 'all', action: '', outcome: 'all', from: undefined, to: undefined })
  page.value = 1
}
async function openDetails(event: AuditEvent) { try { selectedEvent.value = await store.detail(event.id); drawerVisible.value = true } catch (error) { ElMessage.error(errorMessage(error)) } }
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}
function displayAction(value: string) { return actionLabel[value] ?? value }
function outcomeFor(event: AuditEvent) { return outcomeLabel[event.outcome] }

async function exportCsv() {
  try {
    const csv = await store.exportCsv({ ...filters })
    const url = URL.createObjectURL(csv)
    const anchor = document.createElement('a'); anchor.href = url
    anchor.download = `operation-log-${new Date().toISOString().slice(0, 10)}.csv`
    anchor.click(); URL.revokeObjectURL(url)
    ElMessage.success('已导出当前筛选结果')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
</script>

<template>
  <div class="content-management-view audit-view" data-testid="logs-view">
    <header class="page-heading-row content-heading">
      <div>
        <div class="page-eyebrow">系统 <span aria-hidden="true">/</span> 操作日志</div>
        <h1 class="page-title">操作日志</h1>
        <p class="page-description">查看服务器保存的管理操作与处理结果。</p>
      </div>
      <div class="audit-heading-actions">
        <span class="demo-badge"><span class="environment-dot" />服务器审计记录</span>
        <el-button data-testid="audit-export" :disabled="result.total === 0" @click="exportCsv"><Download :size="14" />导出当前结果 CSV</el-button>
      </div>
    </header>

    <el-alert class="audit-demo-notice" data-testid="audit-demo-notice" type="info" :closable="false" show-icon>
      操作日志由服务器持久化，只展示安全摘要。筛选、详情和导出均以服务器记录为准。
    </el-alert>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <ContentMetricGrid :items="metricItems" />

    <section class="panel-card audit-filter-panel" aria-label="操作日志筛选">
      <div class="audit-filter-grid">
        <el-input v-model="filters.keyword" aria-label="搜索操作日志" placeholder="搜索动作、目标或安全摘要" clearable>
          <template #prefix><Search :size="15" /></template>
        </el-input>
        <el-input v-model="filters.actor" aria-label="筛选管理员" placeholder="管理员账号" clearable />
        <el-select v-model="filters.module" aria-label="业务模块" class="filter-select" data-testid="audit-module-filter">
          <el-option label="全部模块" value="all" />
          <el-option v-for="(label, key) in moduleLabel" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="filters.action" aria-label="操作类型" class="filter-select" clearable placeholder="全部操作">
          <el-option v-for="(label, key) in actionLabel" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="filters.outcome" aria-label="处理结果" class="filter-select">
          <el-option label="全部结果" value="all" />
          <el-option v-for="(label, key) in outcomeLabel" :key="key" :label="label" :value="key" />
        </el-select>
        <el-date-picker v-model="filters.from" aria-label="开始日期" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
        <el-date-picker v-model="filters.to" aria-label="结束日期" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
        <el-button data-testid="audit-clear-filters" @click="clearFilters"><RotateCcw :size="14" />重置筛选</el-button>
      </div>
      <div class="audit-filter-summary">共找到 <strong>{{ result.total }}</strong> 条记录<span v-if="filters.keyword">，关键词“{{ filters.keyword }}”</span></div>
    </section>

    <section class="panel-card moderation-table-panel audit-table-panel">
      <div class="moderation-table-title">
        <h2><FileClock :size="15" />操作记录</h2>
        <span>只读 · 最近操作优先</span>
      </div>
      <el-table v-loading="loading" :data="safeRows" class="moderation-table audit-table" row-key="safe.id" aria-label="操作日志列表">
        <el-table-column label="发生时间" width="165"><template #default="scope"><span class="date-cell">{{ formatDate(scope.row.safe.createdAt) }}</span></template></el-table-column>
        <el-table-column label="操作者" width="142"><template #default="scope"><span class="audit-actor">{{ scope.row.safe.actor }}</span></template></el-table-column>
        <el-table-column label="业务模块" width="105"><template #default="scope"><span class="audit-module-chip">{{ moduleLabel[scope.row.safe.module as AuditModule] ?? scope.row.safe.module }}</span></template></el-table-column>
        <el-table-column label="操作详情" min-width="240"><template #default="scope"><div class="audit-action-cell"><strong>{{ displayAction(scope.row.safe.action) }}</strong><span v-if="scope.row.safe.summary && scope.row.safe.summary !== scope.row.safe.action">{{ scope.row.safe.summary }}</span></div></template></el-table-column>
        <el-table-column label="目标对象" min-width="145"><template #default="scope"><span>{{ scope.row.safe.targetType }}<small v-if="scope.row.safe.targetRef" :title="scope.row.safe.targetRef"> · {{ shortReference(scope.row.safe.targetRef) }}</small><small v-if="scope.row.safe.targetCount"> · {{ scope.row.safe.targetCount }} 项</small></span></template></el-table-column>
        <el-table-column label="结果" width="105"><template #default="scope"><span class="audit-outcome" :class="outcomeClass[scope.row.event.outcome as AuditOutcome]">{{ outcomeFor(scope.row.event) }}</span></template></el-table-column>
        <el-table-column label="查看" width="72" align="center"><template #default="scope"><el-button link type="primary" aria-label="查看操作详情" @click="openDetails(scope.row.event)"><Eye :size="15" /></el-button></template></el-table-column>
        <template #empty>
          <div v-if="store.stats.total === 0" class="audit-empty" data-testid="audit-empty-state"><span class="audit-empty-icon"><FileClock :size="24" /></span><strong>暂无操作记录</strong><p>执行管理操作后，安全事件会显示在这里。</p></div>
          <div v-else class="audit-empty" data-testid="audit-no-results"><span class="audit-empty-icon"><Search :size="24" /></span><strong>没有匹配的操作记录</strong><p>调整关键词或筛选条件后再试。</p></div>
        </template>
      </el-table>
      <footer class="audit-table-footer">
        <span>当前显示 {{ result.items.length }} 条，共 {{ result.total }} 条</span>
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" data-testid="audit-pagination" background layout="prev, pager, next, sizes" :page-sizes="[10, 20, 50]" :total="result.total" />
      </footer>
    </section>

    <el-drawer v-model="drawerVisible" data-testid="audit-detail-drawer" class="moderation-drawer audit-detail-drawer" direction="rtl" size="460px" :show-close="false">
      <template #header>
        <div class="drawer-heading"><div><span class="drawer-eyebrow">只读事件记录</span><h2>日志详情</h2></div><el-button circle text aria-label="关闭操作日志详情" @click="drawerVisible = false"><X :size="17" /></el-button></div>
      </template>
      <div v-if="selectedSafe" class="drawer-content audit-detail-content">
        <div class="audit-detail-result" :class="outcomeClass[selectedEvent!.outcome]">
          <component :is="selectedEvent!.outcome === 'success' ? ShieldCheck : selectedEvent!.outcome === 'business_rejected' ? TriangleAlert : ShieldX" :size="19" />
          <div><strong>{{ outcomeFor(selectedEvent!) }}</strong><span>{{ selectedSafe.summary === selectedSafe.action ? displayAction(selectedSafe.action) : selectedSafe.summary || displayAction(selectedSafe.action) }}</span></div>
        </div>
        <dl class="audit-detail-list">
          <div><dt>操作时间</dt><dd>{{ formatDate(selectedSafe.createdAt) }}</dd></div>
          <div><dt>操作者</dt><dd>{{ selectedSafe.actor }}</dd></div>
          <div><dt>业务模块</dt><dd>{{ moduleLabel[selectedSafe.module as AuditModule] ?? selectedSafe.module }}</dd></div>
          <div><dt>操作类型</dt><dd>{{ displayAction(selectedSafe.action) }}</dd></div>
          <div><dt>目标对象</dt><dd>{{ selectedSafe.targetType }}<span v-if="selectedSafe.targetRef"> · {{ selectedSafe.targetRef }}</span><span v-if="selectedSafe.targetCount"> · {{ selectedSafe.targetCount }} 项</span></dd></div>
          <div v-if="selectedSafe.changedFields"><dt>变更字段</dt><dd>{{ selectedSafe.changedFields }}</dd></div>
          <div v-if="selectedSafe.resultCode"><dt>结果代码</dt><dd>{{ selectedSafe.resultCode }}</dd></div>
          <div><dt>事件编号</dt><dd class="audit-event-id">{{ selectedSafe.id }}</dd></div>
        </dl>
        <p class="audit-detail-footnote">详情使用安全字段投影，不包含内容正文、处理理由或用户凭据。</p>
      </div>
    </el-drawer>
  </div>
</template>

<script lang="ts">
import { Search, X } from '@lucide/vue'
export default { components: { Search, X } }
</script>

<style scoped>
.audit-view { width: 100%; max-width: 1600px; margin: 0 auto; color: var(--xlk-color-text); }
.audit-heading-actions { display: flex; align-items: center; gap: 9px; }
.audit-demo-notice { display: none; }
.audit-filter-panel { padding: 14px 18px; margin-bottom: 0; border-radius: 20px 20px 0 0; border-bottom: 1px solid var(--xlk-color-divider); box-shadow: 0 2px 10px rgba(15, 23, 42, 0.03); }
.audit-filter-grid { display: grid; grid-template-columns: minmax(190px, 1.4fr) minmax(125px, 1fr) repeat(3, minmax(125px, .9fr)) repeat(2, minmax(125px, .8fr)) auto; gap: 9px; align-items: center; }
.audit-filter-grid .el-input__wrapper, .audit-filter-grid .el-select__wrapper, .audit-filter-grid .el-date-editor { min-height: 36px; border-radius: 12px; background: var(--xlk-color-primary-soft); box-shadow: none !important; }
.audit-filter-grid .el-button { margin: 0; }
.audit-filter-summary { display: none; }
.audit-table-panel { overflow: hidden; border-radius: 0 0 20px 20px; box-shadow: 0 10px 28px rgba(15, 23, 42, 0.035); }
.audit-table { min-height: 250px; }
.audit-actor { color: var(--xlk-color-text); font-size: 13px; font-weight: 600; }
.audit-module-chip { display: inline-flex; padding: 3px 10px; color: var(--xlk-accent-blue); background: var(--xlk-accent-blue-soft); border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.audit-action-cell { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.audit-action-cell strong { color: var(--xlk-color-text); font-size: 13.5px; font-weight: 650; }
.audit-action-cell span { overflow: hidden; color: var(--xlk-color-muted); font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.audit-table .cell small { color: var(--xlk-color-muted); font-size: 12.5px; }
.audit-outcome { display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.outcome-success { color: var(--xlk-accent-mint-strong); background: var(--xlk-accent-mint); }
.outcome-rejected { color: var(--xlk-accent-amber); background: var(--xlk-accent-amber-soft); }
.outcome-error { color: var(--xlk-accent-rose); background: var(--xlk-accent-rose-soft); }
.audit-empty { display: flex; flex-direction: column; align-items: center; padding: 24px 12px; color: #666666; }
.audit-empty-icon { width: 42px; height: 42px; display: grid; place-items: center; margin-bottom: 10px; color: #666666; background: #f5f5f5; border-radius: 50%; }
.audit-empty strong { color: #303030; font-size: 13px; }
.audit-empty p { margin: 5px 0 0; font-size: 13px; }
.audit-table-footer { min-height: 48px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 5px 15px; border-top: 1px solid #f5f5f5; color: #666666; font-size: 13px; }
.audit-table-footer .el-pagination { margin-left: auto; }
.audit-table-footer .el-pagination.is-background .el-pager li.is-active { background: #171717; }
.audit-detail-content { gap: 17px; }
.audit-detail-result { display: flex; align-items: center; gap: 10px; padding: 13px; border-radius: 8px; }
.audit-detail-result > div { display: flex; flex-direction: column; gap: 3px; }
.audit-detail-result strong { font-size: 13px; }
.audit-detail-result span { color: #666666; font-size: 13px; }
.audit-detail-list { display: flex; flex-direction: column; gap: 0; margin: 0; border-top: 1px solid #f5f5f5; }
.audit-detail-list > div { display: grid; grid-template-columns: 90px 1fr; gap: 10px; padding: 11px 2px; border-bottom: 1px solid #f5f5f5; font-size: 13px; }
.audit-detail-list dt { color: #666666; }
.audit-detail-list dd { margin: 0; color: #303030; overflow-wrap: anywhere; }
.audit-event-id { color: #666666 !important; font-family: ui-monospace, Consolas, monospace; font-size: 13px; }
.audit-detail-footnote { padding: 10px; color: #666666; background: #f5f5f5; border-radius: 6px; font-size: 13px; line-height: 1.6; }
@media (max-width: 1500px) { .audit-filter-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
@media (max-width: 850px) { .audit-heading-actions { align-items: flex-end; flex-direction: column; } .audit-filter-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .audit-table-footer { align-items: flex-start; flex-direction: column; } .audit-table-footer .el-pagination { margin-left: 0; } }
</style>

