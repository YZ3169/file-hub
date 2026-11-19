import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  getPublicConfig,
  getPublicShareMeta,
  listFiles,
  login,
  uploadChunk,
  verifyShareCode,
} from './api'
import { clearSession, getToken, onUnauthorized, setSession } from './auth'

function response(body, status = 200) {
  const text = typeof body === 'string' ? body : JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    text: () => Promise.resolve(text),
  }
}

function ok(data) {
  return response({ code: 0, msg: 'ok', data })
}

describe('api request', () => {
  let fetchMock

  beforeEach(() => {
    clearSession({ notify: false })
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    clearSession({ notify: false })
  })

  it('登录请求不携带 Authorization，并解包 data', async () => {
    fetchMock.mockResolvedValue(ok({ token: 't', user: { id: 1 } }))

    await expect(login('alice', 'secret')).resolves.toEqual({
      token: 't',
      user: { id: 1 },
    })

    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/auth/login')
    expect(options.method).toBe('POST')
    expect(options.headers.get('Authorization')).toBeNull()
    expect(JSON.parse(options.body)).toEqual({ username: 'alice', password: 'secret' })
  })

  it('已登录请求自动带 Bearer Token', async () => {
    setSession({ token: 'tok-9', user: { id: 1, username: 'alice', role: 'USER' } })
    fetchMock.mockResolvedValue(ok({ items: [], total: 0, page: 1, size: 10 }))

    await listFiles({ page: 2, size: 5, keyword: '报告', status: 1, sort: 'size_desc' })

    const [path, options] = fetchMock.mock.calls[0]
    expect(options.headers.get('Authorization')).toBe('Bearer tok-9')
    expect(path).toContain('/api/file/list?')
    expect(path).toContain('page=2')
    expect(path).toContain('size=5')
    expect(path).toContain(`keyword=${encodeURIComponent('报告')}`)
    expect(path).toContain('status=1')
    expect(path).toContain('sort=size_desc')
  })

  it('业务错误码转成 ApiError，保留 HTTP 200', async () => {
    fetchMock.mockResolvedValue(response({ code: 10005, msg: '无权访问该文件' }))

    const error = await listFiles().catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe(10005)
    expect(error.status).toBe(200)
    expect(error.message).toBe('无权访问该文件')
    expect(error.retriable).toBe(false)
  })

  it('401 清空本地会话并通知订阅者', async () => {
    setSession({ token: 'expired', user: { id: 1, username: 'alice', role: 'USER' } })
    const listener = vi.fn()
    const unsubscribe = onUnauthorized(listener)
    fetchMock.mockResolvedValue(response({ code: 40001, msg: '未登录' }, 401))

    await expect(listFiles()).rejects.toMatchObject({ code: 40001, status: 401 })

    expect(getToken()).toBeNull()
    expect(listener).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('429 是可重试错误', async () => {
    fetchMock.mockResolvedValue(response({ code: 40002, msg: '请求过于频繁' }, 429))

    const error = await listFiles().catch((e) => e)

    expect(error.status).toBe(429)
    expect(error.retriable).toBe(true)
  })

  it('非 JSON 响应给出明确错误', async () => {
    fetchMock.mockResolvedValue(response('<html>oops</html>', 200))

    const error = await listFiles().catch((e) => e)

    expect(error.code).toBe(50000)
    expect(error.message).toContain('非 JSON')
  })

  it('缺少 code 的 JSON 视为格式异常', async () => {
    fetchMock.mockResolvedValue(response({ data: {} }))

    const error = await listFiles().catch((e) => e)

    expect(error.code).toBe(50000)
    expect(error.message).toContain('格式异常')
  })

  it('超时映射成 50001，且可重试', async () => {
    fetchMock.mockRejectedValue(new DOMException('请求超时', 'AbortError'))

    const error = await listFiles().catch((e) => e)

    expect(error.code).toBe(50001)
    expect(error.status).toBe(0)
    expect(error.retriable).toBe(true)
  })

  it('网络错误映射成 -1', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'))

    const error = await listFiles().catch((e) => e)

    expect(error.code).toBe(-1)
    expect(error.status).toBe(0)
    expect(error.message).toContain('无法连接服务端')
  })

  it('上传分片使用 FormData 并带 fileId/index/chunkHash', async () => {
    fetchMock.mockResolvedValue(ok({ uploaded: true }))
    const blob = new Blob(['hello'])

    await uploadChunk({ fileId: 'f-1', index: 2, blob, chunkHash: 'abc' })

    const [path, options] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/file/chunk')
    expect(options.method).toBe('POST')
    const body = options.body
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('fileId')).toBe('f-1')
    expect(body.get('index')).toBe('2')
    expect(body.get('chunkHash')).toBe('abc')
    expect(body.get('chunk')).toBeInstanceOf(Blob)
  })

  it('匿名分享接口即使本地有会话也不带 Authorization', async () => {
    setSession({ token: 'tok-9', user: { id: 1, username: 'alice', role: 'USER' } })
    fetchMock.mockResolvedValue(ok({ state: 'ACTIVE' }))

    await getPublicConfig()
    await getPublicShareMeta('A'.repeat(43))
    fetchMock.mockResolvedValue(ok({ fileId: 'f-1' }))
    await verifyShareCode('A'.repeat(43), 'K7Q2')

    for (const [, options] of fetchMock.mock.calls) {
      expect(options.headers.get('Authorization')).toBeNull()
    }
    const [verifyPath, verifyOptions] = fetchMock.mock.calls[2]
    expect(verifyPath).toBe(`/api/public/share/${'A'.repeat(43)}/verify`)
    expect(verifyOptions.method).toBe('POST')
    expect(JSON.parse(verifyOptions.body)).toEqual({ code: 'K7Q2' })
  })
})
