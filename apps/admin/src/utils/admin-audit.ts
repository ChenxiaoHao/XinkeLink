import type { AuditEvent, AuditEventInput } from '../stores/admin-audit'

export interface SafeAuditRow {
  id: string
  createdAt: string
  actor: string
  module: string
  action: string
  targetType: string
  targetRef: string
  targetCount: string
  outcome: string
  summary: string
  changedFields: string
  resultCode: string
}

const sensitiveFieldName = /(password|passwd|token|secret|credential|authorization|cookie|phone|mobile|student.?id|学号|手机号|密码|令牌|凭据)/i
const phonePattern = /(?<!\d)1[3-9]\d{9}(?!\d)/g
const longNumberPattern = /(?<!\d)\d{8,}(?!\d)/g
const jwtPattern = /\beyJ[a-zA-Z0-9_-]{5,}\.[a-zA-Z0-9_-]{5,}\.[a-zA-Z0-9_-]{5,}\b/g
const credentialPairPattern = /(password|passwd|token|secret|authorization|cookie|credential|密码|令牌|凭据)\s*[:：=]?\s*[^\s,;]+/gi

export function sanitizeAuditText(value: string, maxLength = 180): string {
  return String(value)
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, ' ')
    .replace(jwtPattern, '[已脱敏]')
    .replace(credentialPairPattern, '$1=[已脱敏]')
    .replace(phonePattern, '[已脱敏]')
    .replace(longNumberPattern, '[已脱敏]')
    .replace(/[\t ]+/g, ' ')
    .replace(/\r\n?/g, '\n')
    .trim()
    .slice(0, maxLength)
}

export function maskAuditActor(account: string): string {
  const value = String(account ?? '').trim()
  if (!value) return '本地演示管理员'
  if (/^1[3-9]\d{9}$/.test(value)) return `${value.slice(0, 3)}****${value.slice(-4)}`
  const email = value.match(/^([^@]+)@([^@]+)$/)
  if (email) return `${email[1].slice(0, 2)}${'*'.repeat(Math.max(3, email[1].length - 2))}@${email[2]}`
  if (/^\d{8,}$/.test(value)) return `${value.slice(0, 2)}****${value.slice(-2)}`
  return sanitizeAuditText(value, 48)
}

export function sanitizeChangedFields(fields: string[]): string[] {
  const result: string[] = []
  for (const field of fields) {
    if (typeof field !== 'string' || sensitiveFieldName.test(field)) continue
    const safe = field.replace(/[^\p{L}\p{N}_.-]/gu, '').slice(0, 48)
    if (safe && !result.includes(safe)) result.push(safe)
    if (result.length === 12) break
  }
  return result
}

export function toSafeAuditRow(event: AuditEvent): SafeAuditRow {
  return {
    id: String(event.id).slice(0, 80),
    createdAt: sanitizeAuditText(event.createdAt, 40),
    actor: maskAuditActor(event.actor),
    module: sanitizeAuditText(event.module, 32),
    action: sanitizeAuditText(event.action, 48),
    targetType: sanitizeAuditText(event.targetType, 40),
    targetRef: event.targetType === 'user' ? '用户（已脱敏）' : sanitizeAuditText(event.targetRef ?? '', 80),
    targetCount: event.targetCount === undefined ? '' : String(event.targetCount),
    outcome: sanitizeAuditText(event.outcome, 32),
    summary: sanitizeAuditText(event.summary ?? event.summaryCode ?? '', 180),
    changedFields: sanitizeChangedFields(event.changedFields ?? []).join('、'),
    resultCode: sanitizeAuditText(event.resultCode ?? '', 48),
  }
}

export function toAuditCsv(events: readonly AuditEvent[]): string {
  const headers: Array<keyof SafeAuditRow> = [
    'createdAt', 'actor', 'module', 'action', 'targetType', 'targetRef',
    'targetCount', 'outcome', 'summary', 'changedFields', 'resultCode',
  ]
  const labels: Record<keyof SafeAuditRow, string> = {
    id: '事件编号', createdAt: '操作时间', actor: '操作者', module: '业务模块',
    action: '操作类型', targetType: '目标类型', targetRef: '目标引用', targetCount: '目标数量',
    outcome: '处理结果', summary: '安全摘要', changedFields: '变更字段', resultCode: '结果代码',
  }
  const quote = (input: string): string => {
    const formulaSafe = /^[\s]*[=+\-@]/.test(input) ? `'${input}` : input
    return `"${formulaSafe.replace(/"/g, '""')}"`
  }
  const rows = [headers.map(key => quote(labels[key])).join(','), ...events.map((event) => {
    const row = toSafeAuditRow(event)
    return headers.map(key => quote(row[key])).join(',')
  })]
  return `\uFEFF${rows.join('\r\n')}`
}

export function recordAdminAuditSafely(writer: (input: AuditEventInput) => void, input: AuditEventInput): void {
  try { writer(input) } catch { /* Audit failures must be isolated from business state and return values. */ }
}
