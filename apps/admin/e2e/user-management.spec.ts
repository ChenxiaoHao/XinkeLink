import { expect, test, type Page } from './api-fixture'
import fs from 'node:fs'
import path from 'node:path'

const screenshotDir = path.resolve('../../.local/admin-phase4')

async function enterDemo(page: Page, pathName: string) {
  await page.goto(pathName)
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByLabel('管理员账号').fill('demo-admin')
  await page.getByLabel('管理员密码').fill('demo-password')
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page).toHaveURL(new RegExp(`${pathName.replaceAll('/', '\\/')}$`))
  await expect(page.locator('.el-message')).toBeVisible()
  await expect(page.locator('.el-message')).toHaveCount(0)
}

async function waitForTable(page: Page) { await expect(page.locator('.el-loading-mask')).toHaveCount(0) }

async function approveUserAction(page: Page, reason: string) {
  await page.getByTestId('user-action-reason').fill(reason)
  await page.getByTestId('confirm-user-action').click()
  const dialog = page.getByRole('dialog', { name: '请再次确认用户处理' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '确认处理' }).click()
}

test('user profiles link content, filters, and reasoned restrictions with a release and temporary ban at 1366px', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.setViewportSize({ width: 1366, height: 760 })
  await enterDemo(page, '/admin/users')
  fs.mkdirSync(screenshotDir, { recursive: true })
  await expect(page.getByTestId('users-view')).toBeVisible()
  await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible()
  await expect(page.locator('.user-table .el-table__body-wrapper tbody tr')).toHaveCount(10)
  await waitForTable(page)
  await page.screenshot({ path: path.join(screenshotDir, 'users-1366.png'), animations: 'disabled' })
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.locator('.user-table .el-table__body-wrapper tbody tr')).toHaveCount(5)
  await page.getByRole('button', { name: '上一页' }).click()
  await page.setViewportSize({ width: 1440, height: 900 })
  await waitForTable(page)
  await page.locator('.main-scroll').evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: path.join(screenshotDir, 'users-1440.png'), animations: 'disabled' })

  await page.getByRole('textbox', { name: '搜索用户' }).fill('林知夏')
  await expect(page.locator('.user-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await page.getByTestId('open-user-detail').click()
  await expect(page.getByTestId('user-detail-drawer')).toBeVisible()
  await expect(page.getByText('用户自填资料，未经平台核实。')).toBeVisible()
  await expect(page.locator('.user-content-groups')).toContainText('帖子')
  await expect(page.locator('.user-content-groups')).toContainText('评论')
  await expect(page.locator('.user-content-groups')).toContainText('评论')
  await waitForTable(page)
  await page.screenshot({ path: path.join(screenshotDir, 'user-detail-1440.png'), animations: 'disabled' })

  await page.getByTestId('toggle-restriction-publish').click()
  await page.getByTestId('confirm-user-action').click()
  await expect(page.locator('.el-message').last()).toContainText('请填写处理原因')
  await approveUserAction(page, '复核后限制发布内容')
  await expect(page.locator('.user-detail-drawer .el-drawer__header')).toContainText('受限制')
  await page.getByTestId('user-detail-tabs').getByText('处理历史').click()
  await expect(page.locator('.user-history-item').first()).toContainText('限制发布内容')

  await page.getByTestId('lift-user-restrictions').click()
  await approveUserAction(page, '复核完成，解除发布限制')
  await expect(page.locator('.user-detail-drawer .el-drawer__header')).toContainText('正常')

  await page.getByTestId('ban-temporary').click()
  await approveUserAction(page, '连续发布违规内容，临时封禁')
  await expect(page.locator('.user-detail-drawer .el-drawer__header')).toContainText('临时封禁')
  await expect(page.getByTestId('toggle-restriction-publish')).toBeDisabled()
  await expect(page.getByTestId('lift-user-restrictions')).toBeEnabled()
  await expect(page.locator('.user-ban-until')).toContainText('临时封禁至')

  await page.getByRole('button', { name: '关闭用户详情' }).click()
  await page.getByRole('textbox', { name: '搜索用户' }).fill('')
  await page.getByTestId('user-restriction-filter').click()
  await page.getByRole('option', { name: '发布内容限制' }).click()
  await expect(page.locator('.user-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await page.getByTestId('user-restriction-filter').click()
  await page.getByRole('option', { name: '全部限制类型' }).click()
  await page.getByTestId('user-status-filter').click()
  await page.getByRole('option', { name: '临时封禁' }).click()
  await expect(page.locator('.user-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await expect(page.locator('.user-table .el-table__body-wrapper tbody tr').first()).toContainText('林知夏')
  await page.setViewportSize({ width: 1366, height: 460 })
  const main = page.locator('.main-scroll')
  const topbarY = (await page.locator('.admin-topbar').boundingBox())?.y
  const sidebarY = (await page.locator('.admin-sidebar').boundingBox())?.y
  expect(await main.evaluate(element => element.scrollHeight > element.clientHeight)).toBe(true)
  await main.evaluate(element => { element.scrollTop = 400 })
  expect(await main.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  expect(await page.evaluate(() => document.documentElement.scrollTop)).toBe(0)
  expect((await page.locator('.admin-topbar').boundingBox())?.y).toBe(topbarY)
  expect((await page.locator('.admin-sidebar').boundingBox())?.y).toBe(sidebarY)
  expect(errors).toEqual([])
})

test('report restrictions sync to user records and cannot be lifted after a permanent ban at 1440px', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 900 })
  await enterDemo(page, '/admin/reports')
  await page.getByRole('textbox', { name: '搜索举报' }).fill('R-2026102204')
  await expect(page.getByTestId('open-report-detail')).toHaveCount(1)
  await page.getByTestId('open-report-detail').click()
  await page.getByTestId('report-resolution-action').click()
  await page.getByRole('option', { name: '限制用户' }).click()
  await page.getByTestId('report-resolution-reason').fill('核实用户近期重复发布推广信息，限制账号功能。')
  await page.getByTestId('confirm-report-processing').click()
  const reportDialog = page.getByRole('dialog', { name: '确认处理举报？' })
  await reportDialog.getByRole('button', { name: '确认处理' }).click()
  await page.getByRole('button', { name: '关闭举报详情' }).click()

  await page.getByRole('link', { name: '用户管理' }).click()
  await page.getByRole('textbox', { name: '搜索用户' }).fill('2024****183')
  await expect(page.getByTestId('open-user-detail')).toHaveCount(1)
  await page.getByTestId('open-user-detail').click()
  await expect(page.locator('.user-detail-drawer .el-drawer__header')).toContainText('受限制')
  await page.getByTestId('user-detail-tabs').getByText('处理历史').click()
  await expect(page.locator('.user-history-item').first()).toContainText('举报审核限制用户')

  await page.getByTestId('ban-permanent').click()
  await approveUserAction(page, '多次违规，经审核决定永久封禁')
  await expect(page.locator('.user-detail-drawer .el-drawer__header')).toContainText('永久封禁')
  await expect(page.getByTestId('lift-user-restrictions')).toBeDisabled()
  await expect(page.getByTestId('toggle-restriction-message')).toBeDisabled()
  await page.getByTestId('user-detail-tabs').getByText('举报记录').click()
  await expect(page.locator('.user-report-item').filter({ hasText: 'R-2026102204' })).toContainText('R-2026102204')
  await page.getByRole('button', { name: '关闭用户详情' }).click()

  await page.getByRole('link', { name: '举报审核' }).click()
  await page.getByTestId('report-processed-tab').click()
  await page.getByRole('textbox', { name: '搜索举报' }).fill('R-2026102204')
  await expect(page.getByTestId('open-report-detail')).toHaveCount(1)
  await page.getByTestId('open-report-detail').click()
  await expect(page.locator('.report-original-content')).toContainText('永久封禁')
  expect(errors).toEqual([])
})
