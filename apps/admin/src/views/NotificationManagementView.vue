<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { Bell, CalendarDays, Eye, FileText, Info, Plus, Search, Send, ShieldCheck, UsersRound, X } from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import { useAdminOperationsStore, type AdminNotification, type NotificationAudience, type NotificationFilters, type NotificationInput } from '../stores/admin-operations'

const store = useAdminOperationsStore()
const filters = reactive<NotificationFilters>({ keyword: '', audience: 'all', status: 'all' })
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const drawerVisible = ref(false)
const drawerId = ref<string | null>(null)
const drawerMode = ref<'create' | 'edit' | 'view'>('create')
const form = reactive<NotificationInput>({ title: '', body: '', audience: 'all' })
let loadingSequence = 0
const loadError = ref('')

const result = computed(() => store.queryNotifications(filters, page.value, pageSize.value))
const recipientCount = computed(() => store.audiencePreview?.audience === form.audience ? store.audiencePreview.recipientCount : 0)
const previewLoading = ref(false)
let previewSequence = 0
watch([() => form.audience, drawerVisible], async () => {
  if (!drawerVisible.value || drawerMode.value === 'view') return
  const sequence = ++previewSequence; previewLoading.value = true
  try { await store.previewAudience(form.audience) } catch (error) { ElMessage.error(errorMessage(error)) } finally { if (sequence === previewSequence) previewLoading.value = false }
})
const selectedRecord = computed(() => drawerId.value ? store.notificationById(drawerId.value) : undefined)
const metrics = computed(() => [
  { label: '通知记录', value: store.notificationStats.total, unit: '条', note: '包含草稿与发送记录', icon: Bell },
  { label: '待发送草稿', value: store.notificationStats.draft, unit: '条', note: '可继续编辑并确认发送', icon: FileText },
  { label: '已发送', value: store.notificationStats.sent, unit: '条', note: '已写入站内接收记录', icon: Send },
  { label: '累计接收记录', value: store.notificationStats.inAppRecipientCount, unit: '条', note: '不会调用微信或外部推送服务', icon: ShieldCheck },
])
const audienceLabel: Record<NotificationAudience, string> = { all: '全体用户', active: '正常状态用户', recent: '近 30 天活跃用户' }
const audienceDescription: Record<NotificationAudience, string> = {
  all: '按服务器当前全部有效用户计算。',
  active: '按服务器有效账号状态计算。',
  recent: '按服务器账号状态及近30天活跃记录计算。',
}
function audienceName(value: NotificationAudience) { return audienceLabel[value] }

watch(filters, () => { page.value = 1; setLoading() }, { deep: true })
watch(page, setLoading)
onMounted(setLoading)
watch(pageSize, () => { page.value = 1; setLoading() })

