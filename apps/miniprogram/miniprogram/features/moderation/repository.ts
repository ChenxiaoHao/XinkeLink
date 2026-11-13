import { LocalStorage } from '../profile/repository'

export const REPORTS_KEY = 'xinkelink.reports.v1'
export const REPORT_REASONS = [
  '涉嫌校外商业广告或代写兼职', '诈骗或诱导私下转账',
  '违规辱骂骚扰或人身攻击', '冒充校方/官方发布虚假通知', '其他违规行为',
] as const
export type ReportReason = typeof REPORT_REASONS[number]
// 真机侧支持举报评论，本地演示也要认这个 kind，否则评论举报记录会被判为无效。
export type ReportTargetKind = 'post' | 'comment' | 'user'
export interface LocalReport {
  id: string; targetKind: ReportTargetKind; targetId: string; reason: ReportReason
  detail: string; evidence: string[]; createdAt: number; status: 'local-only'
}
export interface ReportDraft { targetKind: ReportTargetKind; targetId: string; reason: ReportReason | ''; detail: string; evidence: string[] }

function validKind(value: unknown): value is ReportTargetKind { return value === 'post' || value === 'comment' || value === 'user' }
function validReason(value: unknown): value is ReportReason { return REPORT_REASONS.some(reason => reason === value) }

export function readLocalReports(storage: LocalStorage): LocalReport[] {
  const raw = storage.getStorageSync(REPORTS_KEY) as unknown
  if (raw === undefined || raw === null || raw === '') return []
  const value = raw as { version?: unknown; items?: unknown }
  if (value.version !== 1 || !Array.isArray(value.items) || !value.items.every(item => item && typeof item.id === 'string'
    && validKind(item.targetKind) && typeof item.targetId === 'string' && validReason(item.reason)
    && typeof item.detail === 'string' && Array.isArray(item.evidence) && Number.isFinite(item.createdAt)
    && item.status === 'local-only')) throw new Error('举报记录读取失败')
  return value.items as LocalReport[]
}

export function submitLocalReport(storage: LocalStorage, draft: ReportDraft): LocalReport {
  if (!validKind(draft.targetKind) || !draft.targetId.trim()) throw new Error('举报对象无效')
  if (!validReason(draft.reason)) throw new Error('请选择举报原因')
  const detail = draft.detail.trim()
  if (!detail || detail.length > 200) throw new Error('请填写 1 至 200 字的举报说明')
  if (!Array.isArray(draft.evidence) || draft.evidence.length > 3 || draft.evidence.some(item => typeof item !== 'string' || !item)) throw new Error('举报截图最多 3 张')
  const report: LocalReport = { id: 'local-report-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8),
    targetKind: draft.targetKind, targetId: draft.targetId, reason: draft.reason, detail,
    evidence: [...draft.evidence], createdAt: Date.now(), status: 'local-only' }
  storage.setStorageSync(REPORTS_KEY, { version: 1, items: [report, ...readLocalReports(storage)] })
  return report
}
