import { expect, test, type Page } from './api-fixture'
import fs from 'node:fs'
import path from 'node:path'

const screenshotDir = path.resolve('../../.local/admin-phase6')

async function enterDemo(page: Page, route: string) {
  await page.goto(route)
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByLabel('管理员账号').fill('demo-admin')
  await page.getByLabel('管理员密码').fill('demo-password')
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page).toHaveURL(new RegExp(`${route.replaceAll('/', '\\/')}$`))
  await expect(page.locator('.el-message').last()).toContainText('登录成功')
  await expect(page.locator('.el-message')).toHaveCount(0)
}

test('operation log shows audited management events, filters, details and exports only the visible safe result', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.setViewportSize({ width: 1366, height: 900 })
  await enterDemo(page, '/admin/logs')
  fs.mkdirSync(screenshotDir, { recursive: true })
  await expect(page.getByTestId('logs-view')).toBeVisible()
  await expect(page.getByTestId('audit-demo-notice')).toContainText('服务器持久化')
  await expect(page.getByTestId('audit-empty-state')).toBeVisible()

  await page.getByRole('link', { name: '帖子管理' }).click()
  await expect(page.getByTestId('posts-view')).toBeVisible()
  await page.getByRole('button', { name: '隐藏帖子' }).first().click()
  await expect(page.locator('.el-message').last()).toContainText('已隐藏')
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.getByRole('link', { name: '操作日志' }).click()
  await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await page.screenshot({ path: path.join(screenshotDir, 'operation-log-list-1366.png'), animations: 'disabled' })

  await page.getByRole('textbox', { name: '搜索操作日志' }).fill('帖子状态')
  await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await page.getByRole('button', { name: '查看操作详情' }).first().click()
  await expect(page.getByTestId('audit-detail-drawer')).toBeVisible()
  await expect(page.getByText('日志详情')).toBeVisible()
  await expect(page.getByTestId('audit-detail-drawer').getByText('帖子状态已更新')).toBeVisible()
  await page.screenshot({ path: path.join(screenshotDir, 'operation-log-detail-1366.png'), animations: 'disabled' })
  await page.getByRole('button', { name: '关闭操作日志详情' }).click()
  await expect(page.getByTestId('audit-detail-drawer')).toBeHidden()

  await page.getByRole('textbox', { name: '搜索操作日志' }).fill('不存在的审计关键词')
  await expect(page.getByTestId('audit-no-results')).toBeVisible()
  await page.getByTestId('audit-clear-filters').click()
  await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(1)

  const downloadPromise = page.waitForEvent('download')
  await page.getByTestId('audit-export').click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/operation-log.*\.csv$/)
  const downloadPath = await download.path()
  expect(downloadPath).toBeTruthy()
  const csv = fs.readFileSync(downloadPath!, 'utf8')
  expect(csv.startsWith('\uFEFF')).toBe(true)
  expect(csv).toContain('帖子状态已更新')
  expect(pageErrors).toEqual([])
})

test('operation log keeps sidebar and topbar fixed while its right content scrolls at 1440px', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 760 })
  await enterDemo(page, '/admin/logs')
  await page.getByRole('link', { name: '帖子管理' }).click()
  await page.getByRole('button', { name: '隐藏帖子' }).first().click()
  await page.getByRole('link', { name: '操作日志' }).click()
  const main = page.locator('.main-scroll')
  const topbar = page.locator('.admin-topbar')
  const sidebar = page.locator('.admin-sidebar')
  const topBefore = (await topbar.boundingBox())?.y
  const sidebarBefore = (await sidebar.boundingBox())?.y
  expect(await main.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await main.evaluate(element => { element.scrollTop = 500 })
  expect(await page.evaluate(() => document.documentElement.scrollTop)).toBe(0)
  expect((await topbar.boundingBox())?.y).toBe(topBefore)
  expect((await sidebar.boundingBox())?.y).toBe(sidebarBefore)
})

