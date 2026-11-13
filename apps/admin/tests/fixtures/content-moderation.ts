import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useAdminAuditStore, type AuditEventInput, type AuditOutcome } from './admin-audit'
import { recordAdminAuditSafely } from '../../src/utils/admin-audit'
import campusImage from '../../src/assets/moderation/campus.jpg'
import libraryImage from '../../src/assets/moderation/library.jpg'
import deskImage from '../../src/assets/moderation/desk.jpg'
import linAvatar from '../../src/assets/moderation/avatar-lin.jpg'
import zhouAvatar from '../../src/assets/moderation/avatar-zhou.jpg'

export type ModerationStatus = 'visible' | 'hidden'
export type PostCategory = '校园生活' | '失物招领' | '学习交流' | '校园互助'
export type PostPeriod = 'all' | '7d' | '30d'
export type CommentLevel = 'all' | 'root' | 'reply'
export type ModeratedBusiness = 'all' | 'forum'

export interface AdminPost {
  id: string
  title: string
  content: string
  category: PostCategory
  status: ModerationStatus
  reviewState: 'normal' | 'pending'
  createdAt: string
  authorName: string
  authorId: string
  authorAvatar: string
  images: string[]
  likes: number
}

export interface AdminComment {
  id: string
  postId: string | null
  business: 'forum'
  linkedTitle: string
  authorName: string
  authorId: string
  authorAvatar: string
  content: string
  createdAt: string
  status: ModerationStatus
  parentId: string | null
  likes: number
}

export type ReportType = 'post' | 'comment' | 'user' | 'message'
export type ReportPriority = 'high' | 'medium' | 'low'
export type ReportStatus = 'pending' | 'processed'
export type ReportResolution = 'reject' | 'hide_post' | 'hide_comment' | 'restrict_user' | 'warn_user' | 'hide_message'


export interface ReportHistoryEntry {
  action: ReportResolution
  reason: string
  processedAt: string
  operator: string
}

export interface AdminReport {
  id: string
  type: ReportType
  priority: ReportPriority
  status: ReportStatus
  reason: string
  description: string
  createdAt: string
  reporterName: string
  reporterId: string
  targetId: string
  targetName: string
  targetUserId: string
  targetTitle: string
  evidence: string[]
  resolution: ReportResolution | null
  resolutionReason: string
  processedAt: string | null
  history: ReportHistoryEntry[]
}

export type UserRestrictionChannel = 'publish' | 'comment' | 'message'
export type UserStatus = 'active' | 'restricted' | 'banned' | 'permanently_banned'
export type UserModerationAction = 'restrict_publish' | 'restrict_comment' | 'restrict_message' | 'release_publish' | 'release_comment' | 'release_message' | 'lift_restrictions' | 'temporary_ban' | 'temporary_ban_expired' | 'permanent_ban' | 'report_restriction' | 'report_warning'

export interface UserModerationHistoryEntry {
  action: UserModerationAction
  reason: string
  createdAt: string
  operator: string
  source: 'user_management' | 'report'
}

export interface AdminUser {
  id: string
  name: string
  avatar: string
  school: string
  major: string
  className: string
  enrollmentYear: string
  joinedAt: string
  lastActiveAt: string
  status: UserStatus
  restrictions: Record<UserRestrictionChannel, boolean>
  banUntil: string | null
  history: UserModerationHistoryEntry[]
}

export interface QueryPage<T> { items: T[]; total: number; page: number; pageSize: number }
export interface PostFilters { keyword?: string; category?: PostCategory | 'all'; status?: ModerationStatus | 'all'; period?: PostPeriod }
export interface CommentFilters { keyword?: string; level?: CommentLevel; status?: ModerationStatus | 'all'; business?: ModeratedBusiness }
export interface ReportFilters { keyword?: string; type?: ReportType | 'all'; priority?: ReportPriority | 'all'; status?: ReportStatus | 'all'; period?: 'all' | '7d' | '30d' }
export interface UserFilters { keyword?: string; status?: UserStatus | 'all'; restriction?: UserRestrictionChannel | 'all' }

const ago = (days: number, hour = 10) => {
  const value = new Date()
  value.setDate(value.getDate() - days)
  value.setHours(hour, 20, 0, 0)
  return value.toISOString()
}

