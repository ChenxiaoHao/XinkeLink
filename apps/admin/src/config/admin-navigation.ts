export interface AdminNavigationItem {
  name: string
  label: string
  path: string
  icon: string
  description: string
}

export interface AdminNavigationGroup {
  label: string
  items: AdminNavigationItem[]
}

export const ADMIN_NAVIGATION: AdminNavigationGroup[] = [
  { label: '概览', items: [
    { name: 'dashboard', label: '数据概览', path: '/admin/dashboard', icon: 'ChartNoAxesCombined', description: '社区运行数据与待办事项' },
  ] },
  { label: '内容管理', items: [
    { name: 'ai-service', label: 'Link AI 服务', path: '/admin/ai/service', icon: 'Settings2', description: '服务开关、模型和 Token 用量' },
    { name: 'ai-knowledge', label: 'Link AI 知识库', path: '/admin/ai/knowledge', icon: 'FileText', description: '学校规章资料导入、校对、发布与停用' },
    { name: 'posts', label: '帖子管理', path: '/admin/posts', icon: 'FileText', description: '帖子内容管理' },
    { name: 'comments', label: '评论管理', path: '/admin/comments', icon: 'MessageSquareText', description: '评论与回复管理' },
  ] },
  { label: '社区治理', items: [
    { name: 'reports', label: '举报审核', path: '/admin/reports', icon: 'ShieldAlert', description: '社区举报审核' },
    { name: 'users', label: '用户管理', path: '/admin/users', icon: 'UsersRound', description: '社区用户管理' },
  ] },
  { label: '运营管理', items: [
    { name: 'feedback', label: '意见反馈', path: '/admin/feedback', icon: 'MessageSquareText', description: '用户意见与建议' },
    { name: 'announcements', label: '公告管理', path: '/admin/announcements', icon: 'Megaphone', description: '平台公告管理' },
    { name: 'notifications', label: '通知管理', path: '/admin/notifications', icon: 'Bell', description: '系统通知管理' },
  ] },
  { label: '系统', items: [
    { name: 'logs', label: '操作日志', path: '/admin/logs', icon: 'ScrollText', description: '管理员操作记录' },
    { name: 'settings', label: '系统设置', path: '/admin/settings', icon: 'Settings2', description: '管理后台设置' },
  ] },
]

export const ADMIN_NAV_ITEMS = ADMIN_NAVIGATION.flatMap(group => group.items)
