import { test, expect } from '@playwright/test'
import path from 'node:path'

test('the rendered admin connects to the running Java service', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '信科 Link 管理后台' })).toBeVisible()
  await page.getByRole('button', { name: '检查服务连接' }).click()
  await expect(page.getByText('服务连接正常')).toBeVisible()
  await page.screenshot({ path: path.resolve('../../.local/admin-connected.png'), fullPage: true, animations: 'disabled' })
  expect(errors).toEqual([])
})

test('an unavailable service shows a failure and supports retry', async ({ page }) => {
  await page.route('**/api/v1/health', (route) => route.fulfill({
    status: 503, contentType: 'text/plain', body: 'unavailable',
  }))
  await page.goto('/')
  await page.getByRole('button', { name: '检查服务连接' }).click()
  await expect(page.getByText('连接失败，请确认服务端已启动后重试')).toBeVisible()
  await expect(page.getByRole('button', { name: '检查服务连接' })).toBeEnabled()
  await page.screenshot({ path: path.resolve('../../.local/admin-unavailable.png'), fullPage: true, animations: 'disabled' })
  await page.unroute('**/api/v1/health')
  await page.getByRole('button', { name: '检查服务连接' }).click()
  await expect(page.getByText('服务连接正常')).toBeVisible()
})
