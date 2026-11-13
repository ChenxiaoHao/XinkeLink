import { adminHttp, ApiError } from './admin-http'

export type BetaPhase = 'beta' | 'open'
export type BetaCodeStatus = 'unused' | 'used'
export interface BetaCode {
  id: string
  code: string
  status: BetaCodeStatus
  user: { id: string; nickname: string } | null
  usedAt: string | null
  createdAt: string
}
export interface BetaManagement {
  phase: BetaPhase
  version: number
  total: number
  used: number
  remaining: number
  codes: BetaCode[]
}

function validate(value: BetaManagement): BetaManagement {
  const id = (input: unknown) => typeof input === 'string' && /^[1-9]\d*$/.test(input)
  const date = (input: unknown) => typeof input === 'string' && Number.isFinite(Date.parse(input))
  const valid = value && ['beta', 'open'].includes(value.phase)
    && [value.version, value.total, value.used, value.remaining].every(n => Number.isSafeInteger(n) && n >= 0)
    && [0, 30].includes(value.total) && value.used + value.remaining === value.total
    && Array.isArray(value.codes) && value.codes.length === value.total
    && value.codes.every(row => row && id(row.id) && typeof row.code === 'string' && row.code.length > 0 && date(row.createdAt)
      && (row.status === 'unused' ? row.user === null && row.usedAt === null : row.status === 'used' && row.user && id(row.user.id) && typeof row.user.nickname === 'string' && date(row.usedAt)))
    && value.codes.filter(row => row.status === 'used').length === value.used
    && new Set(value.codes.map(row => row.id)).size === value.total
    && new Set(value.codes.map(row => row.code)).size === value.total
    && (value.total > 0 || value.phase === 'open')
  if (!valid) throw new ApiError(502, 'INVALID_RESPONSE', '内测管理响应格式异常，请刷新重试')
  return value
}

export const adminBeta = {
  async get() { return validate(await adminHttp.request<BetaManagement>('/beta')) },
  async initialize() { return validate(await adminHttp.request<BetaManagement>('/beta/initialize', { method: 'POST', body: JSON.stringify({}) })) },
  async setPhase(phase: BetaPhase, expectedVersion: number) {
    return validate(await adminHttp.request<BetaManagement>('/beta', { method: 'PATCH', body: JSON.stringify({ phase, expectedVersion }) }))
  },
}