test('system settings edits local review configuration, confirms reset and resets after reload at 1440px', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 900 })
  await enterDemo(page, '/admin/settings')
  fs.mkdirSync(screenshotDir, { recursive: true })
  await expect(page.getByTestId('settings-view')).toBeVisible()
  await expect(page.getByText('信科 Link', { exact: true }).last()).toBeVisible()
  await expect(page.getByTestId('community-config-readonly')).toContainText('只读')
  await expect(page.getByTestId('settings-simulation-notice')).toContainText('不执行自动内容拦截')
  await page.screenshot({ path: path.join(screenshotDir, 'system-settings-1440.png'), animations: 'disabled' })

  await page.locator('.settings-control-row').filter({ hasText: '夜间自动队列模式' }).locator('.el-switch').click()
  await page.getByRole('radio', { name: /^高严（严格拦截）/ }).check()
  await page.getByRole('textbox', { name: '新增审核关键词' }).fill('phase6-keyword')
  await page.getByTestId('settings-add-keyword').click()
  await page.getByTestId('settings-save').click()
  await expect(page.locator('.el-message').last()).toContainText('设置已保存')
  await expect(page.locator('.el-message')).toHaveCount(0)

  await page.getByRole('link', { name: '操作日志' }).click()
  await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await expect(page.locator('.audit-table')).toContainText('系统设置')
  await page.getByRole('button', { name: '查看操作详情' }).click()
  await expect(page.getByTestId('audit-detail-drawer')).toContainText('contentReview.imageSensitivity')
  await expect(page.getByTestId('audit-detail-drawer')).not.toContainText('phase6-keyword')
  await page.getByRole('button', { name: '关闭操作日志详情' }).click()
  await expect(page.getByTestId('audit-detail-drawer')).toBeHidden()

  await page.getByRole('link', { name: '系统设置' }).click()
  await page.getByTestId('settings-reset').click()
  await expect(page.getByRole('dialog', { name: '确认恢复默认值' })).toBeVisible()
  await page.screenshot({ path: path.join(screenshotDir, 'settings-reset-confirm-1440.png'), animations: 'disabled' })
  await page.getByRole('button', { name: '取消' }).click()
  await expect(page.getByLabel('夜间队列模式')).not.toBeChecked()
  await page.getByTestId('settings-reset').click()
  await page.getByRole('button', { name: '确认恢复默认值' }).click()
  await expect(page.locator('.el-message').last()).toContainText('已恢复默认值')
  await expect(page.getByLabel('夜间队列模式')).toBeChecked()
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('radio', { name: /^适中（标准模式-推荐）/ })).toBeChecked()
  expect(pageErrors).toEqual([])
})

test('system settings remains usable at 1366px and preserves the fixed admin shell', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 760 })
  await enterDemo(page, '/admin/settings')
  await expect(page.getByTestId('settings-view')).toBeVisible()
  const main = page.locator('.main-scroll')
  const topbar = page.locator('.admin-topbar')
  const sidebar = page.locator('.admin-sidebar')
  const topBefore = (await topbar.boundingBox())?.y
  const sidebarBefore = (await sidebar.boundingBox())?.y
  expect(await main.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await main.evaluate(element => { element.scrollTop = 400 })
  expect(await page.evaluate(() => document.documentElement.scrollTop)).toBe(0)
  expect((await topbar.boundingBox())?.y).toBe(topBefore)
  expect((await sidebar.boundingBox())?.y).toBe(sidebarBefore)
  await main.evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: path.join(screenshotDir, 'system-settings-1366.png'), animations: 'disabled' })
})

