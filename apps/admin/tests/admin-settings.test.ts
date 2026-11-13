import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuditStore } from './fixtures/admin-audit'
import { useAdminSettingsStore, type AdminSettings } from './fixtures/admin-settings'

describe('local demo system settings', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('shows approved read-only platform and community context with local demo defaults', () => {
    const store = useAdminSettingsStore()
    expect(store.settings).toMatchObject({
      platform: { brandName: '信科 Link', serviceArea: '河南信息科技学院学生社区' },
      community: { controlsAvailable: false },
      system: { environment: '本地演示环境', resetsOnRefresh: true },
    })
    expect(store.settings.contentReview.imageSensitivity).toBe('standard')
    expect(store.settings.contentReview.sensitiveWords.length).toBeGreaterThan(0)
    expect(store.settings.platform.brandName).toBe('信科 Link')
  })

  it('saves allowed values locally and logs changed field names without configuration values', () => {
    const store = useAdminSettingsStore()
    const audit = useAdminAuditStore()
    const secretKeyword = 'review-only-example'
    const next: AdminSettings = {
      ...store.settings,
      contentReview: {
        ...store.settings.contentReview,
        nightQueueMode: false,
        imageSensitivity: 'strict',
        sensitiveWords: [...store.settings.contentReview.sensitiveWords, secretKeyword],
      },
    }

    const result = store.saveSettings(next)
    expect(result).toMatchObject({ ok: true, errors: [] })
    expect(result.changedFields).toEqual(expect.arrayContaining([
      'contentReview.nightQueueMode', 'contentReview.imageSensitivity', 'contentReview.sensitiveWords',
    ]))
    expect(store.settings.contentReview.imageSensitivity).toBe('strict')
    expect(audit.events).toHaveLength(1)
    expect(audit.events[0]).toMatchObject({ module: 'settings', action: 'save', outcome: 'success' })
    expect(audit.events[0].changedFields).toContain('contentReview.imageSensitivity')
    expect(JSON.stringify(audit.events)).not.toContain(secretKeyword)
  })

  it('rejects invalid values without changing configuration and records only safe field paths', () => {
    const store = useAdminSettingsStore()
    const audit = useAdminAuditStore()
    const before = JSON.stringify(store.settings)
    const invalid = {
      ...store.settings,
      contentReview: {
        ...store.settings.contentReview,
        imageSensitivity: 'unapproved-value',
        sensitiveWords: ['', 'x'.repeat(50)],
      },
    } as unknown as AdminSettings

    const result = store.saveSettings(invalid)
    expect(result.ok).toBe(false)
    expect(result.errors.length).toBeGreaterThan(0)
    expect(JSON.stringify(store.settings)).toBe(before)
    expect(audit.events).toHaveLength(1)
    expect(audit.events[0].outcome).toBe('business_rejected')
    expect(JSON.stringify(audit.events)).not.toContain('unapproved-value')
    expect(JSON.stringify(audit.events)).not.toContain('x'.repeat(50))
  })

  it('does not create duplicate audit events for a no-change save', () => {
    const store = useAdminSettingsStore()
    expect(store.saveSettings(store.settings)).toMatchObject({ ok: true, changedFields: [] })
    expect(useAdminAuditStore().events).toHaveLength(0)
  })

  it('resets changed local values to defaults and records names only', () => {
    const store = useAdminSettingsStore()
    const audit = useAdminAuditStore()
    store.saveSettings({
      ...store.settings,
      contentReview: { ...store.settings.contentReview, nightQueueMode: false, sensitiveWords: ['临时规则词'] },
    })
    const result = store.resetToDefaults()

    expect(result.changedFields).toContain('contentReview.nightQueueMode')
    expect(store.settings.contentReview.nightQueueMode).toBe(true)
    expect(store.settings.contentReview.sensitiveWords).not.toEqual(['临时规则词'])
    expect(audit.events[0]).toMatchObject({ module: 'settings', action: 'reset_defaults', outcome: 'success' })
    expect(JSON.stringify(audit.events)).not.toContain('临时规则词')
  })

  it('starts a fresh Pinia instance with default settings and isolates audit writer failure', () => {
    const first = useAdminSettingsStore()
    first.saveSettings({ ...first.settings, contentReview: { ...first.settings.contentReview, nightQueueMode: false } })
    setActivePinia(createPinia())
    const fresh = useAdminSettingsStore()
    const audit = useAdminAuditStore()
    expect(fresh.settings.contentReview.nightQueueMode).toBe(true)
    vi.spyOn(audit, 'record').mockImplementation(() => { throw new Error('audit unavailable') })

    const result = fresh.saveSettings({ ...fresh.settings, contentReview: { ...fresh.settings.contentReview, nightQueueMode: false } })
    expect(result.ok).toBe(true)
    expect(fresh.settings.contentReview.nightQueueMode).toBe(false)
  })
})
