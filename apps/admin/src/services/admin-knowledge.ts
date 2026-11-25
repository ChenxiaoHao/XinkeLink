import { adminHttp, ApiError } from './admin-http'

export interface KnowledgeDraft {
  title: string; source: string; version: string; publishedDate: string; scope: string; validity: string; body: string; reviewed: boolean
  filename?: string; originalBase64?: string
  validFrom?: string; validUntil?: string
}
export interface KnowledgeDocument extends KnowledgeDraft {
  id: string; status: 'draft' | 'published' | 'disabled'; activeVersionId: string; warnings: string[]
}
export interface KnowledgeExtraction { body: string; filename: string; originalBase64: string; warnings: string[]; title?: string; source?: string }
export const KNOWLEDGE_UPLOAD_MAX_MIB = 5
const base = '/ai/knowledge'
const json = (method: string, body?: unknown): RequestInit => ({ method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
function validateExtraction(value: KnowledgeExtraction) {
  if (!value || typeof value.body !== 'string' || !value.body.trim()) throw new Error('未提取到文字，请提供文字版文件或人工校对文本；扫描 PDF 不支持 OCR')
  if (!Array.isArray(value.warnings) || value.warnings.some(item => typeof item !== 'string') || typeof value.filename !== 'string' || typeof value.originalBase64 !== 'string') throw new ApiError(502, 'INVALID_RESPONSE', '正文提取响应异常，请重试')
  return value
}
export function validateKnowledgeDraft(body: KnowledgeDraft) {
  if (!body.title.trim() || !body.body.trim()) throw new Error('请填写标题和正文')
  if (body.publishedDate && (!/^\d{4}-\d{2}-\d{2}$/.test(body.publishedDate) || !Number.isFinite(Date.parse(body.publishedDate)) || new Date(body.publishedDate).toISOString().slice(0, 10) !== body.publishedDate)) throw new Error('请填写有效的发布日期')
  return { ...body, title: body.title.trim(), body: body.body.trim() }
}
export const adminKnowledge = {
  async list() {
    const data = await adminHttp.request<{ items: KnowledgeDocument[]; ready?: boolean }>(base)
    if (!data || (data.ready !== undefined && typeof data.ready !== 'boolean') || !Array.isArray(data.items) || data.items.some(row => !row || typeof row.id !== 'string' || typeof row.body !== 'string' || !['draft', 'published', 'disabled'].includes(row.status))) throw new ApiError(502, 'INVALID_RESPONSE', '知识资料响应异常，请刷新重试')
    return { items: data.items, ready: data.ready ?? null }
  },
  async extract(file: File) {
    if (!/\.(pdf|docx|txt|md)$/i.test(file.name)) throw new Error('请选择 PDF、DOCX、TXT 或 MD 文件')
    if (!file.size || file.size > KNOWLEDGE_UPLOAD_MAX_MIB * 1024 * 1024) throw new Error(`文件须非空且不超过 ${KNOWLEDGE_UPLOAD_MAX_MIB} MiB`)
    const body = new FormData(); body.append('file', file)
    return validateExtraction(await adminHttp.request<KnowledgeExtraction>(`${base}/extract`, { method: 'POST', body }))
  },
  async importUrl(value: string) {
    let url: URL
    try { url = new URL(value.trim()) } catch { throw new Error('请输入指定学校官网的 HTTPS 文章地址') }
    if (url.protocol !== 'https:' || url.hostname !== 'hbcet.hpu.edu.cn' || url.username || url.password || (url.port && url.port !== '443')) throw new Error('仅支持指定学校官网的 HTTPS 文章地址')
    return validateExtraction(await adminHttp.request<KnowledgeExtraction>(`${base}/import-url`, json('POST', { url: url.href })))
  },
  save: (body: KnowledgeDraft, id?: string) => adminHttp.request<KnowledgeDocument>(id ? `${base}/${encodeURIComponent(id)}` : base, json(id ? 'PUT' : 'POST', validateKnowledgeDraft(body))),
  publish: (id: string) => adminHttp.request<KnowledgeDocument>(`${base}/${encodeURIComponent(id)}/publish`, json('POST')),
  disable: (id: string) => adminHttp.request<KnowledgeDocument>(`${base}/${encodeURIComponent(id)}/disable`, json('POST')),
}
