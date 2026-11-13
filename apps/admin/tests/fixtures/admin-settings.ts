import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useAdminAuditStore, type AuditEventInput } from './admin-audit'
import { useAdminAuthStore } from './admin-auth'
import { maskAuditActor, recordAdminAuditSafely } from '../../src/utils/admin-audit'

export type ImageSensitivity = 'relaxed' | 'standard' | 'strict'

export interface AdminSettings {
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
    resetsOnRefresh: true
    persistenceNote: string
  }
}

export interface SettingsSaveResult {
  ok: boolean
  changedFields: string[]
  errors: Array<{ field: string; code: 'required' | 'invalid' }>
}

const defaultContentReview: AdminSettings['contentReview'] = {
  sensitiveWordIntercept: true,
  nightQueueMode: true,
  imageSensitivity: 'standard',
  sensitiveWords: ['代写论文', '期末代考', '英语四六级答案', '实验报告定制', '兼职加微信', '刷课插件', '高薪日结打工'],
}

function copyContentReview(source: AdminSettings['contentReview']): AdminSettings['contentReview'] {
  return { ...source, sensitiveWords: [...source.sensitiveWords] }
}

function deepFreeze<T>(value: T): T {
  if (!value || typeof value !== 'object' || Object.isFrozen(value)) return value
  Object.values(value).forEach(deepFreeze)
  return Object.freeze(value)
}

function editableFields(before: AdminSettings['contentReview'], after: AdminSettings['contentReview']): string[] {
  const fields: Array<keyof AdminSettings['contentReview']> = ['sensitiveWordIntercept', 'nightQueueMode', 'imageSensitivity', 'sensitiveWords']
  return fields
    .filter(field => JSON.stringify(before[field]) !== JSON.stringify(after[field]))
    .map(field => `contentReview.${field}`)
}

function validate(next: AdminSettings): SettingsSaveResult['errors'] {
  const errors: SettingsSaveResult['errors'] = []
  const content = next?.contentReview
  if (!content || typeof content.sensitiveWordIntercept !== 'boolean') errors.push({ field: 'contentReview.sensitiveWordIntercept', code: 'required' })
  if (!content || typeof content.nightQueueMode !== 'boolean') errors.push({ field: 'contentReview.nightQueueMode', code: 'required' })
  if (!content || !['relaxed', 'standard', 'strict'].includes(content.imageSensitivity)) errors.push({ field: 'contentReview.imageSensitivity', code: 'invalid' })
  if (!content || !Array.isArray(content.sensitiveWords) || content.sensitiveWords.length > 30) {
    errors.push({ field: 'contentReview.sensitiveWords', code: 'invalid' })
  } else {
    const invalidWord = content.sensitiveWords.some(word => typeof word !== 'string' || word.trim().length < 2 || word.trim().length > 20)
    const duplicateWord = new Set(content.sensitiveWords.map(word => typeof word === 'string' ? word.trim() : '')).size !== content.sensitiveWords.length
    if (invalidWord || duplicateWord) errors.push({ field: 'contentReview.sensitiveWords', code: 'invalid' })
  }
  return errors
}

export const useAdminSettingsStore = defineStore('admin-settings', () => {
  const auth = useAdminAuthStore()
  const audit = useAdminAuditStore()
  const contentReviewState = ref(copyContentReview(defaultContentReview))

  const settings = computed(() => deepFreeze({
    platform: {
      brandName: '信科 Link',
      serviceArea: '河南信息科技学院学生社区',
      platformNotice: '校园社区服务项目，不代表学校官方平台。',
    },
    contentReview: copyContentReview(contentReviewState.value),
    community: {
      controlsAvailable: false as const,
      note: '当前 Stitch 参考未展示此分段的展开配置项，本阶段不提供编辑控件。',
    },
    administrator: {
      account: maskAuditActor(auth.currentAccount),
      role: '管理员',
    },
    system: {
      environment: '本地演示环境',
      version: 'v2.4.0',
      resetsOnRefresh: true as const,
      persistenceNote: '配置只保存在当前前端内存中，刷新页面后恢复默认值。',
    },
  }))

  function appendAudit(event: AuditEventInput) {
    try { recordAdminAuditSafely(payload => audit.record(payload), event) } catch { /* Local audit is best-effort. */ }
  }

  function saveSettings(next: AdminSettings): SettingsSaveResult {
    const errors = validate(next)
    if (errors.length) {
      appendAudit({
        module: 'settings', action: 'save_rejected', targetType: 'configuration', outcome: 'business_rejected',
        changedFields: errors.map(error => error.field), summary: '设置校验未通过', resultCode: 'validation_failed',
      })
      return { ok: false, changedFields: [], errors }
    }

    const changedFields = editableFields(contentReviewState.value, next.contentReview)
    if (!changedFields.length) return { ok: true, changedFields: [], errors: [] }

    contentReviewState.value = copyContentReview(next.contentReview)
    appendAudit({
      module: 'settings', action: 'save', targetType: 'configuration', outcome: 'success',
      changedFields, summary: '本地演示配置已保存', resultCode: 'local_save',
    })
    return { ok: true, changedFields, errors: [] }
  }

  function resetToDefaults(): { changedFields: string[] } {
    const changedFields = editableFields(contentReviewState.value, defaultContentReview)
    if (!changedFields.length) return { changedFields: [] }

    contentReviewState.value = copyContentReview(defaultContentReview)
    appendAudit({
      module: 'settings', action: 'reset_defaults', targetType: 'configuration', outcome: 'success',
      changedFields, summary: '本地演示配置已恢复默认值', resultCode: 'local_reset',
    })
    return { changedFields }
  }

  return { settings, saveSettings, resetToDefaults }
})
