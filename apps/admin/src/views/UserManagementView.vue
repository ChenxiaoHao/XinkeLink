<script setup lang="ts">
import { reportReasonLabel } from '../utils/admin-display'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { Ban, CalendarDays, FileText, Flag, MessageSquareText, Search, ShieldCheck, UserRound, UsersRound, X } from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import RenewingImage from '../components/content/RenewingImage.vue'
import { useContentModerationStore, type UserFilters, type UserRestrictionChannel, type UserStatus } from '../stores/content-moderation'

type UserAction =
  | { type: 'restriction'; channel: UserRestrictionChannel; enabled: boolean }
  | { type: 'ban'; duration: number | 'permanent' }
  | { type: 'lift' }

const store = useContentModerationStore()
const filters = reactive<UserFilters>({ keyword: '', status: 'all', restriction: 'all' })
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const detailUserId = ref<string | null>(null)
const detailVisible = ref(false)
const activeTab = ref('content')
const pendingAction = ref<UserAction | null>(null)
const actionReason = ref('')
let loadingSequence = 0
const loadError = ref('')

const result = computed(() => store.queryUsers(filters, page.value, pageSize.value))
const detailUser = computed(() => detailUserId.value ? store.userById(detailUserId.value) : undefined)
const detailActivity = computed(() => detailUserId.value ? store.userActivity(detailUserId.value) : { posts: [], comments: [] })
const detailReports = computed(() => detailUserId.value ? store.reportsForUser(detailUserId.value) : [])
const detailTargetReportCount = computed(() => detailUserId.value ? store.reportsForUser(detailUserId.value).filter(report => report.targetUserId === detailUserId.value).length : 0)
const metrics = computed(() => [
  { label: '总用户', value: store.userStats.total.toLocaleString(), unit: '人', note: '来自服务器全局统计', icon: UsersRound },
  { label: '今日新增', value: store.userStats.todayNew, unit: '人', note: '按注册时间统计', icon: CalendarDays },
  { label: '受限制用户', value: store.userStats.restricted, unit: '人', note: '包含临时封禁用户', icon: ShieldCheck, danger: store.userStats.restricted > 0 },
  { label: '永久封禁', value: store.userStats.permanentlyBanned, unit: '人', note: '永久状态需保留审计记录', icon: Ban, danger: store.userStats.permanentlyBanned > 0 },
])
const channelLabels: Record<UserRestrictionChannel, string> = { publish: '发布内容', comment: '发表评论', message: '发送私信' }
const restrictionChannels: UserRestrictionChannel[] = ['publish', 'comment', 'message']

watch(filters, () => { page.value = 1; setLoading() }, { deep: true })
watch(page, setLoading)
watch(pageSize, () => { page.value = 1; setLoading() })
onMounted(() => { store.refreshExpiredBans(); setLoading() })
onBeforeRouteLeave(() => { detailVisible.value = false; detailUserId.value = null; pendingAction.value = null })

