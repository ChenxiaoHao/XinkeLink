import { test, expect } from './api-fixture'
import type { Page } from '@playwright/test'

async function login(page: Page) {
  await page.goto('/admin/ai/knowledge')
  await page.getByLabel('管理员账号').fill('demo-admin')
  await page.getByLabel('管理员密码').fill('demo-password')
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  await expect(page.getByTestId('knowledge-view')).toBeVisible()
}

test('HTTP fixture: extract, review, save draft, explicit publication, update and disable', async ({ page }) => {
  let rows: any[] = []
  const writes: Array<{ path: string; body: any }> = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/admin/ai/knowledge{,/**}', async route => {
    const request = route.request(), path = new URL(request.url()).pathname, method = request.method()
    if (method === 'GET') return route.fulfill({ json: { code: 'OK', data: { items: rows, ready: true } } })
    if (path.endsWith('/extract')) {
      expect(request.headers()['content-type']).toContain('multipart/form-data; boundary=')
      return route.fulfill({ json: { code: 'OK', data: { body: '第一条：学生应遵守学校管理规定。', filename: 'rules.txt', originalBase64: 'dGV4dA==', warnings: ['验收桩文本，请核对原始来源。'] } } })
    }
    const body = request.postData() ? request.postDataJSON() : undefined
    writes.push({ path, body })
    if (path.endsWith('/publish')) { expect(rows[0].reviewed).toBe(true); rows[0].status = 'published'; rows[0].activeVersionId = 'v1' }
    else if (path.endsWith('/disable')) { rows[0].status = 'disabled'; rows[0].activeVersionId = '' }
    else if (method === 'PUT') { Object.assign(rows[0], body, { status: 'draft' }) }
    else rows = [{ ...body, id: '15', status: 'draft', activeVersionId: '', warnings: [] }]
    await route.fulfill({ json: { code: 'OK', data: rows[0] } })
  })
  await login(page)
  await page.getByRole('button', { name: '新建资料' }).click()
  await page.getByLabel('上传知识文件').setInputFiles({ name: 'rules.txt', mimeType: 'text/plain', buffer: Buffer.from('text') })
  await expect(page.getByLabel('资料校对正文')).toHaveValue('第一条：学生应遵守学校管理规定。')
  expect(writes).toHaveLength(0)
  await page.getByLabel('资料标题', { exact: true }).fill('验收资料：学校管理规定')
  await page.getByLabel('资料来源', { exact: true }).fill('https://hbcet.hpu.edu.cn/info/1/2.htm')
  await page.getByLabel('资料版本', { exact: true }).fill('2026版')
  await page.getByLabel('资料有效性说明').fill('隔离验收文本，本学年适用')
  await page.getByLabel('确认已人工校对正文和资料信息').check()
  await page.getByLabel('资料版本', { exact: true }).fill('2026修订版')
  await expect(page.getByLabel('确认已人工校对正文和资料信息')).not.toBeChecked()
  await page.getByLabel('确认已人工校对正文和资料信息').check()
  await page.screenshot({ path: '../../.local/link-ai-20261117/admin-editor-1280.png', fullPage: true })
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(writes).toHaveLength(1)
  expect(writes[0]?.body.originalBase64).toBe('dGV4dA==')
  await page.getByRole('button', { name: '发布', exact: true }).click()
  expect(writes).toHaveLength(1)
  await page.getByRole('button', { name: '确认发布', exact: true }).click()
  await expect(page.locator('.el-table__body')).toContainText('已发布')
  await page.getByRole('button', { name: '校对 / 更新', exact: true }).click()
  await page.getByLabel('资料校对正文').fill('第一条：更新后的正文。')
  await page.getByLabel('确认已人工校对正文和资料信息').check()
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.locator('.el-table__body')).toContainText('原发布版本仍生效')
  await page.getByRole('button', { name: '停用', exact: true }).click()
  await page.getByRole('button', { name: '确认停用', exact: true }).click()
  await expect(page.locator('.el-table__body')).toContainText('已停用')
  expect(writes.map(item => item.path)).toEqual(['/api/v1/admin/ai/knowledge', '/api/v1/admin/ai/knowledge/15/publish', '/api/v1/admin/ai/knowledge/15', '/api/v1/admin/ai/knowledge/15/disable'])
  expect(errors).toEqual([])
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.locator('.el-message')).toHaveCount(0)
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.screenshot({ path: `../../.local/link-ai-20261117/admin-knowledge-${width}.png`, fullPage: true })
  }
})

test('HTTP fixture: extraction and list errors remain errors with a working retry', async ({ page }) => {
  let failList = true
  await page.route('**/api/v1/admin/ai/knowledge{,/**}', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ status: failList ? 503 : 200, json: failList ? { code: 'UNAVAILABLE', message: '知识资料读取失败' } : { code: 'OK', data: { items: [], ready: false } } })
    return route.fulfill({ status: 422, json: { code: 'EMPTY_TEXT', message: '未提取到文字，请使用文字版 PDF' } })
  })
  await login(page)
  await expect(page.getByRole('button', { name: '新建资料' })).toBeDisabled()
  await expect(page.getByText('知识资料读取失败', { exact: true })).toBeVisible()
  await expect(page.getByText('检索状态未知', { exact: true })).toBeVisible()
  failList = false
  await page.getByLabel('刷新知识资料').click()
  await expect(page.getByText('检索索引未就绪', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建资料' }).click()
  await page.getByLabel('上传知识文件').setInputFiles({ name: 'scan.pdf', mimeType: 'application/pdf', buffer: Buffer.from('scanned') })
  await expect(page.getByText('未提取到文字，请使用文字版 PDF', { exact: true })).toBeVisible()
  await expect(page.getByLabel('资料校对正文')).toHaveValue('')
})

test('HTTP fixture: reports a ready index with or without an active version', async ({ page }) => {
  let rows: Array<{ id: string; title: string; body: string; status: string; activeVersionId: string }> = []
  await page.route('**/api/v1/admin/ai/knowledge', route => route.fulfill({ json: { code: 'OK', data: { items: rows, ready: true } } }))
  await login(page)
  await expect(page.getByText('检索索引已就绪 · 当前列表暂无发布版本', { exact: true })).toBeVisible()
  rows = [{ id: '15', title: '校规资料', body: '第一条', status: 'draft', activeVersionId: 'v1' }]
  await page.getByLabel('刷新知识资料').click()
  await expect(page.getByText('检索索引已就绪', { exact: true })).toBeVisible()
  await expect(page.locator('.el-table__body')).toContainText('原发布版本仍生效')
  await page.getByRole('button', { name: '新建资料' }).click()
  await expect(page.getByText('上传文字版文件（不超过 5 MiB）', { exact: true })).toBeVisible()
})
