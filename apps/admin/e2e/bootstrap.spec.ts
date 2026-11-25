import { test, expect } from './api-fixture'
import fs from 'node:fs'
import path from 'node:path'

const screenshotDir = path.resolve('../../.local/admin-phase1')

test('menu navigation starts the next page at the top of its independent scroll area', async ({ page }) => {
  await enterDemo(page)
  await page.locator('.main-scroll').evaluate(element => { element.scrollTop = element.scrollHeight })
  expect(await page.locator('.main-scroll').evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  await page.getByRole('link', { name: '公告管理', exact: true }).click()
  await expect(page.getByTestId('announcements-view')).toBeVisible()
  await expect.poll(() => page.locator('.main-scroll').evaluate(element => element.scrollTop)).toBe(0)
})

test('retired features are absent from navigation and old bookmarks land on the dashboard', async ({ page }) => {
  await enterDemo(page)
  await expect(page.getByRole('link', { name: /内测|福袋/ })).toHaveCount(0)
  const search = page.getByRole('textbox', { name: '搜索管理菜单' })
  for (const keyword of ['内测', '福袋']) {
    await search.fill(keyword)
    await expect(page.getByText('没有匹配的管理菜单')).toBeVisible()
  }
  for (const path of ['/admin/beta', '/admin/lucky-bags']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/admin\/dashboard$/)
  }
})

async function enterDemo(page: import('@playwright/test').Page) {
  await page.goto('/admin/dashboard')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByLabel('管理员账号').fill('demo-admin')
  await page.getByLabel('管理员密码').fill('demo-password')
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
  await expect(page.getByTestId('dashboard-view')).toBeVisible()
  await expect(page.locator('.el-message')).toHaveCount(0)
}

test('login, protected routing, menu navigation and logout work in local demo mode', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/admin/dashboard')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page.getByText('请输入管理员账号和密码')).toBeVisible()
  await page.getByLabel('管理员账号').fill('demo-admin')
  await page.getByLabel('管理员密码').fill('demo-password')
  await page.getByRole('button', { name: '显示密码' }).click()
  await expect(page.getByLabel('管理员密码')).toHaveAttribute('type', 'text')
  await page.getByRole('button', { name: '隐藏密码' }).click()
  await page.getByText('记住账号', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '记住账号' })).toBeChecked()
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page).toHaveURL(/\/admin\/dashboard$/)
  await expect(page.getByTestId('dashboard-view')).toBeVisible()
  await expect(page.getByRole('heading', { name: '数据概览', exact: true })).toBeVisible()
  await expect(page.getByText('3,842')).toBeVisible()

  await page.getByRole('link', { name: '评论管理' }).click()
  await expect(page).toHaveURL(/\/admin\/comments$/)
  await expect(page.getByTestId('comments-view')).toBeVisible()
  await expect(page.getByRole('heading', { name: '评论与互动管理' })).toBeVisible()
  await page.getByRole('link', { name: '数据概览' }).click()
  await expect(page).toHaveURL(/\/admin\/dashboard$/)

  await page.getByRole('button', { name: '管理员菜单' }).click()
  await page.getByText('退出登录').click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('管理员账号')).toHaveValue('demo-admin')
  await page.goto('/admin/settings')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  expect(errors).toEqual([])

  fs.mkdirSync(screenshotDir, { recursive: true })
  await page.screenshot({ path: path.join(screenshotDir, 'login-1366.png'), fullPage: true, animations: 'disabled' })
})

test('dashboard adapts at target widths and sidebar/header/content scrolling stay independent', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await enterDemo(page)

  await page.getByRole('button', { name: '30天' }).click()
  await expect(page.locator('.trend-chart')).toHaveAttribute('aria-label', /30天每日发帖量/)
  await page.getByRole('button', { name: '本学期' }).click()
  await expect(page.locator('.trend-chart')).toHaveAttribute('aria-label', /本学期每日发帖量/)
  await page.getByRole('button', { name: '7天' }).click()
  await page.getByRole('button', { name: '社区举报' }).click()
  await expect(page.locator('.activity-table tbody tr')).toHaveCount(1)
  await page.getByRole('button', { name: '全部动态' }).click()
  await expect(page.locator('.activity-table tbody tr')).toHaveCount(4)

  fs.mkdirSync(screenshotDir, { recursive: true })
  for (const width of [1366, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.getByTestId('dashboard-view')).toBeVisible()
    await page.locator('.main-scroll').evaluate(element => { element.scrollTop = 0 })
    await page.screenshot({ path: path.join(screenshotDir, `dashboard-${width}.png`), fullPage: false, animations: 'disabled' })
    const bounds = await page.locator('.admin-shell').boundingBox()
    expect(bounds?.width).toBe(width)
  }

  await page.setViewportSize({ width: 1366, height: 520 })
  const main = page.locator('.main-scroll')
  const menu = page.locator('.sidebar-menu')
  const sidebar = page.locator('.admin-sidebar')
  const topbar = page.locator('.admin-topbar')
  const footer = page.locator('.sidebar-footer')
  const mainTopBefore = (await topbar.boundingBox())?.y
  const footerTopBefore = (await footer.boundingBox())?.y
  const sidebarTopBefore = (await sidebar.boundingBox())?.y
  await main.evaluate(element => { element.scrollTop = 500 })
  expect(await main.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  expect(await page.evaluate(() => document.documentElement.scrollTop)).toBe(0)
  expect((await topbar.boundingBox())?.y).toBe(mainTopBefore)
  expect((await footer.boundingBox())?.y).toBe(footerTopBefore)
  expect((await sidebar.boundingBox())?.y).toBe(sidebarTopBefore)

  const menuMetrics = await menu.evaluate(element => ({ scrollHeight: element.scrollHeight, clientHeight: element.clientHeight, height: getComputedStyle(element).height, items: element.querySelectorAll('.nav-item').length }))
  expect(menuMetrics.items).toBe(11)
  expect(menuMetrics.scrollHeight > menuMetrics.clientHeight, JSON.stringify(menuMetrics)).toBe(true)
  await menu.evaluate(element => { element.scrollTop = element.scrollHeight })
  expect(await menu.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  expect(await page.evaluate(() => document.documentElement.scrollTop)).toBe(0)
  expect(errors).toEqual([])
})
