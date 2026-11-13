<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { CalendarDays, Eye, FileText, Globe2, Megaphone, Pencil, Plus, RotateCcw, Search, Send, Trash2, X } from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import { useAdminOperationsStore, type AdminAnnouncement, type AnnouncementCategory, type AnnouncementFilters, type AnnouncementInput, type AnnouncementScope } from '../stores/admin-operations'

const store = useAdminOperationsStore()
const filters = reactive<AnnouncementFilters>({ keyword: '', category: 'all', status: 'all' })
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const editorVisible = ref(false)
const previewVisible = ref(false)
const editorId = ref<string | null>(null)
const editorForm = reactive<AnnouncementInput>({ title: '', body: '', category: 'community', scope: 'all' })
const previewItem = ref<Pick<AdminAnnouncement, 'title' | 'body' | 'category' | 'scope'> | null>(null)
let loadingSequence = 0
const loadError = ref('')

const result = computed(() => store.queryAnnouncements(filters, page.value, pageSize.value))
const metrics = computed(() => [
  { label: '总公告', value: store.announcementStats.total, unit: '条', note: '包含草稿与已撤回记录', icon: Megaphone },
  { label: '已发布', value: store.announcementStats.published, unit: '条', note: '当前展示中的社区公告', icon: Send },
  { label: '草稿', value: store.announcementStats.draft, unit: '条', note: '尚未发布的公告内容', icon: FileText },
  { label: '已撤回', value: store.announcementStats.withdrawn, unit: '条', note: '保留记录，可编辑后重新发布', icon: RotateCcw },
])
const categoryLabel: Record<AnnouncementCategory, string> = { community: '社区动态', activity: '活动信息', safety: '安全提示' }
const scopeLabel: Record<AnnouncementScope, string> = { all: '全站', forum: '论坛' }
const statusLabel = { draft: '草稿', published: '已发布', withdrawn: '已撤回' } as const
function categoryName(value: AnnouncementCategory) { return categoryLabel[value] }
function scopeName(value: AnnouncementScope) { return scopeLabel[value] }
function statusName(value: AdminAnnouncement['status']) { return statusLabel[value] }

watch(filters, () => { page.value = 1; setLoading() }, { deep: true })
watch(page, setLoading)
onMounted(setLoading)
watch(pageSize, () => { page.value = 1; setLoading() })

