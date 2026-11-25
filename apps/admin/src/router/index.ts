import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import type { Component } from 'vue'
import { ADMIN_NAV_ITEMS } from '../config/admin-navigation'
import { useAdminAuthStore } from '../stores/admin-auth'

const adminViews: Partial<Record<string, () => Promise<{ default: Component }>>> = {
  dashboard: () => import('../views/DashboardView.vue'),
  posts: () => import('../views/PostManagementView.vue'),
  comments: () => import('../views/CommentManagementView.vue'),
  reports: () => import('../views/ReportReviewView.vue'),
  users: () => import('../views/UserManagementView.vue'),
  announcements: () => import('../views/AnnouncementManagementView.vue'),
  notifications: () => import('../views/NotificationManagementView.vue'),
  logs: () => import('../views/OperationLogView.vue'),
  settings: () => import('../views/SystemSettingsView.vue'),
  feedback: () => import('../views/FeedbackManagementView.vue'),
  'ai-knowledge': () => import('../views/KnowledgeManagementView.vue'),
  'ai-service': () => import('../views/AiServiceView.vue'),
}

export function createAdminRouter(pinia: Pinia, history: RouterHistory = createWebHistory(import.meta.env.BASE_URL)) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/admin/dashboard' },
      { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { guestOnly: true, title: '管理员登录' } },
      {
        path: '/admin', component: () => import('../components/admin/AdminLayout.vue'), meta: { requiresAuth: true },
        children: [
          { path: '', redirect: '/admin/dashboard' },
          ...ADMIN_NAV_ITEMS.map(item => ({
            path: item.path.replace('/admin/', ''),
            name: item.name,
            component: adminViews[item.name] ?? (() => import('../views/DevelopingView.vue')),
            meta: { requiresAuth: true, title: item.label, description: item.description, navigationKey: item.name, development: !adminViews[item.name] },
          })),
        ],
      },
      { path: '/:pathMatch(.*)*', redirect: '/admin/dashboard' },
    ],
  })

  router.beforeEach(async to => {
    const auth = useAdminAuthStore(pinia)
    await auth.restoreSession()
    if (to.meta.requiresAuth && !auth.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
    if (to.name === 'login' && auth.isAuthenticated) return { name: 'dashboard' }
    return true
  })

  return router
}
