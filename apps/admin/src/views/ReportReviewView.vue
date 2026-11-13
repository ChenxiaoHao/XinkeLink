<script setup lang="ts">
import { reportReasonLabel } from '../utils/admin-display'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { AlertTriangle, Ban, Check, Clock3, Eye, FileText, Flag, Image as ImageIcon, MessageSquareText, Search, ShieldAlert, UserRound, UsersRound, X } from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import RenewingImage from '../components/content/RenewingImage.vue'
import { useContentModerationStore, type AdminReport, type ReportFilters, type ReportResolution, type ReportType } from '../stores/content-moderation'

const store = useContentModerationStore()
const filters = reactive<ReportFilters>({ keyword: '', type: 'all', priority: 'all', status: 'pending', period: 'all' })
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const detailReportId = ref<string | null>(null)
const detailVisible = ref(false)
const resolutionAction = ref<ReportResolution>('reject')
const resolutionReason = ref('')
let loadingSequence = 0
const loadError = ref('')

const result = computed(() => store.queryReports(filters, page.value, pageSize.value))
const detailReport = computed(() => detailReportId.value ? store.reportById(detailReportId.value) : undefined)
const metrics = computed(() => [
  { label: '待处理举报', value: store.reportStats.pending, unit: '件', note: '等待管理员审核', icon: Flag, danger: store.reportStats.pending > 0 },
  { label: '高优先级', value: store.reportStats.highPriority, unit: '件', note: '建议优先查看', icon: AlertTriangle, danger: store.reportStats.highPriority > 0 },
  { label: '已处理', value: store.reportStats.processed, unit: '件', note: '保留审核记录', icon: Check },
  { label: '举报总数', value: store.reportStats.total, unit: '件', note: '服务器记录', icon: ShieldAlert },
])
const actionsByType: Record<ReportType, Array<{ label: string; value: ReportResolution }>> = {
  post: [{ label: '驳回举报', value: 'reject' }, { label: '隐藏帖子', value: 'hide_post' }],
  comment: [{ label: '驳回举报', value: 'reject' }, { label: '隐藏评论', value: 'hide_comment' }],
  user: [{ label: '驳回举报', value: 'reject' }, { label: '发送提醒', value: 'warn_user' }, { label: '限制用户', value: 'restrict_user' }],
  message: [{ label: '驳回举报', value: 'reject' }, { label: '隐藏私信证据', value: 'hide_message' }],
}
const availableActions = computed(() => detailReport.value ? actionsByType[detailReport.value.type] : [])

