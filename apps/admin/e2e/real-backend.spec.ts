import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

// 显式启用才连接本地集成服务；不拦截请求，不导入演示数据。
test('real HTTP core workflows and admin browser integration', async ({ page, request }) => {
  test.skip(!process.env.XINKELINK_E2E_CREDENTIAL_FILE, '需要本地私有测试账号文件和显式测试微信适配器')
  test.setTimeout(120000)
  const credentials = JSON.parse(fs.readFileSync(process.env.XINKELINK_E2E_CREDENTIAL_FILE!, 'utf8'))
  const dir = path.resolve('../../.local/backend-day5/screenshots')
  fs.mkdirSync(dir, { recursive: true })
  const evidence: Array<{ method: string; path: string; status: number }> = []
  const browserErrors: string[] = []
  page.on('pageerror', error => browserErrors.push(error.message))
  const uid = crypto.randomUUID().slice(0, 8)
  async function api(method: string, url: string, body?: unknown, token?: string, status = 200) {
    const response = await request.fetch(`/api/v1${url}`, { method, data: body, headers: token ? { Authorization: `Bearer ${token}` } : {} })
    evidence.push({ method, path: url, status: response.status() })
    const json = await response.json()
    expect(response.status(), `${method} ${url}: ${json.code}`).toBe(status)
    return json.data
  }
  const alice = await api('POST', '/auth/wechat', { code: 'day5-alice' })
  const bob = await api('POST', '/auth/wechat', { code: 'day5-bob' })
  const author = alice.accessToken, reader = bob.accessToken
  await api('GET', '/admin/posts', undefined, reader, 403)
  await api('POST', '/posts', { content: '未登录', category: 'campus', anonymous: false, imageIds: [], clientRequestId: crypto.randomUUID() }, undefined, 401)
  const post = await api('POST', '/posts', { content: `Day5 浏览器联调帖子 ${uid}`, category: 'study', anonymous: false, imageIds: [], clientRequestId: crypto.randomUUID() }, author, 201)
  const comment = await api('POST', `/posts/${post.id}/comments`, { content: `联调评论 ${uid}`, clientRequestId: crypto.randomUUID() }, reader, 201)
  expect(comment.commentCount).toBe(1)
  expect((await api('PUT', `/posts/${post.id}/like`, undefined, reader)).likeCount).toBe(1)
  expect((await api('PUT', `/posts/${post.id}/like`, undefined, reader)).changed).toBe(false)
  await api('PUT', `/posts/${post.id}/favorite`, undefined, reader)
  const detail = await api('GET', `/posts/${post.id}`, undefined, reader)
  expect(detail.liked).toBe(true); expect(detail.favorited).toBe(true)
  await api('PATCH', `/posts/${post.id}`, { content: '越权编辑', expectedVersion: detail.version }, reader, 403)
  const picture = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a06kAAAAASUVORK5CYII=', 'base64')
  const upload = await request.post('/api/v1/images', { headers: { Authorization: `Bearer ${author}` }, multipart: { purpose: 'post', file: { name: 'test.png', mimeType: 'image/png', buffer: picture } } })
  expect(upload.status()).toBe(201)
  expect((await upload.json()).data.id).toBeTruthy()
  const conversation = await api('POST', '/conversations', { peerUserId: alice.me.id }, reader, 201)
  const message = await api('POST', `/conversations/${conversation.id}/messages`, { text: `你好 ${uid}`, clientMessageId: crypto.randomUUID() }, reader, 201)
  const history = await api('GET', `/conversations/${conversation.id}/messages`, undefined, author)
  expect(history.items.some((item: { id: string }) => item.id === message.id)).toBe(true)
  await api('PUT', `/conversations/${conversation.id}/read`, { readUpToSequence: message.sequence }, author)
  const report = await api('POST', '/reports', { targetType: 'post', targetId: post.id, reasonCode: 'other', description: `开发验收举报 ${uid}`, evidenceImageIds: [], clientRequestId: crypto.randomUUID() }, reader, 201)

  await page.goto('/admin/dashboard')
  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('管理员账号').fill(credentials.account)
  await page.getByLabel('管理员密码').fill(credentials.password)
  const loginResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/admin/auth/login'))
  await page.getByRole('button', { name: /进入管理后台/ }).click()
  const login = (await (await loginResponse).json()).data
  expect(login.accessToken).toBeTruthy()
  const admin = login.accessToken
  await expect(page.getByTestId('dashboard-view')).toBeVisible()
  await page.setViewportSize({ width: 1366, height: 812 })
  await page.screenshot({ path: path.join(dir, 'dashboard-real-1366.png') })
  const resolved = await api('POST', `/admin/reports/${report.id}/resolution`, { action: 'hide_post', reason: '仅本地联调验证', expectedVersion: 0, confirmed: true }, admin)
  expect(resolved).toBeTruthy()
  await api('GET', `/posts/${post.id}`, undefined, reader, 404)
  await api('POST', `/admin/reports/${report.id}/resolution`, { action: 'hide_post', reason: '重复处理', expectedVersion: 0, confirmed: true }, admin, 409)
  const announcement = await api('POST', '/admin/announcements', { title: `联调社区公告 ${uid}`, body: '信科 Link 社区开发验收，非学校官方通知。', category: 'community', scope: 'all', clientRequestId: crypto.randomUUID() }, admin, 201)
  await api('POST', `/admin/announcements/${announcement.id}/publish`, { expectedVersion: announcement.version, confirmed: true }, admin)
  expect((await api('GET', '/announcements?scope=forum', undefined, reader)).items.some((item: { id: string }) => item.id === announcement.id)).toBe(true)
  expect((await api('GET', '/notifications?type=announcement', undefined, reader)).items.length).toBeGreaterThan(0)
  const user = await api('GET', `/admin/users/${alice.me.id}`, undefined, admin)
  const restricted = await api('POST', `/admin/users/${alice.me.id}/restrictions`, { channel: 'publish', enabled: true, reason: '联调验证', expectedVersion: user.version, confirmed: true }, admin)
  await api('POST', '/posts', { content: '应被拒绝', category: 'campus', anonymous: false, imageIds: [], clientRequestId: crypto.randomUUID() }, author, 403)
  await api('POST', `/admin/users/${alice.me.id}/release`, { reason: '完成联调恢复测试账号', expectedVersion: restricted.version, confirmed: true }, admin)

  for (const width of [1366, 1440]) {
    await page.setViewportSize({ width, height: 812 })
    for (const route of ['posts', 'comments', 'reports', 'users', 'announcements', 'notifications', 'logs', 'settings']) {
      const endpoint = route === 'logs' ? 'audit-events' : route
      const loaded = page.waitForResponse(response => response.url().includes(`/api/v1/admin/${endpoint}`) && response.request().method() === 'GET')
      await page.goto(`/admin/${route}`)
      expect((await loaded).status(), route).toBe(200)
      await expect(page.locator('.el-loading-mask')).toHaveCount(0)
      await page.screenshot({ path: path.join(dir, `${route}-real-${width}.png`) })
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      const top = (await page.locator('.admin-topbar').boundingBox())!.y
      const side = (await page.locator('.admin-sidebar').boundingBox())!.y
      await page.locator('.main-scroll').evaluate(element => { element.scrollTop = element.scrollHeight })
      expect((await page.locator('.admin-topbar').boundingBox())!.y).toBe(top)
      expect((await page.locator('.admin-sidebar').boundingBox())!.y).toBe(side)
      expect(await page.evaluate(() => document.documentElement.scrollTop)).toBe(0)
    }
  }
  // 刷新必须经安全cookie+CSRF恢复，不接受localStorage演示登录标志。
  const refreshed = page.waitForResponse(response => response.url().endsWith('/api/v1/admin/auth/refresh'))
  await page.reload(); expect((await refreshed).status()).toBe(200)
  await expect(page.getByTestId('settings-view')).toBeVisible()
  expect(await page.evaluate(() => Object.keys(localStorage).some(key => /password|access.?token|refresh.?token/i.test(key)))).toBe(false)
  const csv = await request.get('/api/v1/admin/audit-events/export', { headers: { Authorization: `Bearer ${admin}` } })
  expect(csv.status()).toBe(200)
  expect((await csv.text()).includes('hide_post')).toBe(false) // 摘要记录受控命令resolve，不复制请求正文。
  fs.writeFileSync(path.join(dir, '../real-http-evidence.json'), JSON.stringify({ evidence, browserErrors, fixtures: { postId: post.id, reportId: report.id, announcementId: announcement.id }, wechat: 'test-adapter-only' }, null, 2))
  expect(browserErrors).toEqual([])
})

