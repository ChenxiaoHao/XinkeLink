import { test, expect } from './api-fixture'
import type { BetaManagement } from '../src/services/admin-beta'

const fresh = (): BetaManagement => ({ phase: 'open', version: 0, total: 0, used: 0, remaining: 0, codes: [] })
const batch = (): BetaManagement => ({
  phase: 'beta', version: 1, total: 30, used: 0, remaining: 30,
  codes: Array.from({ length: 30 }, (_, i) => ({ id: String(i + 1), code: `XK-BROWSER-${String(i + 1).padStart(6, '0')}`, status: 'unused', user: null, usedAt: null, createdAt: '2026-11-08T00:00:00Z' })),
})
async function login(page: import('@playwright/test').Page) {
  await page.goto('/admin/beta')
  await page.getByLabel('管理员账号').fill('demo-admin')
  await page.getByLabel('管理员密码').fill('demo-password')
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page.getByTestId('beta-view')).toBeVisible()
}

test('initializes one batch, shows bound accounts, copies unused codes and confirms versioned phase changes', async ({ page, context }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  let state = fresh()
  const writes: Array<{ method: string; body: unknown }> = []
  await page.route('**/api/v1/admin/beta{,/**}', async route => {
    const request = route.request(), method = request.method()
    if (method !== 'GET') {
      const body = request.postDataJSON()
      writes.push({ method, body })
      if (method === 'POST') { expect(body).toEqual({}); if (!state.total) state = batch() }
      else { expect(body.expectedVersion).toBe(state.version); state = { ...state, phase: body.phase, version: state.version + 1 } }
    }
    await route.fulfill({ json: { code: 'OK', data: state } })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await login(page)
  await expect(page.locator('.sidebar-menu .nav-item')).toHaveCount(11)
  await expect(page.getByTestId('beta-total')).toHaveText('0')
  await page.getByRole('button', { name: '初始化 30 个内测码', exact: true }).click()
  await expect(page.getByTestId('beta-total')).toHaveText('30')
  await expect(page.getByTestId('beta-remaining')).toHaveText('30')
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('内测中')
  expect(writes).toEqual([{ method: 'POST', body: {} }])

  state = { ...state, used: 2, remaining: 28, codes: state.codes.map((row, index) => index < 2 ? { ...row, status: 'used', user: { id: String(901 + index), nickname: ['内测验证甲', '内测验证乙'][index]! }, usedAt: '2026-11-08T01:30:00Z' } : row) }
  await page.getByRole('button', { name: '刷新内测数据' }).click()
  await expect(page.getByTestId('beta-used')).toHaveText('2')
  await expect(page.getByTestId('beta-remaining')).toHaveText('28')
  await expect(page.getByTestId('beta-table')).toContainText('内测验证甲')
  await expect(page.getByTestId('beta-table')).toContainText('901')
  await expect(page.getByTestId('beta-table')).toContainText('2026-11-08 09:30')
  await page.getByRole('combobox', { name: '内测码状态' }).selectOption('used')
  await expect(page.locator('[data-testid="beta-table"] tbody tr')).toHaveCount(2)
  await expect(page.getByRole('button', { name: /复制内测码/ })).toHaveCount(0)
  await page.getByRole('textbox', { name: '搜索内测码或账号' }).fill('902')
  await expect(page.locator('[data-testid="beta-table"] tbody tr')).toHaveCount(1)
  await expect(page.getByTestId('beta-table')).toContainText('内测验证乙')
  await page.getByRole('textbox', { name: '搜索内测码或账号' }).fill('不存在的账号')
  await expect(page.getByTestId('beta-no-results')).toBeVisible()
  await page.getByRole('textbox', { name: '搜索内测码或账号' }).fill('')
  await page.getByRole('combobox', { name: '内测码状态' }).selectOption('unused')
  await expect(page.locator('[data-testid="beta-table"] tbody tr')).toHaveCount(28)
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: '复制内测码 3', exact: true }).click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('XK-BROWSER-000003')

  await page.getByRole('button', { name: '切换为正式开放' }).click()
  await expect(page.getByRole('dialog')).toContainText('所有账号')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(writes).toHaveLength(1)
  await page.getByRole('button', { name: '切换为正式开放' }).click()
  await page.getByRole('button', { name: '确认切换', exact: true }).click()
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('正式开放')
  await expect(page.getByTestId('beta-remaining')).toHaveText('28')
  await page.getByRole('button', { name: '重新开启内测' }).click()
  await expect(page.getByRole('dialog')).toContainText('未激活账号')
  await page.getByRole('button', { name: '确认切换', exact: true }).click()
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('内测中')
  expect(writes.slice(1)).toEqual([{ method: 'PATCH', body: { phase: 'open', expectedVersion: 1 } }, { method: 'PATCH', body: { phase: 'beta', expectedVersion: 2 } }])
  await page.getByRole('combobox', { name: '内测码状态' }).selectOption('all')
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.locator('.main-scroll').evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: 'test-results/beta-management-1440.png', animations: 'disabled' })
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.screenshot({ path: 'test-results/beta-management-1280.png', animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/beta-management-390.png', animations: 'disabled' })
  expect(errors).toEqual([])
})

test('shows loading and failed states honestly, disables mutations until a successful reload', async ({ page }) => {
  let fail = true
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/v1/admin/beta', async route => {
    await gate
    await route.fulfill(fail ? { status: 503, json: { code: 'UNAVAILABLE', message: '内测服务暂不可用' } } : { json: { code: 'OK', data: batch() } })
  })
  await login(page)
  await expect(page.getByTestId('beta-total')).toHaveText('—')
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('阶段加载中')
  release()
  await expect(page.getByTestId('beta-error')).toContainText('内测服务暂不可用')
  await expect(page.getByTestId('beta-total')).toHaveText('—')
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('阶段获取失败')
  await expect(page.getByRole('button', { name: '初始化 30 个内测码', exact: true })).toHaveCount(0)
  fail = false
  await page.getByRole('button', { name: '刷新内测数据' }).click()
  await expect(page.getByTestId('beta-total')).toHaveText('30')
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('内测中')
  fail = true
  await page.getByRole('button', { name: '刷新内测数据' }).click()
  await expect(page.getByTestId('beta-error')).toContainText('上次成功加载的数据')
  await expect(page.getByTestId('beta-phase-badge')).toHaveText('阶段获取失败')
  await expect(page.getByRole('button', { name: '切换为正式开放' })).toBeDisabled()
})