const initialPosts: AdminPost[] = [
  { id: 'P-2026102301', title: '图书馆东区靠窗座位的晚霞，今天也很温柔', content: '今天在图书馆五楼靠东窗靠窗拍到的晚霞，天边从浅粉色渐变成柔和的雾蓝。微风吹过窗帘真的很治愈，期末复习的同学累了记得抬头看看风景。', category: '学习交流', status: 'visible', reviewState: 'normal', createdAt: ago(0, 9), authorName: '林知夏', authorId: '2022****312', authorAvatar: zhouAvatar, images: [campusImage, libraryImage, deskImage, campusImage], likes: 28 },
  { id: 'P-2026102208', title: '期末复习资料整理好了，分享给大家', content: '把本学期重点内容按章节整理了一遍，欢迎需要的同学一起交流复习方法。图书馆三楼自习区也很安静，大家加油。', category: '学习交流', status: 'visible', reviewState: 'normal', createdAt: ago(1, 19), authorName: '大三计科阿杰', authorId: '2021****884', authorAvatar: linAvatar, images: [deskImage, libraryImage], likes: 142 },
  { id: 'P-2026102205', title: '在教学楼 C 栋捡到一张校园卡', content: '刚才在 C 栋 302 阶梯教室靠门位置捡到一张校园卡，已交至一楼值班室，请失主核对信息后领取。', category: '失物招领', status: 'visible', reviewState: 'pending', createdAt: ago(1, 15), authorName: '匿名同学', authorId: '匿名', authorAvatar: '', images: [campusImage], likes: 8 },
  { id: 'P-2026102103', title: '周末校园绿道散步，有一起的吗', content: '天气不错，打算周六下午沿校园绿道走一圈，顺便拍些照片。有想一起散步的同学可以评论区交流。', category: '校园生活', status: 'visible', reviewState: 'normal', createdAt: ago(2, 11), authorName: '苏小禾', authorId: '2023****117', authorAvatar: zhouAvatar, images: [campusImage, libraryImage], likes: 36 },
  { id: 'P-2026102006', title: '求推荐适合新手的 Python 学习资料', content: '刚开始接触 Python，想找一些适合初学者的资料和练习项目。希望大家分享一下学习路线，谢谢！', category: '学习交流', status: 'hidden', reviewState: 'normal', createdAt: ago(3, 14), authorName: '小周同学', authorId: '2024****921', authorAvatar: linAvatar, images: [], likes: 13 },
  { id: 'P-2026101904', title: '校园北门附近有没有靠谱的打印店', content: '需要打印课程报告和几份资料，想问下大家北门附近哪家打印店价格合适、装订也比较快。', category: '校园互助', status: 'visible', reviewState: 'normal', createdAt: ago(4, 16), authorName: '周同学', authorId: '2022****631', authorAvatar: zhouAvatar, images: [], likes: 4 },
  { id: 'P-2026101802', title: '图书馆三楼今天开放到几点？', content: '晚上想去图书馆自习，想确认一下三楼阅览区周末闭馆时间有没有调整。知道的同学麻烦告诉我一下。', category: '学习交流', status: 'visible', reviewState: 'normal', createdAt: ago(5, 8), authorName: '李同学', authorId: '2023****204', authorAvatar: linAvatar, images: [libraryImage], likes: 6 },
  { id: 'P-2026101709', title: '在操场看到了很漂亮的日落', content: '傍晚操场的风和晚霞都刚刚好，忙碌的一天也算有了一个温柔的收尾。', category: '校园生活', status: 'visible', reviewState: 'normal', createdAt: ago(6, 18), authorName: '林知夏', authorId: '2022****312', authorAvatar: zhouAvatar, images: [campusImage], likes: 24 },
  { id: 'P-2026101602', title: '求问软件工程课程设计组队', content: '正在找课程设计队友，方向可以一起商量，希望大家按时沟通、共同完成。', category: '校园互助', status: 'visible', reviewState: 'pending', createdAt: ago(7, 13), authorName: '匿名同学', authorId: '匿名', authorAvatar: '', images: [], likes: 2 },
  { id: 'P-2026101401', title: '失物招领：教学楼门口的蓝色水杯', content: '今天中午在教学楼门口拾到一个蓝色水杯，放在教学楼服务台，有需要的同学可核对后领取。', category: '失物招领', status: 'visible', reviewState: 'normal', createdAt: ago(9, 12), authorName: '陈同学', authorId: '2022****510', authorAvatar: linAvatar, images: [deskImage], likes: 3 },
  { id: 'P-2026101207', title: '校园里适合晨读的安静角落', content: '最近想培养晨读习惯，除了图书馆还有哪些人少、采光好的位置？欢迎推荐。', category: '学习交流', status: 'hidden', reviewState: 'normal', createdAt: ago(11, 7), authorName: '王同学', authorId: '2024****183', authorAvatar: zhouAvatar, images: [libraryImage], likes: 9 },
  { id: 'P-2026101004', title: '毕业季整理的一些校园照片', content: '把过去几年拍下的校园角落整理了一下，记录下熟悉的季节和路。', category: '校园生活', status: 'visible', reviewState: 'normal', createdAt: ago(13, 20), authorName: '苏小禾', authorId: '2023****117', authorAvatar: zhouAvatar, images: [campusImage, deskImage, libraryImage], likes: 58 },
]