async function setLoading() {
  const sequence = ++loadingSequence
  loading.value = true; loadError.value = ''
  try { await store.loadNotifications({ ...filters }, page.value, pageSize.value) }
  catch (error) { if (sequence === loadingSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (sequence === loadingSequence) loading.value = false }
}
function resetForm() { form.title = ''; form.body = ''; form.audience = 'all' }
function openCreate() { drawerId.value = null; drawerMode.value = 'create'; resetForm(); drawerVisible.value = true }
async function openEdit(item: AdminNotification) {
  try { item = await store.loadNotificationDetail(item.id) } catch (error) { ElMessage.error(errorMessage(error)); return }
  if (item.status === 'sent') return
  drawerId.value = item.id; drawerMode.value = 'edit'; form.title = item.title; form.body = item.body; form.audience = item.audience; drawerVisible.value = true
}
async function openRecord(item: AdminNotification) {
  try { item = await store.loadNotificationDetail(item.id) } catch (error) { ElMessage.error(errorMessage(error)); return }
  drawerId.value = item.id; drawerMode.value = item.status === 'sent' ? 'view' : 'edit'
  form.title = item.title; form.body = item.body; form.audience = item.audience; drawerVisible.value = true
}
function validForm() {
  if (!form.title.trim()) { ElMessage.warning('请填写通知标题'); return false }
  if (!form.body.trim()) { ElMessage.warning('请填写通知正文'); return false }
  if (previewLoading.value || !store.audiencePreview || !recipientCount.value) { ElMessage.warning('请等待受众核对完成；没有接收人时不能发送'); return false }
  return true
}
async function saveDraft() {
  if (!form.title.trim() || !form.body.trim()) { validForm(); return }
  try {
    const saved = await store.saveNotificationDraft({ ...form }, drawerId.value ?? undefined)
    drawerId.value = saved.id; drawerVisible.value = false
    ElMessage.success('通知草稿已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function confirmSend() {
  if (!validForm()) return
  const count = recipientCount.value
  try {
    await ElMessageBox.confirm(`将向「${audienceLabel[form.audience]}」的 ${count} 位用户发送真实站内通知，发送后无法撤回。`, '发送前风险提示', {
      type: 'warning', confirmButtonText: '确认发送站内通知', cancelButtonText: '返回检查', distinguishCancelAndClose: true,
    })
    const saved = drawerId.value
      ? await store.saveNotificationDraft({ ...form }, drawerId.value)
      : await store.saveNotificationDraft({ ...form })
    if (!saved || !(await store.sendNotification(saved.id))) { ElMessage.error('发送未完成：请重新核对接收范围和通知状态'); return }
    drawerId.value = saved.id; drawerVisible.value = false
    ElMessage.success('站内通知已发送')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
}
function formatDate(value: string | null) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}
function statusLabel(item: AdminNotification) { return item.status === 'sent' ? '已发送站内通知' : '草稿' }
</script>

<template>
  <el-config-provider :locale="zhCn">
  <div class="content-management-view operations-management-view" data-testid="notifications-view">
    <header class="page-heading-row content-heading">
      <div><div class="page-eyebrow">运营管理 <span aria-hidden="true">/</span> 通知管理</div><h1 class="page-title">通知管理</h1><p class="page-description">通知通过站内消息发送给所选用户，发送后无法撤回。</p></div>
      <div class="operations-heading-actions"><span class="demo-badge"><span class="environment-dot" />服务器数据</span><el-button type="primary" data-testid="create-notification" @click="openCreate"><Plus :size="15" />创建通知</el-button></div>
    </header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <ContentMetricGrid :items="metrics" />

    <section class="panel-card content-filter-panel" aria-label="通知搜索与筛选">
      <div class="operations-filter-row notification-filter-row">
        <el-input v-model="filters.keyword" class="moderation-search" aria-label="搜索通知" placeholder="搜索通知标题、正文或编号" clearable data-testid="notification-search"><template #prefix><Search :size="16" /></template></el-input>
        <el-select v-model="filters.audience" aria-label="通知接收范围筛选" class="filter-select" data-testid="notification-audience-filter"><el-option label="全部接收范围" value="all" /><el-option label="正常状态用户" value="active" /><el-option label="近 30 天活跃用户" value="recent" /></el-select>
        <el-select v-model="filters.status" aria-label="通知状态筛选" class="filter-select" data-testid="notification-status-filter"><el-option label="全部状态" value="all" /><el-option label="草稿" value="draft" /><el-option label="已发送站内通知" value="sent" /></el-select>
      </div>
      <div class="filter-hint"><UsersRound :size="14" /> 接收范围与人数由服务器核对；发送时将再次校验受众是否变化。</div>
    </section>

    <section class="panel-card moderation-table-panel" aria-label="通知记录列表">
      <div class="moderation-table-title"><h2><Bell :size="16" />通知记录</h2><span>{{ result.total }} 条服务器记录</span></div>
      <el-table v-loading="loading" :data="result.items" row-key="id" class="moderation-table operations-table notification-table" empty-text="暂无符合条件的通知" data-testid="notification-table">
        <el-table-column label="通知标题 / 编号" min-width="245"><template #default="{ row }"><button class="operation-title-cell" type="button" data-testid="open-notification-record" @click="openRecord(row)"><strong>{{ row.title }}</strong><small>{{ row.id }} · {{ row.body }}</small></button></template></el-table-column>
        <el-table-column label="接收范围" width="175"><template #default="{ row }"><span class="operation-scope"><UsersRound :size="13" />{{ audienceName(row.audience) }}</span></template></el-table-column>
        <el-table-column label="状态" min-width="175"><template #default="{ row }"><el-tag :type="row.status === 'sent' ? 'success' : 'warning'" effect="light" round>{{ statusLabel(row) }}</el-tag><small v-if="row.status === 'sent'" class="operation-status-note">{{ row.recipientCount === null ? '未记录接收人数' : `站内接收 ${row.recipientCount} 位用户` }}</small></template></el-table-column>
        <el-table-column label="创建 / 发送时间" width="160"><template #default="{ row }"><span class="operation-date"><CalendarDays :size="13" />{{ formatDate(row.sentAt || row.updatedAt) }}</span></template></el-table-column>
        <el-table-column label="管理操作" width="210" fixed="right" align="right"><template #default="{ row }"><div class="operation-row-actions"><el-button text type="primary" :aria-label="`查看通知：${row.title}`" data-testid="notification-view-action" @click="openRecord(row)"><Eye :size="14" />查看</el-button><el-button v-if="row.status === 'draft'" text type="primary" :aria-label="`编辑通知：${row.title}`" data-testid="notification-edit" @click="openEdit(row)"><FileText :size="14" />编辑</el-button><el-button v-if="row.status === 'draft'" text type="success" :aria-label="`发送通知：${row.title}`" data-testid="notification-send-row" @click="openEdit(row)"><Send :size="14" />发送</el-button></div></template></el-table-column>
      </el-table>
      <div class="table-footer"><span>当前展示 {{ result.items.length }} 条</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50]" :total="result.total" layout="sizes, prev, pager, next" background small data-testid="notification-pagination" /></div>
    </section>

    <el-drawer v-model="drawerVisible" :title="drawerMode === 'view' ? '通知发送记录' : drawerMode === 'edit' ? '编辑系统通知' : '创建系统通知'" direction="rtl" size="min(620px, 54vw)" class="moderation-drawer operations-drawer notification-editor-drawer" data-testid="notification-editor-drawer" destroy-on-close>
      <template #header><div class="drawer-heading"><div><span class="drawer-eyebrow">运营管理 / 通知管理</span><h2>{{ drawerMode === 'view' ? '发送记录' : drawerMode === 'edit' ? '编辑系统通知' : '创建系统通知' }}</h2></div><el-tag v-if="drawerMode === 'view'" type="success" effect="light" round>已发送</el-tag><el-tag v-else effect="light" round>草稿</el-tag></div></template>
      <div class="drawer-content operations-form-content">
        <div class="operations-risk-note"><Info :size="16" /><div><strong>真实站内通知</strong><p>确认发送后，通知会持久化至所选用户的站内消息。此功能不发送微信订阅消息。</p></div></div>
        <label class="operations-field"><span>通知标题 <b>*</b></span><el-input v-model="form.title" maxlength="50" show-word-limit :readonly="drawerMode === 'view'" placeholder="请输入通知标题" aria-label="通知标题" data-testid="notification-title" /></label>
        <label class="operations-field"><span>通知正文 <b>*</b></span><el-input v-model="form.body" type="textarea" :rows="7" maxlength="500" show-word-limit resize="vertical" :readonly="drawerMode === 'view'" placeholder="填写需要向所选用户范围提示的信息" aria-label="通知正文" data-testid="notification-body" /></label>
        <label class="operations-field"><span>接收范围 <b>*</b></span><el-select v-model="form.audience" aria-label="接收范围" :disabled="drawerMode === 'view'" data-testid="notification-audience"><el-option label="全体用户" value="all" /><el-option label="正常状态用户" value="active" /><el-option label="近 30 天活跃用户" value="recent" /></el-select></label>
        <section class="audience-match-card" data-testid="audience-match-card"><div class="audience-match-head"><span><UsersRound :size="15" />受众范围核对</span><strong data-testid="audience-match-count">{{ drawerMode === 'view' ? selectedRecord?.recipientCount ?? '未记录' : recipientCount }}<small>{{ drawerMode === 'view' ? selectedRecord?.recipientCount === null ? '' : '位用户' : '位用户' }}</small></strong></div><p>{{ audienceDescription[form.audience] }}</p><small>发送前为受众预览；发送记录中的人数为实际写入的站内接收记录数。</small></section>
        <div v-if="drawerMode === 'view'" class="notification-send-record" data-testid="notification-send-record"><div><span>发送时间</span><strong>{{ formatDate(selectedRecord?.sentAt ?? null) }}</strong></div><div><span>记录类型</span><strong>站内发送</strong></div><div><span>外部消息发送</span><strong>未发送</strong></div></div>
        <div v-else class="operations-risk-note is-warning"><ShieldCheck :size="16" /><div><strong>发送后不可撤回</strong><p>将向所选用户发送真实站内通知，不调用微信订阅消息。请核对通知内容、接收范围与人数。</p></div></div>
        <div class="drawer-actions"><el-button @click="drawerVisible = false"><X :size="14" />{{ drawerMode === 'view' ? '关闭' : '取消' }}</el-button><span class="drawer-actions-spacer" /><template v-if="drawerMode !== 'view'"><el-button data-testid="save-notification-draft" @click="saveDraft">保存草稿</el-button><el-button type="primary" :disabled="previewLoading || !recipientCount" data-testid="confirm-notification-send" @click="confirmSend"><Send :size="14" />确认发送站内通知</el-button></template></div>
      </div>
    </el-drawer>
  </div>
  </el-config-provider>
</template>
