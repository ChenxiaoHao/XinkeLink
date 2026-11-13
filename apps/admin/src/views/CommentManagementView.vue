<script setup lang="ts">
import { Heart } from '@lucide/vue'
import { hasSeparateTitle } from '../utils/admin-display'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  Eye, EyeOff, FileText, MessageSquareText, RotateCcw, Search, Trash2, UserRound, X,
} from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import RenewingImage from '../components/content/RenewingImage.vue'
import { useContentModerationStore, type AdminComment, type CommentFilters, type ModerationStatus } from '../stores/content-moderation'

const store = useContentModerationStore()
const filters = reactive<CommentFilters>({ keyword: '', level: 'all', status: 'all', business: 'all' })
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const selectedRows = ref<AdminComment[]>([])
const tableRef = ref<{ clearSelection: () => void } | null>(null)
const detailCommentId = ref<string | null>(null)
const detailVisible = ref(false)
let loadingSequence = 0
const loadError = ref('')

const result = computed(() => store.queryComments(filters, page.value, pageSize.value))
const selectedIds = computed(() => selectedRows.value.map(row => row.id))
const detailComment = computed(() => detailCommentId.value ? store.commentById(detailCommentId.value) : undefined)
const parentComment = computed(() => detailComment.value?.parentId ? store.commentById(detailComment.value.parentId) : undefined)
const rootComment = computed(() => parentComment.value ?? detailComment.value)
const rootReplies = computed(() => rootComment.value ? store.repliesFor(rootComment.value.id) : [])
const linkedPost = computed(() => detailComment.value?.postId ? store.postById(detailComment.value.postId) : undefined)
const metrics = computed(() => [
  { label: '评论总数', value: store.commentStats.total, unit: '条', note: '全局有效记录', icon: MessageSquareText },
  { label: '今日评论', value: store.commentStats.today, unit: '条', note: '按北京时间统计', icon: FileText },
  { label: '互动点赞', value: store.commentStats.interactions, unit: '条', note: '关联一级评论', icon: MessageSquareText },
  { label: '已隐藏', value: store.commentStats.hidden, unit: '条', note: '可恢复展示', icon: EyeOff },
])