const initialComments: AdminComment[] = [
  { id: 'C-3101', postId: 'P-2026102301', business: 'forum', linkedTitle: initialPosts[0].title, authorName: '周同学', authorId: '2022****702', authorAvatar: linAvatar, content: '晚霞真的很美，图书馆靠窗的位置也很适合复习。', createdAt: ago(0, 9), status: 'visible', parentId: null, likes: 14 },
  { id: 'C-3102', postId: 'P-2026102301', business: 'forum', linkedTitle: initialPosts[0].title, authorName: '林知夏', authorId: '2022****312', authorAvatar: zhouAvatar, content: '谢谢喜欢，傍晚的光线刚好。', createdAt: ago(0, 9), status: 'visible', parentId: 'C-3101', likes: 4 },
  { id: 'C-3103', postId: 'P-2026102208', business: 'forum', linkedTitle: initialPosts[1].title, authorName: '苏小禾', authorId: '2023****117', authorAvatar: zhouAvatar, content: '复习资料可以发一份吗？最近正好在整理笔记。', createdAt: ago(1, 19), status: 'visible', parentId: null, likes: 3 },
  { id: 'C-3104', postId: 'P-2026102208', business: 'forum', linkedTitle: initialPosts[1].title, authorName: '大三计科阿杰', authorId: '2021****884', authorAvatar: linAvatar, content: '可以的，晚点整理好发你。', createdAt: ago(1, 20), status: 'visible', parentId: 'C-3103', likes: 1 },
  { id: 'C-3105', postId: 'P-2026102205', business: 'forum', linkedTitle: initialPosts[2].title, authorName: '陈同学', authorId: '2022****510', authorAvatar: linAvatar, content: '感谢，校园卡已经领回了。', createdAt: ago(1, 16), status: 'visible', parentId: null, likes: 2 },
  { id: 'C-3106', postId: 'P-2026102103', business: 'forum', linkedTitle: initialPosts[3].title, authorName: '王同学', authorId: '2024****183', authorAvatar: zhouAvatar, content: '周日下午可以吗？我也想去走走。', createdAt: ago(2, 12), status: 'hidden', parentId: null, likes: 2 },
  { id: 'C-3107', postId: 'P-2026102103', business: 'forum', linkedTitle: initialPosts[3].title, authorName: '苏小禾', authorId: '2023****117', authorAvatar: zhouAvatar, content: '可以，周日下午三点北门见。', createdAt: ago(2, 12), status: 'visible', parentId: 'C-3106', likes: 1 },
  { id: 'C-3110', postId: 'P-2026101904', business: 'forum', linkedTitle: initialPosts[5].title, authorName: '赵同学', authorId: '2021****035', authorAvatar: linAvatar, content: '北门向东那家打印店速度挺快，可以去看看。', createdAt: ago(4, 17), status: 'visible', parentId: null, likes: 5 },
  { id: 'C-3111', postId: 'P-2026101802', business: 'forum', linkedTitle: initialPosts[6].title, authorName: '匿名同学', authorId: '匿名', authorAvatar: '', content: '周末应该是晚上十点闭馆。', createdAt: ago(5, 9), status: 'visible', parentId: null, likes: 1 },
  { id: 'C-3112', postId: 'P-2026101709', business: 'forum', linkedTitle: initialPosts[7].title, authorName: '周同学', authorId: '2022****702', authorAvatar: linAvatar, content: '操场日落确实很漂亮。', createdAt: ago(6, 18), status: 'visible', parentId: null, likes: 7 },
  { id: 'C-3113', postId: 'P-2026101602', business: 'forum', linkedTitle: initialPosts[8].title, authorName: '王同学', authorId: '2024****183', authorAvatar: zhouAvatar, content: '我也在找队友，方便一起交流下课程要求。', createdAt: ago(7, 14), status: 'visible', parentId: null, likes: 0 },
  { id: 'C-3114', postId: 'P-2026101602', business: 'forum', linkedTitle: initialPosts[8].title, authorName: '匿名同学', authorId: '匿名', authorAvatar: '', content: '可以先把分工和时间安排讨论清楚。', createdAt: ago(7, 14), status: 'visible', parentId: 'C-3113', likes: 0 },
  { id: 'C-3115', postId: 'P-2026101401', business: 'forum', linkedTitle: initialPosts[9].title, authorName: '陈同学', authorId: '2022****510', authorAvatar: linAvatar, content: '水杯已经在服务台啦。', createdAt: ago(9, 13), status: 'visible', parentId: null, likes: 1 },
  { id: 'C-3116', postId: 'P-2026101207', business: 'forum', linkedTitle: initialPosts[10].title, authorName: '林知夏', authorId: '2022****312', authorAvatar: zhouAvatar, content: '西区花园旁边早上人比较少。', createdAt: ago(11, 8), status: 'visible', parentId: null, likes: 2 },
  { id: 'C-3117', postId: 'P-2026101004', business: 'forum', linkedTitle: initialPosts[11].title, authorName: '苏小禾', authorId: '2023****117', authorAvatar: zhouAvatar, content: '这些照片很有校园记忆。', createdAt: ago(13, 21), status: 'visible', parentId: null, likes: 3 },
]

