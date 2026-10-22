export interface ServerHealth {
  application: 'xinkelink-server'
  status: 'UP'
}

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

export async function readHealth(fetcher: Fetcher = fetch): Promise<ServerHealth> {
  const response = await fetcher('/api/v1/health', {
    signal: AbortSignal.timeout(5000),
    cache: 'no-store',
  })
  if (!response.ok) throw new Error('服务返回 HTTP ' + response.status)
  const payload: unknown = await response.json()
  if (
    typeof payload !== 'object' || payload === null ||
    !('application' in payload) || payload.application !== 'xinkelink-server' ||
    !('status' in payload) || payload.status !== 'UP'
  ) {
    throw new Error('健康响应格式不正确')
  }
  return { application: payload.application, status: payload.status }
}
