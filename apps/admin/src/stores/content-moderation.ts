import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { adminHttp, ApiError, periodRange, queryString } from '../services/admin-http'
import { adaptPost, adaptComment, adaptReport, adaptUser, adaptHistory, postCategories, type Wire } from '../services/admin-adapters'
import type { AdminPost, AdminComment, AdminReport, AdminUser, QueryPage, PostFilters, CommentFilters, ReportFilters, UserFilters, ModerationStatus, UserRestrictionChannel, ReportResolution } from '../services/admin-models'
export type * from '../services/admin-models'

const emptyPage = <T>(): QueryPage<T> => ({ items: [], total: 0, page: 1, pageSize: 10 })
const idPath = (id: string) => encodeURIComponent(id)
function upsert<T extends { id: string }>(items: T[], next: T[]) { const map = new Map(items.map(item => [item.id, item])); next.forEach(item => map.set(item.id, item)); return [...map.values()] }

export const useContentModerationStore = defineStore('content-moderation', () => {
  const posts = ref<AdminPost[]>([]), comments = ref<AdminComment[]>([]), reports = ref<AdminReport[]>([]), users = ref<AdminUser[]>([])
  const postPage = ref(emptyPage<AdminPost>()), commentPage = ref(emptyPage<AdminComment>()), reportPage = ref(emptyPage<AdminReport>()), userPage = ref(emptyPage<AdminUser>())
  const postStats = ref({ total: 0, visible: 0, hidden: 0, today: 0, pending: 0 })
  const commentStats = ref({ total: 0, visible: 0, hidden: 0, today: 0, interactions: 0 })
  const reportStats = ref({ total: 0, pending: 0, processed: 0, highPriority: 0 })
  const userStats = ref({ total: 0, todayNew: 0, restricted: 0, permanentlyBanned: 0 })
  const restrictedUsers = computed(() => users.value.filter(user => user.status !== 'active'))
  const userContent = ref<Record<string, { posts: AdminPost[]; comments: AdminComment[] }>>({})
  const userReports = ref<Record<string, AdminReport[]>>({})
  const postCommentIds = ref<Record<string, string[]>>({})
  const contextReplyIds = ref<Record<string, string[]>>({})
  let postRequest = 0, commentRequest = 0, reportRequest = 0, userRequest = 0
  let reloadPosts = async () => {}, reloadComments = async () => {}, reloadReports = async () => {}, reloadUsers = async () => {}

  async function loadPosts(filters: PostFilters, page: number, pageSize: number) {
    const sequence = ++postRequest
    reloadPosts = () => loadPosts({ ...filters }, page, pageSize)
    const { period, ...rest } = filters
    const category = Object.keys(postCategories).find(key => postCategories[key] === filters.category)
    const dto = await adminHttp.request<Wire>('/posts' + queryString({ ...rest, category, ...periodRange(period), page, pageSize }))
    if (sequence !== postRequest) return
    const items = dto.items.map(adaptPost)
    posts.value = upsert(posts.value, items); postPage.value = { items, total: dto.total, page: dto.page, pageSize: dto.pageSize }; postStats.value = dto.stats
  }
  async function loadComments(filters: CommentFilters, page: number, pageSize: number) {
    const sequence = ++commentRequest
    reloadComments = () => loadComments({ ...filters }, page, pageSize)
    const dto = await adminHttp.request<Wire>('/comments' + queryString({ ...filters, page, pageSize }))
    if (sequence !== commentRequest) return
    const items = dto.items.map(adaptComment)
    comments.value = upsert(comments.value, items); commentPage.value = { items, total: dto.total, page: dto.page, pageSize: dto.pageSize }; commentStats.value = dto.stats
  }

  async function loadReports(filters: ReportFilters, page: number, pageSize: number) {
    const sequence = ++reportRequest
    reloadReports = () => loadReports({ ...filters }, page, pageSize)
    const { period, ...rest } = filters
    const dto = await adminHttp.request<Wire>('/reports' + queryString({ ...rest, ...periodRange(period), page, pageSize }))
    if (sequence !== reportRequest) return
    const items = dto.items.map(adaptReport)
    reports.value = upsert(reports.value, items); reportPage.value = { items, total: dto.total, page: dto.page, pageSize: dto.pageSize }; reportStats.value = dto.stats
  }
  async function loadUsers(filters: UserFilters, page: number, pageSize: number) {
    const sequence = ++userRequest
    reloadUsers = () => loadUsers({ ...filters }, page, pageSize)
    const dto = await adminHttp.request<Wire>('/users' + queryString({ ...filters, page, pageSize }))
    if (sequence !== userRequest) return
    const items = dto.items.map(adaptUser)
    users.value = upsert(users.value, items); userPage.value = { items, total: dto.total, page: dto.page, pageSize: dto.pageSize }; userStats.value = dto.stats
  }
  const queryPosts = (_filters: PostFilters, _page: number, _pageSize: number) => postPage.value
  const queryComments = (_filters: CommentFilters, _page: number, _pageSize: number) => commentPage.value
  const queryReports = (_filters: ReportFilters, _page: number, _pageSize: number) => reportPage.value
  const queryUsers = (_filters: UserFilters, _page: number, _pageSize: number) => userPage.value
  const postById = (id: string) => posts.value.find(item => item.id === id)
  const commentById = (id: string) => comments.value.find(item => item.id === id)
  const reportById = (id: string) => reports.value.find(item => item.id === id)
  const userById = (id: string) => users.value.find(item => item.id === id)
  const commentsForPost = (id: string) => (postCommentIds.value[id] ?? []).map(commentById).filter((item): item is AdminComment => Boolean(item))
  const visibleCommentCount = (id: string) => postById(id)?.visibleCommentCount ?? 0
  const repliesFor = (id: string) => contextReplyIds.value[id] ? contextReplyIds.value[id].map(commentById).filter((item): item is AdminComment => Boolean(item)) : comments.value.filter(item => item.parentId === id)
  const userActivity = (id: string) => userContent.value[id] ?? { posts: [], comments: [] }
  const reportsForUser = (id: string) => userReports.value[id] ?? []

  async function allPages(path: string): Promise<Wire[]> {
    const items: Wire[] = []
    for (let page = 1; ; page++) {
      const data = await adminHttp.request<Wire>(path + (path.includes('?') ? '&' : '?') + `page=${page}&pageSize=50`)
      items.push(...data.items)
      if (items.length >= data.total || !data.items.length) return items
    }
  }
  async function loadPostDetail(id: string) {
    const dto = await adminHttp.request<Wire>(`/posts/${idPath(id)}`)
    posts.value = upsert(posts.value, [adaptPost(dto.post)])
    const roots = await allPages(`/posts/${idPath(id)}/comments`)
    const flattened = roots.flatMap(root => [root, ...(root.replies ?? [])])
    comments.value = upsert(comments.value, flattened.map(adaptComment)); postCommentIds.value[id] = flattened.map(item => item.id)
  }
  async function loadCommentContext(id: string) {
    let cursor: string | null = null
    const replyIds: string[] = []
    do {
      const dto: Wire = await adminHttp.request<Wire>(`/comments/${idPath(id)}/context` + queryString({ replyCursor: cursor, size: 50 }))
      const rows = [dto.root, dto.selected, ...dto.replies.items].filter(Boolean)
      comments.value = upsert(comments.value, rows.map(adaptComment)); replyIds.push(...dto.replies.items.map((item: Wire) => item.id))
      contextReplyIds.value[dto.root.id] = [...new Set(replyIds)]
      cursor = dto.replies.hasMore ? dto.replies.nextCursor : null
    } while (cursor)
  }

  async function loadReportDetail(id: string) {
    const dto = await adminHttp.request<Wire>(`/reports/${idPath(id)}`)
    reports.value = upsert(reports.value, [adaptReport(dto)])
  }
  async function loadUserDetail(id: string) {
    const base = `/users/${idPath(id)}`
    const [dto, postItems, commentItems, reportItems, history] = await Promise.all([adminHttp.request<Wire>(base), allPages(base + '/content?type=post'), allPages(base + '/content?type=comment'), allPages(base + '/reports'), allPages(base + '/moderation-history')])
    const user = adaptUser(dto.user ?? dto); user.history = history.map(adaptHistory)
    users.value = upsert(users.value, [user]); userContent.value[id] = { posts: postItems.map(adaptPost), comments: commentItems.map(adaptComment) }; userReports.value[id] = reportItems.map(adaptReport)
  }
  function itemsFor(ids: string[], source: { id: string; version: number }[]) {
    return [...new Set(ids)].map(id => {
      const item = source.find(item => item.id === id)
      if (!item || !Number.isInteger(item.version)) throw new ApiError(409, 'VERSION_REQUIRED', '记录版本不可用，请刷新后重试')
      return { id, expectedVersion: item.version }
    })
  }
  async function setPostStatus(ids: string[], targetStatus: ModerationStatus) {
    const result = await adminHttp.request<Wire>('/posts/status', { method: 'POST', body: JSON.stringify({ items: itemsFor(ids, posts.value), targetStatus }) })
    result.items?.forEach((change: Wire) => { const item = postById(change.id); if (item) { item.status = targetStatus; item.version = change.newVersion } })
    await reloadPosts(); return result.changedCount as number
  }
  async function deletePosts(ids: string[]) {
    for (const item of itemsFor(ids, posts.value)) await adminHttp.request(`/posts/${idPath(item.id)}`, { method: 'DELETE', headers: { 'If-Match': `"${item.expectedVersion}"` } })
    posts.value = posts.value.filter(item => !ids.includes(item.id)); await reloadPosts()
  }
  async function setCommentStatus(ids: string[], targetStatus: ModerationStatus) {
    const result = await adminHttp.request<Wire>('/comments/status', { method: 'POST', body: JSON.stringify({ items: itemsFor(ids, comments.value), targetStatus }) })
    result.items?.forEach((change: Wire) => { const item = commentById(change.id); if (item) { item.status = targetStatus; item.version = change.newVersion } })
    await reloadComments(); return result.changedCount as number
  }
  async function deleteComments(ids: string[]) {
    await adminHttp.request('/comments/delete', { method: 'POST', body: JSON.stringify({ items: itemsFor(ids, comments.value), confirmed: true }) }); comments.value = comments.value.filter(item => !ids.includes(item.id) && !ids.includes(item.parentId ?? '')); await reloadComments()
  }


  async function processReport(id: string, action: ReportResolution, reason: string) {
    const report = reportById(id)
    const dto = await adminHttp.request<Wire>(`/reports/${idPath(id)}/resolution`, { method: 'POST', body: JSON.stringify({ action, reason, expectedVersion: report?.version, confirmed: true }) }); reports.value = upsert(reports.value, [adaptReport(dto.report)]); await reloadReports(); return true
  }
  async function userCommand(id: string, suffix: string, body: Wire) {
    const dto = await adminHttp.request<Wire>(`/users/${idPath(id)}/${suffix}`, { method: 'POST', body: JSON.stringify({ ...body, expectedVersion: userById(id)?.version, confirmed: true }) }); users.value = upsert(users.value, [adaptUser(dto)]); await reloadUsers(); await loadUserDetail(id); return true
  }
  const setUserRestriction = (id: string, channel: UserRestrictionChannel, enabled: boolean, reason: string) => userCommand(id, 'restrictions', { channel, enabled, reason })
  const banUser = (id: string, durationDays: number | 'permanent', reason: string) => userCommand(id, 'ban', { mode: durationDays === 'permanent' ? 'permanent' : 'temporary', ...(durationDays === 'permanent' ? {} : { durationDays }), reason })
  const liftUserRestrictions = (id: string, reason: string) => userCommand(id, 'release', { reason })
  function refreshExpiredBans() { /* Effective restrictions are computed by the backend. */ }
  return { posts, comments, reports, users, restrictedUsers, postStats, commentStats, reportStats, userStats, loadPosts, loadComments, loadReports, loadUsers, queryPosts, queryComments, queryReports, queryUsers, postById, commentById, reportById, userById, commentsForPost, visibleCommentCount, repliesFor, userActivity, reportsForUser, loadPostDetail, loadCommentContext, loadReportDetail, loadUserDetail, setPostStatus, deletePosts, setCommentStatus, deleteComments, processReport, setUserRestriction, banUser, liftUserRestrictions, refreshExpiredBans }
})
