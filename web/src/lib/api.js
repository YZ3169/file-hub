export class ApiError extends Error {
  constructor(code, message, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }

  /** 哪些错误值得重试：网络/5xx/分片校验失败/存储瞬时失败 */
  get retriable() {
    if (this.status >= 500 || this.status === 429) return true
    return [30004, 30005, 20003, 50000].includes(this.code)
  }
}

async function request(path, options = {}) {
  let res
  try {
    res = await fetch(path, options)
  } catch (e) {
    if (e.name === 'AbortError') throw e
    throw new ApiError(-1, '无法连接服务端：请确认 Java 服务已在 8081 端口启动', 0)
  }

  const text = await res.text()
  let body = null
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      throw new ApiError(50000, `服务端返回了非 JSON 响应（HTTP ${res.status}）`, res.status)
    }
  }

  if (!res.ok) {
    throw new ApiError(body?.code ?? res.status, body?.msg ?? `请求失败（HTTP ${res.status}）`, res.status)
  }
  if (!body || body.code === undefined) {
    throw new ApiError(50000, '服务端返回格式异常', res.status)
  }
  if (body.code !== 0) {
    throw new ApiError(body.code, body.msg || '请求失败', res.status)
  }
  return body.data
}

export const getConfig = () => request('/api/file/config')

export const checkInstant = (hash, size) =>
  request(`/api/file/check?hash=${encodeURIComponent(hash)}&size=${size}`)

export const initUpload = (payload) =>
  request('/api/file/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })

export const uploadChunk = ({ fileId, index, blob, chunkHash, signal }) => {
  const fd = new FormData()
  fd.append('fileId', fileId)
  fd.append('index', String(index))
  if (chunkHash) fd.append('chunkHash', chunkHash)
  fd.append('chunk', blob)
  return request('/api/file/chunk', { method: 'POST', body: fd, signal })
}

export const completeUpload = (fileId, signal) =>
  request(`/api/file/${fileId}/complete`, { method: 'POST', signal })

export const getStatus = (fileId) => request(`/api/file/${fileId}/status`)

export const listFiles = () => request('/api/file/list')

export const getStats = () => request('/api/file/stats')

export const deleteFile = (fileId) =>
  request(`/api/file/${encodeURIComponent(fileId)}`, { method: 'DELETE' })

export const downloadUrl = (fileId) => `/api/file/${encodeURIComponent(fileId)}/download`
