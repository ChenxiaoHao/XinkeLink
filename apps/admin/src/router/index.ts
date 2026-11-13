import { createRouter, createWebHistory, type RouterHistory } from 'vue-router'
import type { Pinia } from 'pinia'
import AdminLayout from '../components/admin/AdminLayout.vue'
import { ADMIN_NAV_ITEMS } from '../config/admin-navigation'
import { useAdminAuthStore } from '../stores/admin-auth'
import DashboardView from '../views/DashboardView.vue'
import PostManagementView from '../views/PostManagementView.vue'
import CommentManagementView from '../views/CommentManagementView.vue'
import ReportReviewView from '../views/ReportReviewView.vue'
import UserManagementView from '../views/UserManagementView.vue'
import AnnouncementManagementView from '../views/AnnouncementManagementView.vue'
import NotificationManagementView from '../views/NotificationManagementView.vue'
import OperationLogView from '../views/OperationLogView.vue'
import SystemSettingsView from '../views/SystemSettingsView.vue'
import BetaManagementView from '../views/BetaManagementView.vue'
import FeedbackManagementView from '../views/FeedbackManagementView.vue'
import DevelopingView from '../views/DevelopingView.vue'
import LoginView from '../views/LoginView.vue'

export function createAdminRouter(pinia: Pinia, history: RouterHistory = createWebHistory(import.meta.env.BASE_URL)) {
  const router = createRouter({
    history,
    routes: [
      { path: '/', redirect: '/admin/dashboard' },
      { path: '/login', name: 'login', component: LoginView, meta: { guestOnly: true, title: '管理员登录' } },
      {
        path: '/admin', component: AdminLayout, meta: { requiresAuth: true },
        children: [
          { path: '', redirect: '/admin/dashboard' },
          ...ADMIN_NAV_ITEMS.map(item => ({
            path: item.path.replace('/admin/', ''),
            name: item.name,
            component: item.name === 'dashboard' ? DashboardView : item.name === 'posts' ? PostManagementView : item.name === 'comments' ? CommentManagementView : item.name === 'reports' ? ReportReviewView : item.name === 'users' ? UserManagementView : item.name === 'announcements' ? AnnouncementManagementView : item.name === 'notifications' ? NotificationManagementView : item.name === 'logs' ? OperationLogView : item.name === 'settings' ? SystemSettingsView : item.name === 'beta' ? BetaManagementView : item.name === 'feedback' ? FeedbackManagementView : DevelopingView,
            meta: { requiresAuth: true, title: item.label, description: item.description, navigationKey: item.name, development: !['dashboard', 'posts', 'comments', 'reports', 'users', 'announcements', 'notifications', 'logs', 'settings', 'beta', 'feedback'].includes(item.name) },
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
