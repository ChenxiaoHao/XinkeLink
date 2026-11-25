import { afterEach, expect, it, vi } from 'vitest'
import { adminAi } from '../src/services/admin-ai'

const settings = {
  enabled: true,
  revision: 1,
  models: [{
    stage: 'chat', model: 'qwen-flash', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    keyConfigured: true, apiKey: 'must-not-enter-view-state',
  }],
}
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }))

afterEach(() => vi.unstubAllGlobals())

it('retains only public provider metadata from the dashboard response', async () => {
  vi.stubGlobal('fetch', async () => ok({ settings, today: { tokens: 17 }, records: [] }))
  const result = await adminAi.dashboard()
  expect(result.today.tokens).toBe(17)
  expect(result.settings.models[0]).toEqual({
    stage: 'chat', model: 'qwen-flash', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', keyConfigured: true,
  })
})

it('only submits an API key when an administrator enters a replacement', async () => {
  const requests: RequestInit[] = []
  vi.stubGlobal('fetch', async (_url: string, request: RequestInit) => { requests.push(request); return ok(settings) })
  const model = { model: 'qwen-flash', baseUrl: settings.models[0]!.baseUrl, revision: 1 }
  await adminAi.edit('chat', { ...model, apiKey: '  ' })
  await adminAi.edit('chat', { ...model, apiKey: 'replacement-only' })
  expect(JSON.parse(String(requests[0]?.body))).toEqual(model)
  expect(JSON.parse(String(requests[1]?.body))).toEqual({ ...model, apiKey: 'replacement-only' })
})
