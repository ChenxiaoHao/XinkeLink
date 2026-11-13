import { adminHttp } from './admin-http'
export interface DashboardData {
  asOf: string; range: string; rangeStart: string; rangeEnd: string
  metrics: { totalUsers: number; todayPosts: number; pendingReports: number }
  trends: Array<{ date: string; postCount: number; dau: number | null }>
  todos: Array<{ kind: string; id: string; title: string; priority: string; createdAt: string }>
  recentActivity: Array<{ id: string; kind: string; summary: string; createdAt: string; target: { kind: string; id: string } }>
  unavailableMetrics: Array<{ key: string; reason: string }>
}
export const readDashboard = (range: '7d' | '30d' | 'semester') => adminHttp.request<DashboardData>(`/dashboard?range=${range}`)
