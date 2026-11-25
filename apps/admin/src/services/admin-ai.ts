import { adminHttp } from './admin-http'

export type AiStage = 'chat' | 'embedding' | 'rerank'
export interface AiModel {
  stage: AiStage
  model: string
  baseUrl: string
  keyConfigured: boolean
}
export interface AiSettings {
  enabled: boolean
  revision: number
  models: AiModel[]
}
export interface AiEvent {
  time: string
  action: 'call' | 'model' | 'enable' | 'disable'
  stage: AiStage | null
  model: string | null
  success: boolean
  tokens: number | null
  elapsedMs: number | null
}
interface AiDashboard {
  settings: AiSettings
  today: { date: string; tokens: number; calls: number; unreportedCalls: number }
  records: AiEvent[]
}

const path = '/ai/service'
const put = (body: object): RequestInit => ({
  method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})

function publicSettings(settings: AiSettings): AiSettings {
  return {
    enabled: settings.enabled,
    revision: settings.revision,
    models: settings.models.map(({ stage, model, baseUrl, keyConfigured }) => ({ stage, model, baseUrl, keyConfigured })),
  }
}

export const adminAi = {
  async dashboard(): Promise<AiDashboard> {
    const data = await adminHttp.request<AiDashboard>(path)
    return { settings: publicSettings(data.settings), today: data.today, records: data.records }
  },
  async toggle(enabled: boolean, revision: number): Promise<AiSettings> {
    return publicSettings(await adminHttp.request<AiSettings>(`${path}/state`, put({ enabled, revision })))
  },
  async edit(stage: AiStage, input: { model: string; baseUrl: string; apiKey: string; revision: number }): Promise<AiSettings> {
    const { apiKey, ...model } = input
    return publicSettings(await adminHttp.request<AiSettings>(`${path}/models/${stage}`, put({
      ...model, ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}),
    })))
  },
}
