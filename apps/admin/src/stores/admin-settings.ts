import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { Wire } from '../services/admin-adapters'
import { adminHttp, ApiError } from '../services/admin-http'
export type ImageSensitivity = 'relaxed' | 'standard' | 'strict'

export interface AdminSettings {
  version: number
  effectMode: 'configuration_only'
  platform: {
    brandName: string
    serviceArea: string
    platformNotice: string
  }
  contentReview: {
    sensitiveWordIntercept: boolean
    nightQueueMode: boolean
    imageSensitivity: ImageSensitivity
    sensitiveWords: string[]
  }
  community: {
    controlsAvailable: false
    note: string
  }
  administrator: {
    account: string
    role: string
  }
  system: {
    environment: string
    version: string
    resetsOnRefresh: boolean
    persistenceNote: string
  }
}

export interface SettingsSaveResult {
  ok: boolean
  changedFields: string[]
  errors: Array<{ field: string; code: 'required' | 'invalid' }>
}


export const useAdminSettingsStore = defineStore('admin-settings', () => {
  const loaded = ref(false)
  const settings = ref<AdminSettings>({ version: 0, effectMode: 'configuration_only', platform: { brandName: '', serviceArea: '', platformNotice: '' }, contentReview: { sensitiveWordIntercept: false, nightQueueMode: false, imageSensitivity: 'standard', sensitiveWords: [] }, community: { controlsAvailable: false, note: '' }, administrator: { account: '', role: '' }, system: { environment: '', version: '', resetsOnRefresh: false, persistenceNote: '' } })
  function accept(dto: Wire) {
    settings.value = {
      version: dto.version, effectMode: dto.effectMode,
      platform: { brandName: dto.platform.name, serviceArea: dto.platform.school, platformNotice: dto.platform.description },
      administrator: { account: dto.administrator.loginName, role: dto.administrator.role },
      community: { controlsAvailable: false, note: dto.community.description }, contentReview: dto.contentReview,
      system: { environment: dto.system.architecture, version: String(dto.version), resetsOnRefresh: false, persistenceNote: '配置保存在服务器；当前仅作为配置，不执行自动内容拦截。' },
    }
    loaded.value = true
  }
  async function loadSettings() { accept(await adminHttp.request<AdminSettings>('/settings')) }
  async function saveSettings(next: AdminSettings): Promise<SettingsSaveResult> {
    try {
    const before = settings.value.contentReview
    const dto = await adminHttp.request<AdminSettings & { changedFields?: string[] }>('/settings/content-review', { method: 'PATCH', body: JSON.stringify({ ...next.contentReview, expectedVersion: settings.value.version }) })
    const changedFields = dto.changedFields ?? Object.keys(before).filter(field => JSON.stringify(before[field as keyof typeof before]) !== JSON.stringify(dto.contentReview[field as keyof typeof before])).map(field => `contentReview.${field}`)
    accept(dto); return { ok: true, changedFields, errors: [] }
    } catch (error) {
      if (error instanceof ApiError && error.status === 400 && error.code === 'VALIDATION_FAILED') {
        return { ok: false, changedFields: [], errors: error.errors.length ? error.errors.map(item => ({ field: item.field, code: 'invalid' as const })) : [{ field: 'contentReview', code: 'invalid' }] }
      }
      throw error
    }
  }
  async function resetToDefaults() {
    const dto = await adminHttp.request<AdminSettings & { changedFields?: string[] }>('/settings/content-review/reset', { method: 'POST', body: JSON.stringify({ expectedVersion: settings.value.version, confirmed: true }) })
    accept(dto); return { changedFields: dto.changedFields ?? [] }
  }
  return { settings, loaded, loadSettings, saveSettings, resetToDefaults }
})