const initialReports: AdminReport[] = [
  { id: 'R-2026102302', type: 'post', priority: 'high', status: 'pending', reason: '涉及不当言论', description: '帖子内容疑似包含针对同学的攻击性表达，请核实上下文。', createdAt: ago(0, 9), reporterName: '苏小禾', reporterId: '2023****117', targetId: 'P-2026102208', targetName: '大三计科阿杰', targetUserId: '2021****884', targetTitle: initialPosts[1].title, evidence: [libraryImage], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026102203', type: 'comment', priority: 'medium', status: 'pending', reason: '评论含有引流信息', description: '评论中出现疑似站外联系方式，需结合原帖语境审核。', createdAt: ago(1, 14), reporterName: '陈同学', reporterId: '2022****510', targetId: 'C-3102', targetName: '林知夏', targetUserId: '2022****312', targetTitle: '回复：晚霞真的很美，图书馆靠窗的位置也很适合复习。', evidence: [deskImage], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026102204', type: 'user', priority: 'medium', status: 'pending', reason: '疑似重复发布广告', description: '近期多个内容出现相同推广描述，请核对账号活动。', createdAt: ago(1, 11), reporterName: '匿名同学', reporterId: '匿名', targetId: '2024****183', targetName: '王同学', targetUserId: '2024****183', targetTitle: '用户行为举报', evidence: [campusImage], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026102105', type: 'message', priority: 'low', status: 'pending', reason: '私信沟通不当', description: '举报人反馈私信沟通中存在不友善表达，请查看所提交的会话证据。', createdAt: ago(2, 17), reporterName: '李同学', reporterId: '2023****204', targetId: 'DM-2026102105', targetName: '苏小禾', targetUserId: '2023****117', targetTitle: '同学交流', evidence: [deskImage], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026101909', type: 'post', priority: 'medium', status: 'pending', reason: '内容疑似重复发布', description: '内容与近期其他校园动态相似，请核对是否为重复信息。', createdAt: ago(4, 13), reporterName: '周同学', reporterId: '2022****702', targetId: 'P-2026102103', targetName: '苏小禾', targetUserId: '2023****117', targetTitle: initialPosts[3].title, evidence: [], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026101810', type: 'comment', priority: 'medium', status: 'pending', reason: '回复内容存在人身攻击', description: '请结合一级评论与回复上下文核查。', createdAt: ago(5, 16), reporterName: '匿名同学', reporterId: '匿名', targetId: 'C-3104', targetName: '大三计科阿杰', targetUserId: '2021****884', targetTitle: '回复：复习资料可以发一份吗？最近正好在整理笔记。', evidence: [], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026101711', type: 'user', priority: 'low', status: 'pending', reason: '用户资料疑似包含联系方式', description: '举报人反馈个人简介中疑似包含外部联系方式，请管理员核实。', createdAt: ago(6, 10), reporterName: '陈同学', reporterId: '2022****510', targetId: '2022****702', targetName: '周同学', targetUserId: '2022****702', targetTitle: '用户资料举报', evidence: [campusImage], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026101612', type: 'message', priority: 'low', status: 'pending', reason: '私信疑似诱导转账', description: '请核验私信安全提醒是否已向双方展示。', createdAt: ago(7, 19), reporterName: '林知夏', reporterId: '2022****312', targetId: 'DM-2026101612', targetName: '李同学', targetUserId: '2023****204', targetTitle: '同学交流', evidence: [deskImage], resolution: null, resolutionReason: '', processedAt: null, history: [] },
  { id: 'R-2026101907', type: 'comment', priority: 'low', status: 'processed', reason: '重复评论', description: '相同内容短时间内重复出现。', createdAt: ago(4, 12), reporterName: '陈同学', reporterId: '2022****510', targetId: 'C-3105', targetName: '陈同学', targetUserId: '2022****510', targetTitle: '感谢，校园卡已经领回了。', evidence: [], resolution: 'reject', resolutionReason: '经核实为正常交流内容，驳回举报。', processedAt: ago(3, 9), history: [{ action: 'reject', reason: '经核实为正常交流内容，驳回举报。', processedAt: ago(3, 9), operator: '林教员' }] },
]

function createInitialUsers(): AdminUser[] {
  const indexed = new Map<string, { name: string; avatar: string }>()
  const remember = (id: string, name: string, avatar: string) => {
    if (id && id !== '匿名' && !indexed.has(id)) indexed.set(id, { name, avatar })
  }
  initialPosts.forEach(item => remember(item.authorId, item.authorName, item.authorAvatar))
  initialComments.forEach(item => remember(item.authorId, item.authorName, item.authorAvatar))
  initialReports.forEach(item => {
    remember(item.reporterId, item.reporterName, '')
    remember(item.targetUserId, item.targetName, '')
  })
  ;[
    ['2021****602', '赵同学'], ['2022****019', '许同学'], ['2023****620', '唐同学'],
    ['2024****771', '蒋同学'], ['2025****038', '邓同学'],
  ].forEach(([id, name]) => remember(id, name, ''))
  const majors = ['软件工程', '计算机科学与技术', '网络工程', '数字媒体技术']
  return [...indexed.entries()].map(([id, profile], index) => {
    const enrollmentYear = id.match(/^20\d{2}/)?.[0] ?? '2022'
    return {
      id,
      name: profile.name,
      avatar: profile.avatar,
      school: '河南信息科技学院',
      major: majors[index % majors.length],
      className: `${majors[index % majors.length]}${enrollmentYear}-${index % 3 + 1}班`,
      enrollmentYear,
      joinedAt: index < 2 ? ago(0, 8 + index) : ago(90 + index, 12),
      lastActiveAt: ago(index % 8, 20),
      status: 'active',
      restrictions: { publish: false, comment: false, message: false },
      banUntil: null,
      history: [],
    }
  })
}

function paginate<T>(items: T[], page: number, pageSize: number): QueryPage<T> {
  const safePage = Math.max(1, page)
  const safeSize = Math.max(1, pageSize)
  const start = (safePage - 1) * safeSize
  return { items: items.slice(start, start + safeSize), total: items.length, page: safePage, pageSize: safeSize }
}

export const useContentModerationStore = defineStore('content-moderation', () => {
  const posts = ref<AdminPost[]>(initialPosts.map(post => ({ ...post, images: [...post.images] })))
  const comments = ref<AdminComment[]>(initialComments.map(comment => ({ ...comment })))
  const reports = ref<AdminReport[]>(initialReports.map(report => ({ ...report, evidence: [...report.evidence], history: report.history.map(item => ({ ...item })) })))
  const users = ref<AdminUser[]>(createInitialUsers())
  const auditStore = useAdminAuditStore()
  function appendAudit(event: AuditEventInput) {
    try { recordAdminAuditSafely(payload => auditStore.record(payload), event) } catch { /* Do not leak audit failures into a business operation. */ }
  }
  function auditedMutation<T>(
    createEvent: (result: T | undefined, outcome: AuditOutcome) => AuditEventInput,
    mutation: () => T,
    isSuccess: (result: T) => boolean,
  ): T {
    try {
      const result = mutation()
      try { appendAudit(createEvent(result, isSuccess(result) ? 'success' : 'business_rejected')) } catch { /* Audit event construction is best-effort. */ }
      return result
    } catch (error) {
      try { appendAudit(createEvent(undefined, 'execution_error')) } catch { /* Preserve the original mutation exception. */ }
      throw error
    }
  }
  const restrictedUsers = computed(() => users.value.filter(user => user.status !== 'active').map(user => user.id))
  const postStats = computed(() => {
    const today = new Date().toDateString()
    return {
      total: posts.value.length,
      visible: posts.value.filter(post => post.status === 'visible').length,
      hidden: posts.value.filter(post => post.status === 'hidden').length,
      today: posts.value.filter(post => new Date(post.createdAt).toDateString() === today).length,
      pending: posts.value.filter(post => post.reviewState === 'pending').length,
    }
  })
  const commentStats = computed(() => ({
    total: comments.value.length,
    visible: comments.value.filter(comment => comment.status === 'visible').length,
    hidden: comments.value.filter(comment => comment.status === 'hidden').length,
    today: comments.value.filter(comment => new Date(comment.createdAt).toDateString() === new Date().toDateString()).length,
    interactions: comments.value.reduce((sum, comment) => sum + comment.likes, 0),
  }))
  const reportStats = computed(() => ({
    total: reports.value.length,
    pending: reports.value.filter(report => report.status === 'pending').length,
    processed: reports.value.filter(report => report.status === 'processed').length,
    highPriority: reports.value.filter(report => report.status === 'pending' && report.priority === 'high').length,
  }))
  const userStats = computed(() => {
    const today = new Date().toDateString()
    return {
      total: users.value.length,
      todayNew: users.value.filter(user => new Date(user.joinedAt).toDateString() === today).length,
      restricted: users.value.filter(user => user.status === 'restricted' || user.status === 'banned').length,
      permanentlyBanned: users.value.filter(user => user.status === 'permanently_banned').length,
    }
  })

  function queryUsers(filters: UserFilters, page: number, pageSize: number): QueryPage<AdminUser> {
    refreshExpiredBans()
    const keyword = filters.keyword?.trim().toLocaleLowerCase() ?? ''
    const filtered = users.value.filter(user => {
      const matchesKeyword = !keyword || `${user.id} ${user.name} ${user.school} ${user.major} ${user.className} ${user.enrollmentYear}`.toLocaleLowerCase().includes(keyword)
      const matchesStatus = !filters.status || filters.status === 'all' || user.status === filters.status
      const matchesRestriction = !filters.restriction || filters.restriction === 'all' || user.restrictions[filters.restriction]
      return matchesKeyword && matchesStatus && matchesRestriction
    }).sort((left, right) => right.joinedAt.localeCompare(left.joinedAt) || left.id.localeCompare(right.id))
    return paginate(filtered, page, pageSize)
  }

  function queryPosts(filters: PostFilters, page: number, pageSize: number): QueryPage<AdminPost> {
    const keyword = filters.keyword?.trim().toLocaleLowerCase() ?? ''
    const periodDays = filters.period === '7d' ? 7 : filters.period === '30d' ? 30 : 0
    const cutoff = periodDays ? Date.now() - periodDays * 24 * 60 * 60 * 1000 : 0
    const filtered = posts.value.filter(post => {
      const matchesKeyword = !keyword || `${post.title} ${post.content} ${post.authorName} ${post.authorId}`.toLocaleLowerCase().includes(keyword)
      const matchesCategory = !filters.category || filters.category === 'all' || post.category === filters.category
      const matchesStatus = !filters.status || filters.status === 'all' || post.status === filters.status
      return matchesKeyword && matchesCategory && matchesStatus && (!cutoff || new Date(post.createdAt).getTime() >= cutoff)
    }).sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    return paginate(filtered, page, pageSize)
  }

  function queryComments(filters: CommentFilters, page: number, pageSize: number): QueryPage<AdminComment> {
    const keyword = filters.keyword?.trim().toLocaleLowerCase() ?? ''
    const filtered = comments.value.filter(comment => {
      const postTitle = comment.postId ? postById(comment.postId)?.title ?? '' : ''
      const matchesKeyword = !keyword || `${comment.content} ${comment.authorName} ${comment.authorId} ${comment.linkedTitle} ${postTitle}`.toLocaleLowerCase().includes(keyword)
      const matchesLevel = !filters.level || filters.level === 'all' || (filters.level === 'root' ? comment.parentId === null : comment.parentId !== null)
      const matchesStatus = !filters.status || filters.status === 'all' || comment.status === filters.status
      const matchesBusiness = !filters.business || filters.business === 'all' || comment.business === filters.business
      return matchesKeyword && matchesLevel && matchesStatus && matchesBusiness
    }).sort((left, right) => right.createdAt.localeCompare(left.createdAt))
    return paginate(filtered, page, pageSize)
  }


  function queryReports(filters: ReportFilters, page: number, pageSize: number): QueryPage<AdminReport> {
    const keyword = filters.keyword?.trim().toLocaleLowerCase() ?? ''
    const periodDays = filters.period === '7d' ? 7 : filters.period === '30d' ? 30 : 0
    const cutoff = periodDays ? Date.now() - periodDays * 24 * 60 * 60 * 1000 : 0
    const filtered = reports.value.filter(report => {
      const matchesKeyword = !keyword || `${report.id} ${report.reason} ${report.description} ${report.targetTitle} ${report.targetName} ${report.targetUserId} ${report.reporterName} ${report.reporterId}`.toLocaleLowerCase().includes(keyword)
      const matchesType = !filters.type || filters.type === 'all' || report.type === filters.type
      const matchesPriority = !filters.priority || filters.priority === 'all' || report.priority === filters.priority
      const matchesStatus = !filters.status || filters.status === 'all' || report.status === filters.status
      return matchesKeyword && matchesType && matchesPriority && matchesStatus && (!cutoff || new Date(report.createdAt).getTime() >= cutoff)
    }).sort((left, right) => {
      const priorityOrder: Record<ReportPriority, number> = { high: 0, medium: 1, low: 2 }
      if (left.status !== right.status) return left.status === 'pending' ? -1 : 1
      return priorityOrder[left.priority] - priorityOrder[right.priority] || right.createdAt.localeCompare(left.createdAt)
    })
    return paginate(filtered, page, pageSize)
  }

  function postById(id: string) { return posts.value.find(post => post.id === id) }
  function commentById(id: string) { return comments.value.find(comment => comment.id === id) }
  function commentsForPost(postId: string) { return comments.value.filter(comment => comment.postId === postId).sort((a, b) => a.createdAt.localeCompare(b.createdAt)) }
  function visibleCommentCount(postId: string) { return comments.value.filter(comment => comment.postId === postId && comment.status === 'visible').length }
  function repliesFor(commentId: string) { return comments.value.filter(comment => comment.parentId === commentId).sort((a, b) => a.createdAt.localeCompare(b.createdAt)) }

  function applyPostStatus(ids: string[], status: ModerationStatus): number {
    const targets = new Set(ids)
    let changed = 0
    posts.value.forEach(post => {
      if (targets.has(post.id) && post.status !== status) { post.status = status; changed += 1 }
    })
    return changed
  }
  function setPostStatus(ids: string[], status: ModerationStatus): void {
    auditedMutation((changed, outcome) => ({ module: 'posts', action: status === 'hidden' ? 'hide' : 'restore', targetType: 'post', targetCount: changed ?? new Set(ids).size, outcome, summary: outcome === 'success' ? '帖子状态已更新' : '帖子状态未发生变更' }), () => applyPostStatus(ids, status), changed => changed > 0)
  }
  function removePosts(ids: string[]): number {
    const targets = new Set(ids)
    const deletedPostIds = new Set(posts.value.filter(post => targets.has(post.id)).map(post => post.id))
    posts.value = posts.value.filter(post => !targets.has(post.id))
    comments.value = comments.value.filter(comment => !comment.postId || !deletedPostIds.has(comment.postId))
    return deletedPostIds.size
  }
  function deletePosts(ids: string[]): void {
    auditedMutation((deleted, outcome) => ({ module: 'posts', action: 'delete', targetType: 'post', targetCount: deleted ?? new Set(ids).size, outcome, summary: outcome === 'success' ? '帖子及关联评论已删除' : '未找到可删除帖子' }), () => removePosts(ids), deleted => deleted > 0)
  }
  function applyCommentStatus(ids: string[], status: ModerationStatus): number {
    const targets = new Set(ids)
    let changed = 0
    comments.value.forEach(comment => {
      if (targets.has(comment.id) && comment.status !== status) { comment.status = status; changed += 1 }
    })
    return changed
  }
  function setCommentStatus(ids: string[], status: ModerationStatus): void {
    auditedMutation((changed, outcome) => ({ module: 'comments', action: status === 'hidden' ? 'hide' : 'restore', targetType: 'comment', targetCount: changed ?? new Set(ids).size, outcome, summary: outcome === 'success' ? '评论状态已更新' : '评论状态未发生变更' }), () => applyCommentStatus(ids, status), changed => changed > 0)
  }
  function removeComments(ids: string[]): number {
    const targets = new Set(ids)
    const removed = new Set(comments.value.filter(comment => targets.has(comment.id)).map(comment => comment.id))
    comments.value = comments.value.filter(comment => !targets.has(comment.id) && (!comment.parentId || !removed.has(comment.parentId)))
    return removed.size
  }
  function deleteComments(ids: string[]): void {
    auditedMutation((deleted, outcome) => ({ module: 'comments', action: 'delete', targetType: 'comment', targetCount: deleted ?? new Set(ids).size, outcome, summary: outcome === 'success' ? '评论及关联回复已删除' : '未找到可删除评论' }), () => removeComments(ids), deleted => deleted > 0)
  }


  function reportById(id: string) { return reports.value.find(report => report.id === id) }
  function userById(id: string) {
    refreshExpiredBans()
    return users.value.find(user => user.id === id)
  }
  function userActivity(id: string) {
    return {
      posts: posts.value.filter(post => post.authorId === id).sort((left, right) => right.createdAt.localeCompare(left.createdAt)),
      comments: comments.value.filter(comment => comment.authorId === id).sort((left, right) => right.createdAt.localeCompare(left.createdAt)),
    }
  }
  function reportsForUser(id: string) {
    return reports.value.filter(report => report.targetUserId === id || report.reporterId === id).sort((left, right) => right.createdAt.localeCompare(left.createdAt))
  }

  function addUserHistory(user: AdminUser, action: UserModerationAction, reason: string, source: UserModerationHistoryEntry['source'], operator = '林教员') {
    user.history.unshift({ action, reason: reason.trim(), createdAt: new Date().toISOString(), operator, source })
  }

  function refreshExpiredBans() {
    const now = Date.now()
    users.value.forEach(user => {
      if (user.status !== 'banned' || !user.banUntil || new Date(user.banUntil).getTime() > now) return
      user.status = 'active'
      user.restrictions = { publish: false, comment: false, message: false }
      user.banUntil = null
      addUserHistory(user, 'temporary_ban_expired', '临时封禁期限已到，系统自动解除。', 'user_management', '系统')
    })
  }

  function setUserRestriction(id: string, channel: UserRestrictionChannel, enabled: boolean, reason: string): boolean {
    return auditedMutation((result, outcome) => ({ module: 'users', action: enabled ? `restrict_${channel}` : `release_${channel}`, targetType: 'user', targetCount: result ? 1 : undefined, outcome, summary: outcome === 'success' ? '用户权限状态已更新' : '用户权限操作未执行' }), () => {
      const user = userById(id)
      const detail = reason.trim()
      if (!user || !detail || user.status === 'banned' || user.status === 'permanently_banned' || user.restrictions[channel] === enabled) return false
      user.restrictions[channel] = enabled
      user.status = Object.values(user.restrictions).some(Boolean) ? 'restricted' : 'active'
      const action: Record<UserRestrictionChannel, UserModerationAction> = enabled
        ? { publish: 'restrict_publish', comment: 'restrict_comment', message: 'restrict_message' }
        : { publish: 'release_publish', comment: 'release_comment', message: 'release_message' }
      addUserHistory(user, action[channel], `${enabled ? '限制' : '解除'}：${detail}`, 'user_management')
      return true
    }, Boolean)
  }

  function banUser(id: string, durationDays: number | 'permanent', reason: string): boolean {
    return auditedMutation((result, outcome) => ({ module: 'users', action: durationDays === 'permanent' ? 'permanent_ban' : 'temporary_ban', targetType: 'user', targetCount: result ? 1 : undefined, outcome, summary: outcome === 'success' ? '用户限制状态已更新' : '用户封禁操作未执行' }), () => {
      const user = userById(id)
      const detail = reason.trim()
      if (!user || !detail || user.status === 'permanently_banned' || (user.status === 'banned' && durationDays !== 'permanent')) return false
      if (durationDays !== 'permanent' && (!Number.isInteger(durationDays) || durationDays < 1)) return false
      user.restrictions = { publish: true, comment: true, message: true }
      if (durationDays === 'permanent') {
        user.status = 'permanently_banned'
        user.banUntil = null
        addUserHistory(user, 'permanent_ban', detail, 'user_management')
      } else {
        const until = new Date()
        until.setDate(until.getDate() + durationDays)
        user.status = 'banned'
        user.banUntil = until.toISOString()
        addUserHistory(user, 'temporary_ban', `${durationDays} 天：${detail}`, 'user_management')
      }
      return true
    }, Boolean)
  }

  function liftUserRestrictions(id: string, reason: string): boolean {
    return auditedMutation((result, outcome) => ({ module: 'users', action: 'lift_restrictions', targetType: 'user', targetCount: result ? 1 : undefined, outcome, summary: outcome === 'success' ? '用户限制已解除' : '用户限制未发生变更' }), () => {
      const user = userById(id)
      const detail = reason.trim()
      if (!user || !detail || user.status === 'permanently_banned' || user.status === 'active') return false
      user.restrictions = { publish: false, comment: false, message: false }
      user.status = 'active'
      user.banUntil = null
      addUserHistory(user, 'lift_restrictions', detail, 'user_management')
      return true
    }, Boolean)
  }

  function applyReportUserActionMutation(id: string, action: 'restrict_user' | 'warn_user', reason: string) {
    const user = userById(id)
    if (!user) return false
    if (action === 'restrict_user') {
      user.restrictions = { publish: true, comment: true, message: true }
      if (user.status === 'active') user.status = 'restricted'
      if (user.status === 'restricted' && !Object.values(user.restrictions).some(Boolean)) user.status = 'restricted'
      addUserHistory(user, 'report_restriction', reason, 'report')
    } else {
      addUserHistory(user, 'report_warning', reason, 'report')
    }
    return true
  }




  function processReport(id: string, action: ReportResolution, reason: string): boolean {
    return auditedMutation((_result, outcome) => ({ module: 'reports', action, targetType: 'report', targetRef: id, targetCount: 1, outcome, summary: outcome === 'success' ? '举报处理及关联状态更新已完成' : '举报处理未执行' }), () => {
      const report = reportById(id)
      const detail = reason.trim()
      if (!report || report.status !== 'pending' || !detail) return false
      const compatible: Record<ReportResolution, ReportType[]> = {
        reject: ['post', 'comment', 'user', 'message'],
        hide_post: ['post'],
        hide_comment: ['comment'],
        restrict_user: ['user'],
        warn_user: ['user'],
        hide_message: ['message'],
      }
      if (!compatible[action].includes(report.type)) return false
      if (action === 'hide_post' && !postById(report.targetId)) return false
      if (action === 'hide_comment' && !commentById(report.targetId)) return false

      if (action === 'hide_post') applyPostStatus([report.targetId], 'hidden')
      if (action === 'hide_comment') applyCommentStatus([report.targetId], 'hidden')
      if ((action === 'restrict_user' || action === 'warn_user') && !applyReportUserActionMutation(report.targetUserId, action, detail)) return false

      const processedAt = new Date().toISOString()
      report.status = 'processed'
      report.resolution = action
      report.resolutionReason = detail
      report.processedAt = processedAt
      report.history.unshift({ action, reason: detail, processedAt, operator: '林教员' })
      return true
    }, Boolean)
  }

  return {
    posts, comments, reports, users, restrictedUsers, postStats, commentStats, reportStats, userStats,
    queryPosts, queryComments, queryReports, queryUsers, postById, commentById, reportById, userById, userActivity, reportsForUser,
    commentsForPost, visibleCommentCount, repliesFor, setPostStatus, deletePosts, setCommentStatus, deleteComments,
    setUserRestriction, banUser, liftUserRestrictions, refreshExpiredBans, processReport,
  }
})
