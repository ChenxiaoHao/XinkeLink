import { expect, test } from './api-fixture'
import type { Page } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

const screenshotDir = path.resolve('../../.local/admin-phase3')

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


test('report review filters, processes linked content once, and records handling at 1440px', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 900 })
  await enterDemo(page, '/admin/reports')
  fs.mkdirSync(screenshotDir, { recursive: true })
  await expect(page.getByTestId('reports-view')).toBeVisible()
  await expect(page.getByRole('heading', { name: '违规举报处置中心' })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索举报' }).fill('R-2026102302')
  await page.getByTestId('open-report-detail').first().click()
  await page.getByTestId('report-resolution-action').click()
  await page.getByRole('option', { name: '隐藏帖子' }).click()
  await page.getByPlaceholder('请填写核实依据与处理说明').fill('核实后确认内容违反社区规范，隐藏展示。')
  await page.getByTestId('confirm-report-processing').click()
  await page.getByRole('dialog', { name: '确认处理举报？' }).getByRole('button', { name: '确认处理' }).click()
  await expect(page.getByRole('dialog', { name: '确认处理举报？' })).toBeHidden()
  await page.getByRole('button', { name: '关闭此对话框' }).click()
  await page.getByRole('link', { name: '帖子管理' }).click()
  await page.getByRole('textbox', { name: '搜索帖子' }).fill('期末复习资料整理好了')
  await expect(page.locator('.post-table .el-table__body-wrapper tbody tr')).toHaveCount(1)
  await expect(page.locator('.post-table .el-table__body-wrapper tbody tr').first()).toContainText('已隐藏')
  expect(errors).toEqual([])
})