async function setLoading() {
  const sequence = ++loadingSequence
  loading.value = true; loadError.value = ''
  try { await store.loadComments({ ...filters }, page.value, pageSize.value) }
  catch (error) { if (sequence === loadingSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (sequence === loadingSequence) loading.value = false }
}
watch(filters, () => { page.value = 1; setLoading() }, { deep: true })
watch(page, setLoading)
watch(pageSize, () => { page.value = 1; setLoading() })
onMounted(setLoading)

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}
function levelLabel(comment: AdminComment) { return comment.parentId ? '二级回复' : '一级评论' }
function statusLabel(status: ModerationStatus) { return status === 'visible' ? '正常展示' : '已隐藏' }
function businessLabel(_comment: AdminComment) { return '论坛帖子' }
function onSelectionChange(rows: AdminComment[]) { selectedRows.value = rows }
async function openContext(comment: AdminComment) { try { await store.loadCommentContext(comment.id); detailCommentId.value = comment.id; detailVisible.value = true } catch (error) { ElMessage.error(errorMessage(error)) } }
function closeContext() { detailCommentId.value = null }
function clearSelection() { selectedRows.value = []; tableRef.value?.clearSelection() }
async function setCommentsStatus(ids: string[], status: ModerationStatus) {
  try {
    const changed = await store.setCommentStatus(ids, status)
    clearSelection()
    ElMessage.success(status === 'hidden' ? `已隐藏 ${changed} 条评论` : `已恢复 ${changed} 条评论`)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function deleteComments(ids: string[], label = '所选评论') {
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(`删除${label}后无法恢复；如包含一级评论，其下的回复也会一并移除。`, '确认删除评论？', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', distinguishCancelAndClose: true })
    await store.deleteComments(ids)
    if (detailCommentId.value && !store.commentById(detailCommentId.value)) detailVisible.value = false
    clearSelection()
    ElMessage.success('评论及关联回复已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <el-config-provider :locale="zhCn">
  <div class="content-management-view comment-management-view" data-testid="comments-view">
    <header class="page-heading-row content-heading">
      <div>
        <div class="page-eyebrow">内容管理 <span aria-hidden="true">/</span> 评论管理</div>
        <h1 class="page-title">评论与互动管理</h1>
        <p class="page-description">查看一级评论、二级回复及其关联内容。数据来自服务器，操作记录保存在服务器。</p>
      </div>
      <span class="demo-badge"><span class="environment-dot" />服务器数据</span>
    </header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <ContentMetricGrid :items="metrics" />

    <section class="panel-card content-filter-panel" aria-label="评论搜索与筛选">
      <div class="comment-filter-row">
        <el-input v-model="filters.keyword" class="moderation-search comment-search" aria-label="搜索评论" placeholder="搜索评论内容、关联帖子或发布者" clearable data-testid="comment-search">
          <template #prefix><Search :size="16" /></template>
        </el-input>
        <el-select v-model="filters.level" aria-label="评论层级筛选" class="filter-select" data-testid="comment-level-filter">
          <el-option label="全部层级" value="all" /><el-option label="一级评论" value="root" /><el-option label="二级回复" value="reply" />
        </el-select>
        <el-select v-model="filters.status" aria-label="评论状态筛选" class="filter-select" data-testid="comment-status-filter">
          <el-option label="全部状态" value="all" /><el-option label="正常展示" value="visible" /><el-option label="已隐藏" value="hidden" />
        </el-select>
        <el-select v-model="filters.business" aria-label="关联业务筛选" class="filter-select" data-testid="comment-business-filter">
          <el-option label="全部业务" value="all" /><el-option label="论坛帖子" value="forum" />
        </el-select>
      </div>
      <div v-if="selectedRows.length" class="bulk-toolbar" data-testid="comment-bulk-toolbar">
        <span>已选择 <strong>{{ selectedRows.length }}</strong> 条</span>
        <el-button size="small" plain data-testid="bulk-hide-comments" @click="setCommentsStatus(selectedIds, 'hidden')"><EyeOff :size="14" />批量隐藏</el-button>
        <el-button size="small" plain data-testid="bulk-restore-comments" @click="setCommentsStatus(selectedIds, 'visible')"><RotateCcw :size="14" />批量恢复</el-button>
        <el-button size="small" type="danger" plain data-testid="bulk-delete-comments" @click="deleteComments(selectedIds)"><Trash2 :size="14" />批量删除</el-button>
      </div>
      <div v-else class="filter-hint"><MessageSquareText :size="14" /> 支持按评论层级、内容状态及关联业务筛选</div>
    </section>

    <section class="panel-card moderation-table-panel" aria-label="评论列表">
      <div class="moderation-table-title"><h2><MessageSquareText :size="16" />评论列表</h2><span>共 {{ result.total }} 条评论</span></div>
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="result.items"
        row-key="id"
        class="moderation-table comment-table"
        empty-text="暂无符合条件的评论"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column label="评论内容与层级" min-width="330">
          <template #default="{ row }">
            <button class="comment-content-cell" type="button" :aria-label="`查看上下文：${row.content}`" @click="openContext(row)">
              <span class="comment-cell-labels"><el-tag size="small" effect="plain" :type="row.parentId ? 'info' : ''">{{ levelLabel(row) }}</el-tag><span class="business-chip">{{ businessLabel(row) }}</span></span>
              <span class="comment-cell-text">{{ row.content }}</span>
              <span class="comment-linked-title"><span v-if="row.parentId">回复 {{ store.commentById(row.parentId)?.authorName ?? '已移除评论' }} · </span>{{ row.linkedTitle }}</span>
            </button>
          </template>
        </el-table-column>
        <el-table-column label="发布者" width="150">
          <template #default="{ row }"><div class="publisher-cell"><RenewingImage avatar :size="28" :src="row.authorAvatar"><UserRound :size="15" /></RenewingImage><div><strong>{{ row.authorName }}</strong><small>{{ row.authorId }}</small></div></div></template>
        </el-table-column>
        <el-table-column label="点赞" width="76" align="center"><template #default="{ row }"><span class="likes-cell"><Heart :size="15" /> {{ row.likes }}</span></template></el-table-column>
        <el-table-column label="状态" width="104" align="center"><template #default="{ row }"><el-tag :type="row.status === 'visible' ? 'success' : 'info'" effect="light" round>{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="发布时间" width="118" align="center"><template #default="{ row }"><span class="date-cell">{{ formatDate(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="管理操作" width="142" fixed="right" align="center">
          <template #default="{ row }"><div class="table-actions">
            <el-tooltip content="查看上下文"><el-button text circle aria-label="查看上下文" data-testid="open-comment-context" @click="openContext(row)"><Eye :size="16" /></el-button></el-tooltip>
            <el-tooltip :content="row.status === 'visible' ? '隐藏评论' : '恢复评论'"><el-button text circle :aria-label="row.status === 'visible' ? '隐藏评论' : '恢复评论'" @click="setCommentsStatus([row.id], row.status === 'visible' ? 'hidden' : 'visible')"><EyeOff v-if="row.status === 'visible'" :size="15" /><RotateCcw v-else :size="15" /></el-button></el-tooltip>
            <el-tooltip content="删除评论"><el-button text circle type="danger" aria-label="删除评论" @click="deleteComments([row.id], '这条评论')"><Trash2 :size="15" /></el-button></el-tooltip>
          </div></template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <span>当前展示 {{ result.items.length }} 条</span>
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20]" :total="result.total" layout="sizes, prev, pager, next" background small data-testid="comment-pagination" />
      </div>
    </section>

    <el-drawer v-model="detailVisible" title="评论上下文" direction="rtl" size="min(680px, 52vw)" class="moderation-drawer" data-testid="comment-context-drawer" @closed="closeContext">
      <template #header><div class="drawer-heading"><div><span class="drawer-eyebrow">评论关系与关联内容</span><h2>评论上下文</h2></div><el-tag v-if="detailComment" :type="detailComment.status === 'visible' ? 'success' : 'info'" effect="light" round>{{ statusLabel(detailComment.status) }}</el-tag></div></template>
      <div v-if="detailComment" class="drawer-content comment-context-content">
        <div class="detail-id-line"><span class="content-id">{{ detailComment.id }}</span><span>{{ levelLabel(detailComment) }}</span><span>{{ businessLabel(detailComment) }}</span><span>{{ formatDate(detailComment.createdAt) }}</span></div>
        <div class="context-panel linked-content-card">
          <div class="detail-section-title"><FileText :size="15" />关联内容</div>
          <template v-if="linkedPost">
            <div v-if="hasSeparateTitle(linkedPost)" class="linked-post-title">{{ linkedPost.title }}</div>
            <p>{{ linkedPost.content }}</p>
            <div v-if="linkedPost.images.length" class="context-image-row"><RenewingImage v-for="(image, index) in linkedPost.images.slice(0, 3)" :key="image + index" :src="image" :preview-src-list="linkedPost.images" :initial-index="index" fit="cover" class="context-image" /></div>
            <div class="linked-post-meta"><span class="category-chip">{{ linkedPost.category }}</span><span>{{ linkedPost.authorName }} · {{ formatDate(linkedPost.createdAt) }}</span><el-tag size="small" :type="linkedPost.status === 'visible' ? 'success' : 'info'">{{ linkedPost.status === 'visible' ? '正常展示' : '已隐藏' }}</el-tag></div>
          </template>
          <template v-else><div class="linked-post-title">{{ detailComment.linkedTitle }}</div><p>关联帖子暂不可用。</p><span class="business-chip">论坛帖子</span></template>
        </div>

        <div class="detail-section thread-section">
          <div class="detail-section-title"><MessageSquareText :size="15" />评论关系 <span>{{ rootReplies.length + (rootComment ? 1 : 0) }} 条</span></div>
          <article v-if="rootComment" class="context-thread">
            <div class="context-comment" :class="{ 'is-current': rootComment.id === detailComment.id }">
              <div class="context-comment-head"><RenewingImage avatar :size="32" :src="rootComment.authorAvatar"><UserRound :size="16" /></RenewingImage><div class="context-author"><strong>{{ rootComment.authorName }}</strong><small>{{ rootComment.authorId }} · {{ formatDate(rootComment.createdAt) }}</small></div><el-tag v-if="rootComment.id === detailComment.id" size="small" type="primary">当前查看</el-tag><el-tag v-else size="small" effect="plain">一级评论</el-tag>
            </div><p class="context-comment-body">{{ rootComment.content }}</p><div class="context-comment-foot"><Heart :size="14" /> {{ rootComment.likes }} 点赞 <el-tag v-if="rootComment.status === 'hidden'" size="small" type="info">已隐藏</el-tag></div>
            </div>
            <div v-for="reply in rootReplies" :key="reply.id" class="context-comment reply-context" :class="{ 'is-current': reply.id === detailComment.id }">
              <div class="context-comment-head"><span class="reply-branch">↳</span><RenewingImage avatar :size="28" :src="reply.authorAvatar"><UserRound :size="14" /></RenewingImage><div class="context-author"><strong>{{ reply.authorName }} <small>回复 {{ rootComment.authorName }}</small></strong><small>{{ reply.authorId }} · {{ formatDate(reply.createdAt) }}</small></div><el-tag v-if="reply.id === detailComment.id" size="small" type="primary">当前查看</el-tag><el-tag v-else size="small" effect="plain">二级回复</el-tag></div>
              <p class="context-comment-body">{{ reply.content }}</p><div class="context-comment-foot"><Heart :size="14" /> {{ reply.likes }} 点赞 <el-tag v-if="reply.status === 'hidden'" size="small" type="info">已隐藏</el-tag></div>
            </div>
          </article>
          <el-empty v-else description="评论关系已不存在" :image-size="64" />
        </div>
        <div class="drawer-actions"><el-button v-if="detailComment.status === 'visible'" data-testid="context-hide-comment" @click="setCommentsStatus([detailComment.id], 'hidden')"><EyeOff :size="15" />隐藏评论</el-button><el-button v-else type="primary" plain data-testid="context-restore-comment" @click="setCommentsStatus([detailComment.id], 'visible')"><RotateCcw :size="15" />恢复展示</el-button><el-button type="danger" plain data-testid="context-delete-comment" @click="deleteComments([detailComment.id], '这条评论')"><Trash2 :size="15" />删除评论</el-button><el-button aria-label="关闭评论上下文" @click="detailVisible = false"><X :size="15" />关闭</el-button></div>
      </div>
      <el-empty v-else description="评论已删除" />
    </el-drawer>
  </div>
  </el-config-provider>
</template>