async function setLoading() {
  const sequence = ++loadingSequence
  loading.value = true; loadError.value = ''
  try { await store.loadReports({ ...filters }, page.value, pageSize.value) }
  catch (error) { if (sequence === loadingSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (sequence === loadingSequence) loading.value = false }
}
watch(filters, () => { page.value = 1; setLoading() }, { deep: true })
watch(page, setLoading)
watch(pageSize, () => { page.value = 1; setLoading() })
onMounted(setLoading)
onBeforeRouteLeave(() => { detailVisible.value = false; detailReportId.value = null })
function formatDate(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) }
function typeLabel(type: ReportType) { return ({ post: '帖子', comment: '评论', user: '用户', message: '私信' })[type] }
function typeIcon(type: ReportType) { return ({ post: FileText, comment: MessageSquareText, user: UsersRound, message: MessageSquareText })[type] }
function priorityLabel(priority: AdminReport['priority']) { return priority === 'high' ? '高优先级' : priority === 'medium' ? '中优先级' : '普通优先级' }
function resolutionLabel(action: ReportResolution | null) {
  if (!action) return '未处理'
  const labels: Record<ReportResolution, string> = { reject: '驳回举报', hide_post: '隐藏帖子', hide_comment: '隐藏评论', restrict_user: '限制用户', warn_user: '发送提醒', hide_message: '隐藏私信证据' }
  return labels[action]
}
async function openReport(report: AdminReport) {
  try { await store.loadReportDetail(report.id) } catch (error) { ElMessage.error(errorMessage(error)); return }
  detailReportId.value = report.id
  resolutionAction.value = actionsByType[report.type][0].value
  resolutionReason.value = ''
  detailVisible.value = true
}
function closeReport() { detailReportId.value = null; resolutionReason.value = '' }
function targetStatusLabel(report: AdminReport) {
  return ({ available: '当前可用', active: '正常活跃', restricted: '受限制', banned: '临时封禁', temporarily_banned: '临时封禁', permanently_banned: '永久封禁', visible: '正常展示', hidden: '已隐藏', deleted: '内容已删除', unavailable: '当前不可用' } as Record<string, string>)[report.targetAvailability] ?? report.targetAvailability
}
async function processCurrentReport() {
  const report = detailReport.value
  const reason = resolutionReason.value.trim()
  if (!report || report.status !== 'pending' || !reason) { ElMessage.warning('请先填写处理原因'); return }
  const actionLabel = resolutionLabel(resolutionAction.value)
  try {
    await ElMessageBox.confirm(`即将对「${report.targetTitle}」执行“${actionLabel}”。处理后会同步更新关联内容，且该举报不可重复处理。`, '确认处理举报？', { type: 'warning', confirmButtonText: '确认处理', cancelButtonText: '返回检查', distinguishCancelAndClose: true })
    const completed = await store.processReport(report.id, resolutionAction.value, reason)
    if (!completed) { ElMessage.error('处理未完成：关联内容可能已移除或举报已处理'); return }
    ElMessage.success(`已完成：${actionLabel}`)
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
}
</script>

<template>
  <el-config-provider :locale="zhCn">
    <div class="content-management-view report-review-view" data-testid="reports-view">
      <header class="page-heading-row content-heading">
        <div><div class="page-eyebrow">社区治理 <span aria-hidden="true">/</span> 举报审核</div><h1 class="page-title">违规举报处置中心</h1><p class="page-description">查看举报内容、核对证据并记录处理结果。数据来自服务器，操作结果以服务器响应为准。</p></div>
        <span class="demo-badge"><span class="environment-dot" />服务器数据</span>
      </header>

      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <ContentMetricGrid :items="metrics" />

      <section class="panel-card content-filter-panel" aria-label="举报筛选">
        <div class="report-queue-tabs" role="group" aria-label="举报处理状态"><button type="button" :class="{ active: filters.status === 'pending' }" data-testid="report-pending-tab" @click="filters.status = 'pending'">待审核 <b>{{ store.reportStats.pending }}</b></button><button type="button" :class="{ active: filters.status === 'processed' }" data-testid="report-processed-tab" @click="filters.status = 'processed'">已处理 <b>{{ store.reportStats.processed }}</b></button><button type="button" :class="{ active: filters.status === 'all' }" data-testid="report-all-tab" @click="filters.status = 'all'">全部举报</button><span class="report-queue-note"><Clock3 :size="13" /> 高优先级队列优先展示</span></div>
        <div class="report-filter-row">
          <el-input v-model="filters.keyword" class="moderation-search" aria-label="搜索举报" placeholder="搜索举报编号、内容或用户" clearable data-testid="report-search"><template #prefix><Search :size="16" /></template></el-input>
          <el-select v-model="filters.type" aria-label="举报类型筛选" class="filter-select" data-testid="report-type-filter"><el-option label="全部类型" value="all" /><el-option label="帖子" value="post" /><el-option label="评论" value="comment" /><el-option label="用户" value="user" /><el-option label="私信" value="message" /></el-select>
          <el-select v-model="filters.priority" aria-label="举报优先级筛选" class="filter-select" data-testid="report-priority-filter"><el-option label="全部优先级" value="all" /><el-option label="高优先级" value="high" /><el-option label="中优先级" value="medium" /><el-option label="普通优先级" value="low" /></el-select>
          <el-select v-model="filters.period" aria-label="举报时间筛选" class="filter-select" data-testid="report-period-filter"><el-option label="全部时间" value="all" /><el-option label="近 7 天" value="7d" /><el-option label="近 30 天" value="30d" /></el-select>
        </div>
        <div class="filter-hint"><ShieldAlert :size="14" /> 处理结果会同步更新对应帖子或评论状态，并保留处理原因。</div>
      </section>

      <section class="panel-card moderation-table-panel" aria-label="举报列表">
        <div class="moderation-table-title"><h2><Flag :size="16" />{{ filters.status === 'processed' ? '已处理记录' : filters.status === 'pending' ? '待审核举报列表' : '全部举报列表' }}</h2><span>共 {{ result.total }} 件举报</span></div>
        <el-table v-loading="loading" :data="result.items" row-key="id" class="moderation-table report-table" empty-text="暂无符合条件的举报" data-testid="report-table">
          <el-table-column label="举报内容" min-width="270"><template #default="{ row }"><button class="report-content-cell" type="button" :aria-label="`查看举报：${row.id}`" data-testid="open-report-detail" @click="openReport(row)"><span class="report-id-line"><span class="content-id">{{ row.id }}</span><el-tag size="small" effect="plain"><component :is="typeIcon(row.type)" :size="12" />{{ typeLabel(row.type) }}</el-tag></span><strong>{{ reportReasonLabel(row.reason) }}</strong><small>{{ row.targetTitle }}</small></button></template></el-table-column>
          <el-table-column label="举报人 / 被举报人" width="184"><template #default="{ row }"><div class="report-parties"><span><small>举报人</small><strong>{{ row.reporterName }}</strong></span><span><small>被举报人</small><strong>{{ row.targetName }}</strong></span></div></template></el-table-column>
          <el-table-column label="优先级" width="116" align="center"><template #default="{ row }"><el-tag :type="row.priority === 'high' ? 'danger' : row.priority === 'medium' ? 'warning' : 'info'" effect="light" round>{{ priorityLabel(row.priority) }}</el-tag></template></el-table-column>
          <el-table-column label="处理状态" width="104" align="center"><template #default="{ row }"><el-tag :type="row.status === 'pending' ? 'danger' : 'success'" effect="light" round>{{ row.status === 'pending' ? '待处理' : '已处理' }}</el-tag></template></el-table-column>
          <el-table-column label="举报时间" width="120" align="center"><template #default="{ row }"><span class="date-cell">{{ formatDate(row.createdAt) }}</span></template></el-table-column>
          <el-table-column label="管理操作" width="112" fixed="right" align="center"><template #default="{ row }"><el-tooltip :content="row.status === 'pending' ? '查看并处理' : '查看处理记录'"><el-button text circle aria-label="查看举报详情" data-testid="view-report" @click="openReport(row)"><Eye :size="16" /></el-button></el-tooltip></template></el-table-column>
        </el-table>
        <div class="table-footer"><span>当前展示 {{ result.items.length }} 件</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20]" :total="result.total" layout="sizes, prev, pager, next" background small data-testid="report-pagination" /></div>
      </section>

      <el-drawer v-model="detailVisible" title="举报详情" direction="rtl" size="min(720px, 56vw)" class="moderation-drawer report-detail-drawer" data-testid="report-detail-drawer" @closed="closeReport">
        <template #header><div class="drawer-heading"><div><span class="drawer-eyebrow">举报核实与处理记录</span><h2>举报详情</h2></div><el-tag v-if="detailReport" :type="detailReport.status === 'pending' ? 'danger' : 'success'" effect="light" round>{{ detailReport.status === 'pending' ? '待处理' : '已处理' }}</el-tag></div></template>
        <div v-if="detailReport" class="drawer-content report-detail-content">
          <div class="detail-id-line"><span class="content-id">{{ detailReport.id }}</span><el-tag :type="detailReport.priority === 'high' ? 'danger' : detailReport.priority === 'medium' ? 'warning' : 'info'" effect="plain">{{ priorityLabel(detailReport.priority) }}</el-tag><span>{{ formatDate(detailReport.createdAt) }}</span></div>
          <section class="report-summary-card"><div class="detail-section-title"><ShieldAlert :size="15" />举报原因</div><strong>{{ reportReasonLabel(detailReport.reason) }}</strong><p>{{ detailReport.description }}</p></section>
          <section class="report-party-grid"><article><span class="report-party-label"><Flag :size="13" />举报人</span><strong>{{ detailReport.reporterName }}</strong><small>{{ detailReport.reporterId ? `用户 ID：${detailReport.reporterId}` : '历史身份资料不可用' }}</small></article><article><span class="report-party-label"><UserRound :size="13" />被举报人</span><strong>{{ detailReport.targetName }}</strong><small>{{ detailReport.targetUserId ? `用户 ID：${detailReport.targetUserId}` : '历史身份资料不可用' }}</small></article></section>
          <section class="report-original-content"><div class="detail-section-title"><component :is="typeIcon(detailReport.type)" :size="15" />原始内容 <span>{{ typeLabel(detailReport.type) }} · {{ targetStatusLabel(detailReport) }}</span></div>
            <strong class="report-target-title">{{ detailReport.snapshot.title || detailReport.targetTitle }}</strong>
            <p>{{ detailReport.snapshot.content || '该案件未保存可展示正文。' }}</p>
            <div v-if="detailReport.snapshot.images.length" class="context-image-row"><RenewingImage v-for="(image, index) in detailReport.snapshot.images" :key="image + index" :src="image" :preview-src-list="detailReport.snapshot.images" :initial-index="index" fit="cover" class="context-image" /></div>
            <small>案件快照 · 目标编号 {{ detailReport.targetId }}</small>
          </section>
          <section class="report-evidence-section"><div class="detail-section-title"><ImageIcon :size="15" />举报证据 <span>{{ detailReport.evidence.length }} 张</span></div><div v-if="detailReport.evidence.length" class="report-evidence-grid"><RenewingImage v-for="(image, index) in detailReport.evidence" :key="image + index" :src="image" :preview-src-list="detailReport.evidence" :initial-index="index" fit="cover" class="report-evidence-image" data-testid="report-evidence-image" /></div><p v-else class="inline-empty">举报人未提交图片证据</p></section>
          <section v-if="detailReport.status === 'pending'" class="report-resolution-panel" data-testid="report-resolution-panel"><div class="detail-section-title"><Check :size="15" />审核处理</div><label class="resolution-label" for="resolution-action">处理方式</label><el-select id="resolution-action" v-model="resolutionAction" aria-label="举报处理方式" data-testid="report-resolution-action"><el-option v-for="action in availableActions" :key="action.value" :label="action.label" :value="action.value" /></el-select><label class="resolution-label" for="resolution-reason">处理原因 <span>必填</span></label><el-input id="resolution-reason" v-model="resolutionReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="请填写核实依据与处理说明" data-testid="report-resolution-reason" /><p v-if="resolutionAction === 'restrict_user'" class="resolution-caution"><Ban :size="14" /> 限制状态将记录在服务器数据中。</p><div class="drawer-actions"><el-button aria-label="关闭举报详情" @click="detailVisible = false"><X :size="15" />取消</el-button><el-button type="primary" data-testid="confirm-report-processing" @click="processCurrentReport"><Check :size="15" />确认处理</el-button></div></section>
          <section v-else class="report-history-panel" data-testid="report-history-panel"><div class="detail-section-title"><Clock3 :size="15" />审核处理记录 <span>{{ detailReport.history.length }} 条</span></div><div v-for="(entry, index) in detailReport.history" :key="entry.processedAt + index" class="report-history-entry"><span class="history-mark"><Check :size="12" /></span><div><strong>{{ resolutionLabel(entry.action) }} · {{ entry.operator }}</strong><p>{{ entry.reason }}</p><small>{{ formatDate(entry.processedAt) }}</small></div></div><div class="report-history-current"><span>处理方式</span><strong>{{ resolutionLabel(detailReport.resolution) }}</strong><span>处理结果</span><p>{{ detailReport.resolutionReason }}</p></div><div class="drawer-actions"><el-button aria-label="关闭举报详情" @click="detailVisible = false"><X :size="15" />关闭</el-button></div></section>
        </div><el-empty v-else description="举报记录不存在" />
      </el-drawer>
    </div>
  </el-config-provider>
</template>