async function setLoading() {
  const sequence = ++loadingSequence
  loading.value = true; loadError.value = ''
  try { await store.loadUsers({ ...filters }, page.value, pageSize.value) }
  catch (error) { if (sequence === loadingSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (sequence === loadingSequence) loading.value = false }
}
async function openDetails(userId: string) { try { await store.loadUserDetail(userId); detailUserId.value = userId; activeTab.value = 'content'; pendingAction.value = null; detailVisible.value = true } catch (error) { ElMessage.error(errorMessage(error)) } }
function closeDetails() { detailUserId.value = null; pendingAction.value = null; actionReason.value = '' }
function formatDate(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}
function statusLabel(status: UserStatus) {
  return ({ active: '正常', restricted: '受限制', banned: '临时封禁', permanently_banned: '永久封禁' })[status]
}
function statusType(status: UserStatus) {
  return status === 'active' ? 'success' : status === 'restricted' ? 'warning' : 'danger'
}
function restrictionSummary(user: { restrictions: Record<UserRestrictionChannel, boolean> }) {
  const active = (Object.keys(channelLabels) as UserRestrictionChannel[]).filter(channel => user.restrictions[channel]).map(channel => channelLabels[channel])
  return active.length ? active.join('、') : '无'
}
function reportLabel(type: string) { return ({ post: '帖子', comment: '评论', user: '用户', message: '私信' } as Record<string, string>)[type] ?? type }
function historyLabel(action: string) {
  const labels: Record<string, string> = {
    restrict_publish: '限制发布内容', restrict_comment: '限制发表评论', restrict_message: '限制发送私信',
    release_publish: '解除发布限制', release_comment: '解除评论限制', release_message: '解除私信限制',
    lift_restrictions: '解除限制', temporary_ban: '临时封禁', temporary_ban_expired: '临时封禁到期', permanent_ban: '永久封禁',
    report_restriction: '举报审核限制用户', report_warning: '举报审核发送提醒',
  }
  return labels[action] ?? action
}
function requestAction(action: UserAction) {
  pendingAction.value = action
  actionReason.value = ''
}
function pendingActionLabel(action: UserAction) {
  if (action.type === 'restriction') return `${action.enabled ? '限制' : '解除'}${channelLabels[action.channel]}`
  if (action.type === 'ban') return action.duration === 'permanent' ? '永久封禁用户' : `临时封禁 ${action.duration} 天`
  return '解除用户限制'
}
async function confirmAction() {
  const user = detailUser.value
  const action = pendingAction.value
  const reason = actionReason.value.trim()
  if (!user || !action) return
  if (!reason) { ElMessage.warning('请填写处理原因'); return }
  const label = pendingActionLabel(action)
  try {
    await ElMessageBox.confirm(`即将对「${user.name}（${user.id}）」执行“${label}”。操作将同步更新举报审核中的用户状态并留下处理记录。`, '请再次确认用户处理', {
      type: action.type === 'ban' && action.duration === 'permanent' ? 'error' : 'warning',
      confirmButtonText: '确认处理', cancelButtonText: '返回检查', distinguishCancelAndClose: true,
    })
    let completed = false
    if (action.type === 'restriction') completed = await store.setUserRestriction(user.id, action.channel, action.enabled, reason)
    if (action.type === 'ban') completed = await store.banUser(user.id, action.duration, reason)
    if (action.type === 'lift') completed = await store.liftUserRestrictions(user.id, reason)
    if (!completed) { ElMessage.error('操作未完成：用户状态已变化或该状态不可解除'); return }
    ElMessage.success(`已完成：${label}`)
    pendingAction.value = null
    actionReason.value = ''
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <el-config-provider :locale="zhCn">
    <div class="content-management-view user-management-view" data-testid="users-view">
      <header class="page-heading-row content-heading">
        <div><div class="page-eyebrow">社区治理 <span aria-hidden="true">/</span> 用户管理</div><h1 class="page-title">用户管理</h1><p class="page-description">查看用户活动、关联举报和处理记录。资料由用户自行填写；处理记录由服务器保存。</p></div>
        <span class="demo-badge"><span class="environment-dot" />服务器数据</span>
      </header>

      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <ContentMetricGrid :items="metrics" />

      <section class="panel-card content-filter-panel" aria-label="用户搜索与筛选">
        <div class="user-filter-row">
          <el-input v-model="filters.keyword" class="moderation-search" aria-label="搜索用户" placeholder="搜索昵称、用户 ID、专业或班级" clearable data-testid="user-search"><template #prefix><Search :size="16" /></template></el-input>
          <el-select v-model="filters.status" aria-label="用户状态筛选" class="filter-select" data-testid="user-status-filter"><el-option label="全部状态" value="all" /><el-option label="正常" value="active" /><el-option label="受限制" value="restricted" /><el-option label="临时封禁" value="banned" /><el-option label="永久封禁" value="permanently_banned" /></el-select>
          <el-select v-model="filters.restriction" aria-label="限制类型筛选" class="filter-select" data-testid="user-restriction-filter"><el-option label="全部限制类型" value="all" /><el-option label="发布内容限制" value="publish" /><el-option label="评论限制" value="comment" /><el-option label="私信限制" value="message" /></el-select>
        </div>
        <div class="filter-hint"><UsersRound :size="14" /> 用户发布数由帖子和评论记录实时汇总；用户举报处理状态与举报审核共用。</div>
      </section>

      <section class="panel-card moderation-table-panel" aria-label="用户列表">
        <div class="moderation-table-title"><h2><UsersRound :size="16" />用户列表</h2><span>共 {{ result.total }} 位用户</span></div>
        <el-table v-loading="loading" :data="result.items" row-key="id" class="moderation-table user-table" empty-text="暂无符合条件的用户" data-testid="user-table">
          <el-table-column label="用户信息" min-width="220"><template #default="{ row }"><button class="publisher-cell user-info-cell" type="button" :aria-label="`查看用户：${row.name}`" data-testid="open-user-detail" @click="openDetails(row.id)"><RenewingImage avatar :size="35" :src="row.avatar"><UserRound :size="17" /></RenewingImage><span><strong>{{ row.name }}</strong><small>{{ row.id }}</small></span></button></template></el-table-column>
          <el-table-column label="专业 / 班级" min-width="170"><template #default="{ row }"><div class="user-school-cell"><strong>{{ row.major || '未填写' }}</strong><small>{{ row.className }}</small></div></template></el-table-column>
          <el-table-column label="发布内容" width="168" align="center"><template #default="{ row }"><span class="user-activity-counts"><span><FileText :size="13" />{{ row.contentCounts?.posts ?? '—' }}</span><span><MessageSquareText :size="13" />{{ row.contentCounts?.comments ?? '—' }}</span></span></template></el-table-column>
          <el-table-column label="账号状态 / 限制" min-width="178"><template #default="{ row }"><div class="user-status-cell"><el-tag :type="statusType(row.status)" effect="light" round>{{ statusLabel(row.status) }}</el-tag><small>{{ restrictionSummary(row) }}</small></div></template></el-table-column>
          <el-table-column label="举报记录" width="100" align="center"><template #default="{ row }"><button class="user-report-count" type="button" @click="openDetails(row.id); activeTab = 'reports'"><Flag :size="13" />{{ row.reportCount ?? '—' }}</button></template></el-table-column>
          <el-table-column label="注册时间" width="142" align="center"><template #default="{ row }"><span class="date-cell">{{ formatDate(row.joinedAt) }}</span></template></el-table-column>
          <el-table-column label="管理操作" width="92" fixed="right" align="center"><template #default="{ row }"><el-button text type="primary" data-testid="open-user-detail-action" @click="openDetails(row.id)">查看档案</el-button></template></el-table-column>
        </el-table>
        <div class="table-footer"><span>当前展示 {{ result.items.length }} 位</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20]" :total="result.total" layout="sizes, prev, pager, next" background small data-testid="user-pagination" /></div>
      </section>

      <el-drawer v-model="detailVisible" title="用户详情" direction="rtl" size="min(720px, 56vw)" class="moderation-drawer user-detail-drawer" data-testid="user-detail-drawer" @closed="closeDetails">
        <template #header><div class="drawer-heading"><div><span class="drawer-eyebrow">社区治理与用户信用档案</span><h2>用户详情</h2></div><el-tag v-if="detailUser" :type="statusType(detailUser.status)" effect="light" round>{{ statusLabel(detailUser.status) }}</el-tag></div></template>
        <div v-if="detailUser" class="drawer-content user-detail-content">
          <section class="user-profile-card"><RenewingImage avatar :size="54" :src="detailUser.avatar"><UserRound :size="24" /></RenewingImage><div class="user-profile-copy"><strong>{{ detailUser.name }}</strong><span>用户 ID：{{ detailUser.id }}</span><small>{{ [detailUser.major, detailUser.className].filter(Boolean).join(' · ') || '未填写专业与班级' }}</small></div><div class="user-profile-meta"><span>{{ detailUser.enrollmentYear ? detailUser.enrollmentYear + ' 入学' : '入学年份未填写' }}</span><span>最近活跃 {{ formatDate(detailUser.lastActiveAt) }}</span></div></section>
          <div class="user-profile-caution"><ShieldCheck :size="14" /> 专业与班级信息均为用户自填资料，未经平台核实。</div>
          <section class="user-activity-summary" aria-label="用户发布数量"><div><strong>{{ detailActivity.posts.length }}</strong><span>帖子</span></div><div><strong>{{ detailActivity.comments.length }}</strong><span>评论</span></div><div><strong>{{ detailTargetReportCount }}</strong><span>被举报</span></div></section>

          <section class="user-control-panel" data-testid="user-control-panel"><div class="detail-section-title"><ShieldCheck :size="15" />账号限制与处理</div><div class="user-restriction-controls">
            <el-button v-for="channel in restrictionChannels" :key="channel" size="small" :disabled="detailUser.status === 'banned' || detailUser.status === 'permanently_banned'" :data-testid="`toggle-restriction-${channel}`" @click="requestAction({ type: 'restriction', channel, enabled: !detailUser.restrictions[channel] })">{{ detailUser.restrictions[channel] ? `解除${channelLabels[channel]}` : `限制${channelLabels[channel]}` }}</el-button>
            <div class="user-danger-actions" aria-label="封禁与解除限制"><span>账号治理</span><el-button size="small" type="warning" plain :disabled="detailUser.status === 'banned' || detailUser.status === 'permanently_banned'" data-testid="ban-temporary" @click="requestAction({ type: 'ban', duration: 7 })"><Ban :size="14" />临时封禁 7 天</el-button>
            <el-button size="small" type="danger" plain :disabled="detailUser.status === 'permanently_banned'" data-testid="ban-permanent" @click="requestAction({ type: 'ban', duration: 'permanent' })"><Ban :size="14" />永久封禁</el-button>
            <el-button size="small" type="success" plain :disabled="detailUser.status === 'active' || detailUser.status === 'permanently_banned'" data-testid="lift-user-restrictions" @click="requestAction({ type: 'lift' })">解除限制</el-button>
          </div></div>
          <div v-if="detailUser.status === 'banned' && detailUser.banUntil" class="user-ban-until"><CalendarDays :size="13" /> 临时封禁至 {{ formatDate(detailUser.banUntil) }}</div><div v-else-if="detailUser.status === 'permanently_banned'" class="user-ban-until is-danger"><Ban :size="13" /> 永久封禁状态不可解除</div>
          <div v-if="pendingAction" class="user-action-editor" data-testid="user-action-editor"><div class="user-action-editor-title">{{ pendingActionLabel(pendingAction) }} <span>需要填写原因并再次确认</span></div><el-input v-model="actionReason" type="textarea" :rows="2" maxlength="200" show-word-limit aria-label="处理原因" placeholder="请填写核实依据与处理说明" data-testid="user-action-reason" /><div class="user-action-editor-buttons"><el-button size="small" @click="pendingAction = null; actionReason = ''">取消</el-button><el-button size="small" type="primary" data-testid="confirm-user-action" @click="confirmAction">继续确认</el-button></div></div>
          </section>

          <el-tabs v-model="activeTab" class="user-detail-tabs" data-testid="user-detail-tabs">
            <el-tab-pane label="发布内容" name="content"><div class="user-content-groups">
              <section><div class="user-subheading"><FileText :size="14" />帖子 <span>{{ detailActivity.posts.length }}</span></div><div v-if="detailActivity.posts.length" class="user-record-list"><article v-for="post in detailActivity.posts" :key="post.id" class="user-record-item"><div><strong>{{ post.title }}</strong><small>{{ post.category }} · {{ formatDate(post.createdAt) }}</small></div><el-tag :type="post.status === 'visible' ? 'success' : 'info'" effect="plain">{{ post.status === 'visible' ? '正常展示' : '已隐藏' }}</el-tag></article></div><el-empty v-else description="暂无帖子" :image-size="48" /></section>
              <section><div class="user-subheading"><MessageSquareText :size="14" />评论 <span>{{ detailActivity.comments.length }}</span></div><div v-if="detailActivity.comments.length" class="user-record-list"><article v-for="comment in detailActivity.comments" :key="comment.id" class="user-record-item"><div><strong>{{ comment.content }}</strong><small>{{ comment.linkedTitle }} · {{ formatDate(comment.createdAt) }}</small></div><el-tag :type="comment.status === 'visible' ? 'success' : 'info'" effect="plain">{{ comment.status === 'visible' ? '正常' : '已隐藏' }}</el-tag></article></div><el-empty v-else description="暂无评论" :image-size="48" /></section>
            </div></el-tab-pane>
            <el-tab-pane label="举报记录" name="reports"><div v-if="detailReports.length" class="user-record-list user-report-list"><article v-for="report in detailReports" :key="report.id" class="user-record-item user-report-item"><div><strong>{{ report.id }} · {{ reportLabel(report.type) }} · {{ reportReasonLabel(report.reason) }}</strong><small>{{ report.targetUserId === detailUser.id ? '被举报' : '举报人' }} · {{ report.description }}</small><small>处理：{{ report.resolution ? report.resolutionReason : '待处理' }}</small></div><el-tag :type="report.status === 'pending' ? 'warning' : 'success'" effect="plain">{{ report.status === 'pending' ? '待处理' : '已处理' }}</el-tag></article></div><el-empty v-else description="暂无关联举报记录" :image-size="54" /></el-tab-pane>
            <el-tab-pane label="处理历史" name="history"><div v-if="detailUser.history.length" class="user-record-list"><article v-for="(entry, index) in detailUser.history" :key="entry.createdAt + index" class="user-history-item"><div class="user-history-icon"><ShieldCheck :size="14" /></div><div><strong>{{ historyLabel(entry.action) }} · {{ entry.operator }}<span>{{ entry.source === 'report' ? '举报审核' : '用户管理' }}</span></strong><small>{{ entry.reason }}</small><small>{{ formatDate(entry.createdAt) }}</small></div></article></div><el-empty v-else description="暂无违规处理记录" :image-size="54" /></el-tab-pane>
          </el-tabs>
          <div class="drawer-actions"><el-button aria-label="关闭用户详情" @click="detailVisible = false"><X :size="15" />关闭</el-button></div>
        </div><el-empty v-else description="用户资料不存在" />
      </el-drawer>
    </div>
  </el-config-provider>
</template>
