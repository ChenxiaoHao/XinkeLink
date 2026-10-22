import { describe, expect, it } from 'vitest'
import { readHealth } from '../src/services/health'

describe('server health boundary', () => {
  it('reads a successful response from the Java API', async () => {
    let requested = ''
    const fetcher = async (input: RequestInfo | URL) => {
      requested = String(input)
      return new Response(JSON.stringify({ application: 'xinkelink-server', status: 'UP' }))
    }
    await expect(readHealth(fetcher)).resolves.toEqual({
      application: 'xinkelink-server', status: 'UP',
    })
    expect(requested).toBe('/api/v1/health')
  })

  it('rejects an HTTP error instead of reporting a healthy server', async () => {
    const fetcher = async () => new Response('unavailable', { status: 503 })
    await expect(readHealth(fetcher)).rejects.toThrow('503')
  })

  it('propagates a network failure', async () => {
    const fetcher = async () => { throw new Error('network unavailable') }
    await expect(readHealth(fetcher)).rejects.toThrow('network unavailable')
  })

  it('rejects an HTTP success with an invalid health payload', async () => {
    const fetcher = async () => new Response(JSON.stringify({ status: 'DOWN' }))
    await expect(readHealth(fetcher)).rejects.toThrow('健康响应格式不正确')
  })
})
