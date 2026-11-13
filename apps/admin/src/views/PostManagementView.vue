<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  CalendarDays, Eye, Heart, FileText, Image as ImageIcon, MessageSquareText, RotateCcw, Search, ShieldAlert,
  Trash2, UserRound, EyeOff, X,
} from '@lucide/vue'
import ContentMetricGrid from '../components/content/ContentMetricGrid.vue'
import RenewingImage from '../components/content/RenewingImage.vue'
import { hasSeparateTitle } from '../utils/admin-display'
import { useContentModerationStore, type AdminPost, type ModerationStatus, type PostFilters } from '../stores/content-moderation'

const store = useContentModerationStore()
const filters = reactive<PostFilters>({ keyword: '', category: 'all', status: 'all', period: 'all' })
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const tableRef = ref<{ clearSelection: () => void } | null>(null)
const selectedRows = ref<AdminPost[]>([])
const detailPostId = ref<string | null>(null)
const detailVisible = ref(false)
let loadingSequence = 0
const loadError = ref('')

const result = computed(() => store.queryPosts(filters, page.value, pageSize.value))
const selectedIds = computed(() => selectedRows.value.map(row => row.id))
const detailPost = computed(() => detailPostId.value ? store.postById(detailPostId.value) : undefined)
const detailComments = computed(() => detailPostId.value ? store.commentsForPost(detailPostId.value) : [])
const rootComments = computed(() => detailComments.value.filter(comment => comment.parentId === null))
const metrics = computed(() => [
  { label: '帖子总数', value: store.postStats.total, unit: '篇', note: '全局有效记录', icon: FileText },
  { label: '今日新增', value: store.postStats.today, unit: '篇', note: '按北京时间统计', icon: CalendarDays },
  { label: '待复核', value: store.postStats.pending, unit: '篇', note: '待复核队列', icon: ShieldAlert, danger: store.postStats.pending > 0 },
  { label: '已隐藏', value: store.postStats.hidden, unit: '篇', note: '可恢复展示', icon: EyeOff },
])

async function setLoading() {
  const sequence = ++loadingSequence
  loading.value = true; loadError.value = ''
  try { await store.loadPosts({ ...filters }, page.value, pageSize.value) }
  catch (error) { if (sequence === loadingSequence) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } }
  finally { if (sequence === loadingSequence) loading.value = false }
}

watch(filters, () => { page.value = 1; setLoading() }, { deep: true })
watch(page, setLoading)
watch(pageSize, () => { page.value = 1; setLoading() })
onMounted(setLoading)