test('management actions from all prior modules appear once in the shared audit log', async ({ page }) => {
  test.setTimeout(90_000)
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 900 })
  await enterDemo(page, '/admin/logs')

  await page.getByRole('link', { name: '帖子管理' }).click()
  await page.getByRole('button', { name: '隐藏帖子' }).first().click()
  await expect(page.locator('.el-message').last()).toContainText('已隐藏')
  await expect(page.locator('.el-message')).toHaveCount(0)

  await page.getByRole('link', { name: '评论管理' }).click()
  await page.getByTestId('open-comment-context').first().click()
  await page.getByTestId('context-hide-comment').click()
  await expect(page.getByTestId('context-restore-comment')).toBeVisible()
  await page.getByRole('button', { name: '关闭评论上下文' }).click()

  await page.getByRole('link', { name: '举报审核' }).click()
  await page.getByRole('textbox', { name: '搜索举报' }).fill('R-2026102302')
  await expect(page.getByTestId('open-report-detail')).toHaveCount(1)
  await page.getByTestId('open-report-detail').click()
  await page.getByTestId('report-resolution-action').click()
  await page.getByRole('option', { name: '驳回举报' }).click()
  await page.getByPlaceholder('请填写核实依据与处理说明').fill('复核确认该内容不构成违规。')
  await page.getByTestId('confirm-report-processing').click()
  await page.getByRole('dialog', { name: '确认处理举报？' }).getByRole('button', { name: '确认处理' }).click()
  await expect(page.getByTestId('report-history-panel')).toBeVisible()
  await page.getByRole('button', { name: '关闭此对话框' }).click()

  await page.getByRole('link', { name: '用户管理' }).click()
  await page.getByRole('textbox', { name: '搜索用户' }).fill('林知夏')
  await expect(page.getByTestId('open-user-detail')).toHaveCount(1)
  await page.getByTestId('open-user-detail').click()
  await page.getByTestId('toggle-restriction-publish').click()
  await page.getByTestId('user-action-reason').fill('复核后暂时限制发布内容。')
  await page.getByTestId('confirm-user-action').click()
  await page.getByRole('dialog', { name: '请再次确认用户处理' }).getByRole('button', { name: '确认处理' }).click()
  await expect(page.locator('.el-message').last()).toContainText('已完成：限制发布内容')
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.getByRole('button', { name: '关闭用户详情' }).click()

  await page.getByRole('link', { name: '公告管理' }).click()
  await page.getByTestId('create-announcement').click()
  await page.getByTestId('announcement-title').fill('Phase 6 审计联动公告草稿')
  await page.getByTestId('announcement-body').fill('用于确认公告操作已写入当前会话日志。')
  await page.getByTestId('announcement-category').click()
  await page.getByRole('option', { name: '活动信息' }).click()
  await page.getByTestId('announcement-scope').click()
  await page.getByRole('option', { name: '论坛', exact: true }).click()
  await page.getByTestId('save-announcement-draft').click()
  await expect(page.locator('.el-message').last()).toContainText('草稿已保存')
  await expect(page.locator('.el-message')).toHaveCount(0)

  await page.getByRole('link', { name: '通知管理' }).click()
  await page.getByTestId('create-notification').click()
  await page.getByTestId('notification-title').fill('Phase 6 审计联动通知草稿')
  await page.getByTestId('notification-body').fill('用于确认通知操作已写入当前会话日志，不会发送真实消息。')
  await page.getByTestId('save-notification-draft').click()
  await expect(page.locator('.el-message').last()).toContainText('草稿已保存')
  await expect(page.locator('.el-message')).toHaveCount(0)

  await page.getByRole('link', { name: '系统设置' }).click()
  await page.getByRole('textbox', { name: '新增审核关键词' }).fill('invalid-keyword-longer-than-twenty')
  await page.getByTestId('settings-add-keyword').click()
  await page.getByTestId('settings-save').click()
  await expect(page.getByTestId('settings-validation-errors')).toBeVisible()
  await expect(page.locator('.el-message').last()).toContainText('请检查标出的配置项')
  await expect(page.locator('.el-message')).toHaveCount(0)

  await page.getByRole('link', { name: '操作日志' }).click()
  await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(7)
  await page.screenshot({ path: path.join(screenshotDir, 'operation-log-list-1440.png'), animations: 'disabled' })

  const expectedModules = [
    ['帖子管理', 1], ['评论管理', 1], ['举报审核', 1],
    ['用户管理', 1], ['公告管理', 1], ['通知管理', 1], ['系统设置', 1],
  ] as const
  for (const [moduleName, expectedCount] of expectedModules) {
    await test.step(`filter audit events by ${moduleName}`, async () => {
      const moduleFilter = page.getByTestId('audit-module-filter')
      await moduleFilter.click()
      await page.getByRole('option', { name: moduleName, exact: true }).click()
      await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(expectedCount)
      await expect(page.locator('.audit-table')).toContainText(moduleName)
      if (moduleName === '系统设置') await expect(page.locator('.audit-table')).toContainText('业务拒绝')
    })
  }

  await test.step('clear module filters and open a safe event detail', async () => {
    await page.getByTestId('audit-clear-filters').click()
    await expect(page.locator('.audit-table .el-table__body-wrapper tbody tr')).toHaveCount(7)
    await page.getByRole('button', { name: '查看操作详情' }).first().click()
    await expect(page.getByTestId('audit-detail-drawer')).toBeVisible()
    await page.screenshot({ path: path.join(screenshotDir, 'operation-log-detail-1440.png'), animations: 'disabled' })
  })
  expect(pageErrors).toEqual([])
})