async function setLoading() {
  const sequence = ++loadingSequence
  loading.value = true; loadError.value = ''
  try { await store.loadAnnouncements({ ...filters }, page.value, pageSize.value) }
  catch (error) { if (sequence === loadingSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (sequence === loadingSequence) loading.value = false }
}
function resetForm() { editorForm.title = ''; editorForm.body = ''; editorForm.category = 'community'; editorForm.scope = 'all' }
function openCreate() { editorId.value = null; resetForm(); editorVisible.value = true }
async function openEdit(item: AdminAnnouncement) {
  try { item = await store.loadAnnouncementDetail(item.id) } catch (error) { ElMessage.error(errorMessage(error)); return }
  editorId.value = item.id
  editorForm.title = item.title; editorForm.body = item.body; editorForm.category = item.category; editorForm.scope = item.scope
  editorVisible.value = true
}
function showPreview(item: Pick<AdminAnnouncement, 'title' | 'body' | 'category' | 'scope'>) { previewItem.value = { ...item }; previewVisible.value = true }
function validForm() {
  if (!editorForm.title.trim()) { ElMessage.warning('请填写公告标题'); return false }
  if (!editorForm.body.trim()) { ElMessage.warning('请填写公告正文'); return false }
  return true
}
async function saveDraft() {
  if (!validForm()) return
  try {
    const saved = await store.saveAnnouncementDraft({ ...editorForm }, editorId.value ?? undefined)
    editorId.value = saved.id; editorVisible.value = false
    ElMessage.success('公告草稿已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function publishFromEditor() {
  if (!validForm()) return
  try {
    await ElMessageBox.confirm('确认将此公告发布到信科 Link 校园社区？公告将以平台社区信息展示，不代表学校官方通知。', '发布公告前请确认', {
      type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '继续编辑', distinguishCancelAndClose: true,
    })
    const saved = editorId.value
      ? await store.saveAnnouncementDraft({ ...editorForm }, editorId.value)
      : await store.saveAnnouncementDraft({ ...editorForm })
    if (!saved || !(await store.publishAnnouncement(saved.id))) { ElMessage.error('发布失败，公告内容可能已变化'); return }
    editorVisible.value = false
    ElMessage.success('公告已发布')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}
async function publish(item: AdminAnnouncement) {
  try {
    await ElMessageBox.confirm(`确认发布「${item.title}」？此公告将展示在信科 Link 校园社区。`, '发布公告前请确认', {
      type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消', distinguishCancelAndClose: true,
    })
    if (!(await store.publishAnnouncement(item.id))) { ElMessage.error('发布失败：公告状态已变化'); return }
    ElMessage.success('公告已发布')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
}
async function withdraw(item: AdminAnnouncement) {
  try {
    await ElMessageBox.confirm(`撤回「${item.title}」后，它将不再作为当前公告展示，但记录仍可编辑和重新发布。`, '确认撤回公告', {
      type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '返回', distinguishCancelAndClose: true,
    })
    if (!(await store.withdrawAnnouncement(item.id))) { ElMessage.error('撤回失败：公告状态已变化'); return }
    ElMessage.success('公告已撤回')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
}
async function remove(item: AdminAnnouncement) {
  try {
    await ElMessageBox.confirm(`删除「${item.title}」后将从公告列表移除，且无法恢复。`, '确认删除公告', {
      type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消', distinguishCancelAndClose: true,
    })
    if (!(await store.deleteAnnouncement(item.id))) { ElMessage.error('删除失败：公告记录不存在'); return }
    if (result.value.items.length === 1 && page.value > 1) page.value -= 1
    ElMessage.success('公告记录已删除')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
}
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}
</script>

<template>
  <el-config-provider :locale="zhCn">
  <div class="content-management-view operations-management-view" data-testid="announcements-view">
    <header class="page-heading-row content-heading">
      <div><div class="page-eyebrow">运营管理 <span aria-hidden="true">/</span> 公告管理</div><h1 class="page-title">公告管理</h1><p class="page-description">管理信科 Link 校园社区内展示的公告信息，不代表学校官方通知。数据保存在服务器。</p></div>
      <div class="operations-heading-actions"><span class="demo-badge"><span class="environment-dot" />服务器数据</span><el-button type="primary" data-testid="create-announcement" @click="openCreate"><Plus :size="15" />新建公告</el-button></div>
    </header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <ContentMetricGrid :items="metrics" />

    <section class="panel-card content-filter-panel" aria-label="公告搜索与筛选">
      <div class="operations-filter-row announcement-filter-row">
        <el-input v-model="filters.keyword" class="moderation-search" aria-label="搜索公告" placeholder="搜索公告标题、正文或编号" clearable data-testid="announcement-search"><template #prefix><Search :size="16" /></template></el-input>
        <el-select v-model="filters.category" aria-label="公告分类筛选" class="filter-select" data-testid="announcement-category-filter"><el-option label="全部分类" value="all" /><el-option label="社区动态" value="community" /><el-option label="活动信息" value="activity" /><el-option label="安全提示" value="safety" /></el-select>
        <el-select v-model="filters.status" aria-label="公告状态筛选" class="filter-select" data-testid="announcement-status-filter"><el-option label="全部状态" value="all" /><el-option label="已发布" value="published" /><el-option label="草稿" value="draft" /><el-option label="已撤回" value="withdrawn" /></el-select>
      </div>
      <div class="filter-hint"><Globe2 :size="14" /> 公告仅用于信科 Link 校园社区信息展示，不以学校或学院官方身份发布。</div>
    </section>

    <section class="panel-card moderation-table-panel" aria-label="公告列表">
      <div class="moderation-table-title"><h2><Megaphone :size="16" />公告列表</h2><span>共 {{ result.total }} 条服务器记录</span></div>
      <el-table v-loading="loading" :data="result.items" row-key="id" class="moderation-table operations-table announcement-table" empty-text="暂无符合条件的公告" data-testid="announcement-table">
        <el-table-column label="公告标题 / 编号" min-width="245"><template #default="{ row }"><button class="operation-title-cell" type="button" data-testid="preview-announcement" @click="showPreview(row)"><strong>{{ row.title }}</strong><small>编号 {{ row.id }}</small></button></template></el-table-column>
        <el-table-column label="分类" width="120"><template #default="{ row }"><el-tag effect="plain" round>{{ categoryName(row.category) }}</el-tag></template></el-table-column>
        <el-table-column label="展示范围" width="110"><template #default="{ row }"><span class="operation-scope"><Globe2 :size="13" />{{ scopeName(row.scope) }}</span></template></el-table-column>
        <el-table-column label="状态" width="115"><template #default="{ row }"><el-tag :type="row.status === 'published' ? 'success' : row.status === 'withdrawn' ? 'info' : 'warning'" effect="light" round>{{ statusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="最近更新" width="135"><template #default="{ row }"><span class="date-cell"><CalendarDays :size="13" />{{ formatDate(row.updatedAt) }}</span></template></el-table-column>
        <el-table-column label="管理操作" width="242" fixed="right" align="right"><template #default="{ row }"><div class="operation-row-actions"><el-button text type="primary" :aria-label="`预览公告：${row.title}`" data-testid="announcement-preview-action" @click="showPreview(row)"><Eye :size="14" />预览</el-button><el-button text type="primary" :aria-label="`编辑公告：${row.title}`" data-testid="announcement-edit" @click="openEdit(row)"><Pencil :size="14" />编辑</el-button><el-button v-if="row.status === 'published'" text type="warning" :aria-label="`撤回公告：${row.title}`" data-testid="announcement-withdraw" @click="withdraw(row)"><RotateCcw :size="14" />撤回</el-button><el-button v-else text type="success" :aria-label="`发布公告：${row.title}`" data-testid="announcement-publish" @click="publish(row)"><Send :size="14" />发布</el-button><el-button text type="danger" :aria-label="`删除公告：${row.title}`" data-testid="announcement-delete" @click="remove(row)"><Trash2 :size="14" /></el-button></div></template></el-table-column>
      </el-table>
      <div class="table-footer"><span>当前展示 {{ result.items.length }} 条</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50]" :total="result.total" layout="sizes, prev, pager, next" background small data-testid="announcement-pagination" /></div>
    </section>

    <el-drawer v-model="editorVisible" :title="editorId ? '编辑公告' : '新建公告'" direction="rtl" size="min(600px, 52vw)" class="moderation-drawer operations-drawer announcement-editor-drawer" data-testid="announcement-editor-drawer" destroy-on-close>
      <template #header><div class="drawer-heading"><div><span class="drawer-eyebrow">运营管理 / 公告管理</span><h2>{{ editorId ? '编辑公告' : '新建公告' }}</h2></div><el-tag effect="light" round>{{ editorId ? '编辑中' : '未保存' }}</el-tag></div></template>
      <div class="drawer-content operations-form-content">
        <div class="operations-demo-note"><Megaphone :size="15" /><span>公告在信科 Link 校园社区内展示，不代表学校官方通知。</span></div>
        <label class="operations-field"><span>公告标题 <b>*</b></span><el-input v-model="editorForm.title" maxlength="60" show-word-limit placeholder="请输入公告标题" aria-label="公告标题" data-testid="announcement-title" /></label>
        <label class="operations-field"><span>公告正文 <b>*</b></span><el-input v-model="editorForm.body" type="textarea" :rows="8" maxlength="1200" show-word-limit resize="vertical" placeholder="请填写面向校园社区的公告内容" aria-label="公告正文" data-testid="announcement-body" /></label>
        <div class="operations-field-grid">
          <label class="operations-field"><span>公告分类</span><el-select v-model="editorForm.category" aria-label="公告分类" data-testid="announcement-category"><el-option label="社区动态" value="community" /><el-option label="活动信息" value="activity" /><el-option label="安全提示" value="safety" /></el-select></label>
          <label class="operations-field"><span>展示范围</span><el-select v-model="editorForm.scope" aria-label="展示范围" data-testid="announcement-scope"><el-option label="全站展示" value="all" /><el-option label="论坛" value="forum" /></el-select></label>
        </div>
        <div class="operations-demo-note is-muted"><CalendarDays :size="14" /><span>创建、更新与发布历史由服务器记录。</span></div>
        <div class="drawer-actions"><el-button @click="showPreview(editorForm)"><Eye :size="14" />预览</el-button><span class="drawer-actions-spacer" /><el-button data-testid="save-announcement-draft" @click="saveDraft">保存草稿</el-button><el-button type="primary" data-testid="publish-announcement-from-editor" @click="publishFromEditor"><Send :size="14" />确认发布</el-button></div>
      </div>
    </el-drawer>

    <el-dialog v-model="previewVisible" title="公告预览" width="min(620px, 90vw)" class="operation-preview-dialog" data-testid="announcement-preview-dialog">
      <article v-if="previewItem" class="announcement-preview"><div class="announcement-preview-meta"><el-tag effect="plain" round>{{ categoryName(previewItem.category) }}</el-tag><span>{{ scopeName(previewItem.scope) }}展示</span></div><h2>{{ previewItem.title || '公告标题预览' }}</h2><p>{{ previewItem.body || '公告正文预览' }}</p><small>信科 Link 社区公告 · 本地预览</small></article>
      <template #footer><el-button data-testid="close-announcement-preview" @click="previewVisible = false"><X :size="14" />关闭预览</el-button></template>
    </el-dialog>
  </div>
  </el-config-provider>
</template>