function onSelectionChange(rows: AdminPost[]) { selectedRows.value = rows }
async function openDetails(post: AdminPost) { try { await store.loadPostDetail(post.id); detailPostId.value = post.id; detailVisible.value = true } catch (error) { ElMessage.error(errorMessage(error)) } }
function closeDetails() { detailPostId.value = null }
function statusLabel(status: ModerationStatus) { return status === 'visible' ? '正常展示' : '已隐藏' }
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value))
}
async function setPostsStatus(ids: string[], status: ModerationStatus) {
  try {
    const changed = await store.setPostStatus(ids, status)
    selectedRows.value = []
    tableRef.value?.clearSelection()
    ElMessage.success(status === 'hidden' ? `已隐藏 ${changed} 篇帖子` : `已恢复 ${changed} 篇帖子`)
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function deletePost(post: AdminPost) {
  try {
    await ElMessageBox.confirm(`删除后将同时移除「${post.title}」下的关联评论，且无法恢复。`, '确认删除帖子？', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消', distinguishCancelAndClose: true })
    await store.deletePosts([post.id])
    if (detailPostId.value === post.id) detailVisible.value = false
    selectedRows.value = selectedRows.value.filter(row => row.id !== post.id)
    tableRef.value?.clearSelection()
    ElMessage.success('帖子及关联评论已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}
function bulkStatus(status: ModerationStatus) {
  if (!selectedIds.value.length) return
  setPostsStatus(selectedIds.value, status)
}
</script>

<template>
  <el-config-provider :locale="zhCn">
  <div class="content-management-view post-management-view" data-testid="posts-view">
    <header class="page-heading-row content-heading">
      <div>
        <div class="page-eyebrow">内容管理 <span aria-hidden="true">/</span> 帖子管理</div>
        <h1 class="page-title">帖子综合管理</h1>
        <p class="page-description">查看校园动态、处理内容状态与关联评论。数据来自服务器，操作记录保存在服务器。</p>
      </div>
      <span class="demo-badge"><span class="environment-dot" />服务器数据</span>
    </header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <ContentMetricGrid :items="metrics" />

    <section class="panel-card content-filter-panel" aria-label="帖子搜索与筛选">
      <div class="post-filter-row">
        <el-input v-model="filters.keyword" class="moderation-search" aria-label="搜索帖子" placeholder="搜索帖子标题、正文或发布者" clearable data-testid="post-search">
          <template #prefix><Search :size="16" /></template>
        </el-input>
        <el-select v-model="filters.category" aria-label="帖子分类筛选" class="filter-select category-select" data-testid="post-category-filter">
          <el-option label="全部分类" value="all" />
          <el-option label="校园生活" value="校园生活" />
          <el-option label="失物招领" value="失物招领" />
          <el-option label="学习交流" value="学习交流" />
          <el-option label="校园互助" value="校园互助" />
        </el-select>
        <el-select v-model="filters.status" aria-label="帖子状态筛选" class="filter-select" data-testid="post-status-filter">
          <el-option label="全部状态" value="all" />
          <el-option label="正常展示" value="visible" />
          <el-option label="已隐藏" value="hidden" />
        </el-select>
        <el-select v-model="filters.period" aria-label="帖子时间范围筛选" class="filter-select" data-testid="post-period-filter">
          <el-option label="全部时间" value="all" />
          <el-option label="近 7 天" value="7d" />
          <el-option label="近 30 天" value="30d" />
        </el-select>
      </div>
      <div v-if="selectedRows.length" class="bulk-toolbar" data-testid="post-bulk-toolbar">
        <span>已选择 <strong>{{ selectedRows.length }}</strong> 篇</span>
        <el-button size="small" plain data-testid="bulk-hide-posts" @click="bulkStatus('hidden')"><EyeOff :size="14" />批量隐藏</el-button>
        <el-button size="small" plain data-testid="bulk-restore-posts" @click="bulkStatus('visible')"><RotateCcw :size="14" />批量恢复</el-button>
      </div>
      <div v-else class="filter-hint"><FileText :size="14" /> 可勾选多条帖子进行批量管理</div>
    </section>

    <section class="panel-card moderation-table-panel" aria-label="帖子列表">
      <div class="moderation-table-title"><h2><FileText :size="16" />帖子列表</h2><span>共 {{ result.total }} 篇帖子</span></div>
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="result.items"
        row-key="id"
        class="moderation-table post-table"
        empty-text="暂无符合条件的帖子"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column label="帖子内容" min-width="320">
          <template #default="{ row }">
            <button class="post-title-cell" type="button" :aria-label="`查看帖子：${row.title}`" @click="openDetails(row)">
              <span v-if="hasSeparateTitle(row)" class="post-title-text">{{ row.title }}</span>
              <span class="post-excerpt" :class="{ 'is-primary': !hasSeparateTitle(row) }">{{ row.content }}</span>
              <span class="post-cell-meta"><span class="category-chip">{{ row.category }}</span><span v-if="row.reviewState === 'pending'" class="review-chip">待复核</span></span>
            </button>
          </template>
        </el-table-column>
        <el-table-column label="发布者" width="142">
          <template #default="{ row }"><div class="publisher-cell"><RenewingImage avatar :size="28" :src="row.authorAvatar"><UserRound :size="15" /></RenewingImage><div><strong>{{ row.authorName }}</strong><small>{{ row.authorId }}</small></div></div></template>
        </el-table-column>
        <el-table-column label="互动" width="116" align="center">
          <template #default="{ row }"><span class="interaction-cell"><span title="点赞"><Heart :size="15" />{{ row.likes }}</span><span title="评论"><MessageSquareText :size="15" />{{ store.visibleCommentCount(row.id) }}</span></span></template>
        </el-table-column>
        <el-table-column label="状态" width="108" align="center">
          <template #default="{ row }"><el-tag :type="row.status === 'visible' ? 'success' : 'info'" effect="light" round>{{ statusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="发布时间" width="118" align="center">
          <template #default="{ row }"><span class="date-cell">{{ formatDate(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="管理操作" width="146" fixed="right" align="center">
          <template #default="{ row }">
            <div class="table-actions">
              <el-tooltip content="查看详情"><el-button text circle aria-label="查看详情" data-testid="open-post-detail" @click="openDetails(row)"><Eye :size="16" /></el-button></el-tooltip>
              <el-tooltip :content="row.status === 'visible' ? '隐藏帖子' : '恢复帖子'"><el-button text circle :aria-label="row.status === 'visible' ? '隐藏帖子' : '恢复帖子'" @click="setPostsStatus([row.id], row.status === 'visible' ? 'hidden' : 'visible')"><EyeOff v-if="row.status === 'visible'" :size="15" /><RotateCcw v-else :size="15" /></el-button></el-tooltip>
              <el-tooltip content="删除帖子"><el-button text circle type="danger" aria-label="删除帖子" @click="deletePost(row)"><Trash2 :size="15" /></el-button></el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <span>当前展示 {{ result.items.length }} 条</span>
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20]" :total="result.total" layout="sizes, prev, pager, next" background small data-testid="post-pagination" />
      </div>
    </section>

    <el-drawer v-model="detailVisible" title="帖子详情" direction="rtl" size="min(680px, 52vw)" class="moderation-drawer" data-testid="post-detail-drawer" @closed="closeDetails">
      <template #header><div class="drawer-heading"><div><span class="drawer-eyebrow">帖子内容与互动</span><h2>帖子详情</h2></div><el-tag v-if="detailPost" :type="detailPost.status === 'visible' ? 'success' : 'info'" effect="light" round>{{ statusLabel(detailPost.status) }}</el-tag></div></template>
      <div v-if="detailPost" class="drawer-content post-detail-content">
        <div class="detail-id-line"><span class="content-id">{{ detailPost.id }}</span><span>{{ detailPost.category }}</span><span>{{ formatDate(detailPost.createdAt) }}</span></div>
        <div class="detail-author"><RenewingImage avatar :size="42" :src="detailPost.authorAvatar"><UserRound :size="20" /></RenewingImage><div><strong>{{ detailPost.authorName }}</strong><small>用户 ID：{{ detailPost.authorId }} · 用户自填资料</small></div></div>
        <h3 v-if="hasSeparateTitle(detailPost)" class="detail-post-title">{{ detailPost.title }}</h3>
        <p class="detail-post-body">{{ detailPost.content }}</p>
        <div v-if="detailPost.images.length" class="detail-section">
          <div class="detail-section-title"><ImageIcon :size="15" />帖子图片 <span>{{ detailPost.images.length }} 张</span></div>
          <div class="post-image-grid" :class="`image-count-${Math.min(detailPost.images.length, 4)}`">
            <RenewingImage v-for="(image, index) in detailPost.images" :key="`${detailPost.id}-${index}`" :src="image" :preview-src-list="detailPost.images" :initial-index="index" fit="cover" class="post-detail-image" :alt="`帖子图片 ${index + 1}`" />
          </div>
        </div>
        <div class="detail-stats-strip"><span><Eye :size="14" /> — 次浏览（暂未采集）</span><span><MessageSquareText :size="14" /> {{ store.visibleCommentCount(detailPost.id) }} 条可见评论</span><span>♡ {{ detailPost.likes }} 赞</span></div>
        <div class="detail-section comments-in-post">
          <div class="detail-section-title"><MessageSquareText :size="15" />评论与回复 <span>{{ detailComments.length }} 条</span></div>
          <div v-if="rootComments.length" class="post-comment-list">
            <article v-for="comment in rootComments" :key="comment.id" class="post-comment-thread">
              <div class="comment-line"><RenewingImage avatar :size="26" :src="comment.authorAvatar"><UserRound :size="13" /></RenewingImage><div class="comment-copy"><div class="comment-who"><strong>{{ comment.authorName }}</strong><time>{{ formatDate(comment.createdAt) }}</time><el-tag v-if="comment.status === 'hidden'" size="small" type="info">已隐藏</el-tag></div><p>{{ comment.content }}</p></div></div>
              <div v-for="reply in store.repliesFor(comment.id)" :key="reply.id" class="reply-line"><span class="reply-branch">↳</span><div><strong>{{ reply.authorName }} 回复</strong><p>{{ reply.content }}</p></div><el-tag v-if="reply.status === 'hidden'" size="small" type="info">已隐藏</el-tag></div>
            </article>
          </div>
          <el-empty v-else description="暂无评论" :image-size="64" />
        </div>
        <div class="drawer-actions"><el-button v-if="detailPost.status === 'visible'" data-testid="detail-hide-post" @click="setPostsStatus([detailPost.id], 'hidden')"><EyeOff :size="15" />隐藏帖子</el-button><el-button v-else type="primary" plain data-testid="detail-restore-post" @click="setPostsStatus([detailPost.id], 'visible')"><RotateCcw :size="15" />恢复展示</el-button><el-button type="danger" plain data-testid="detail-delete-post" @click="deletePost(detailPost)"><Trash2 :size="15" />删除帖子</el-button><el-button aria-label="关闭帖子详情" @click="detailVisible = false"><X :size="15" />关闭</el-button></div>
      </div>
      <el-empty v-else description="该帖子已删除" />
    </el-drawer>
  </div>
  </el-config-provider>
</template>
