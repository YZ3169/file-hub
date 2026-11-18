export class ApiError extends Error {
  constructor(code, message, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }

  /** 哪些错误值得重试：网络/5xx/超时/分片校验失败/存储瞬时失败 */
  get retriable() {
    if (this.status >= 500 || this.status === 429) return true
    return [50001, 30004, 30005, 20003, 50000].includes(this.code)
  }
}

const DEFAULT_TIMEOUT = 30_000
const UPLOAD_TIMEOUT = 120_000
const MERGE_TIMEOUT = 600_000

/**
 * 把「用户主动取消」和「超时」两个 signal 合到一起。
 *
 * 取消（暂停/删除任务）必须让 fetch 立刻中断；超时则要变成一个可重试的 ApiError。
 * 支持 AbortSignal.any 的浏览器直接用组合信号，不支持时手工转发。
 */
function combineSignals(external, timeoutMs) {
  const signals = []
  let timer = null
  let timeoutController = null

  if (external) signals.push(external)

  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    signals.push(AbortSignal.timeout(timeoutMs))
  } else {
    timeoutController = new AbortController()
    timer = setTimeout(() => {
      timeoutController.abort(new DOMException('请求超时', 'TimeoutError'))
    }, timeoutMs)
    signals.push(timeoutController.signal)
  }

  let signal
  if (signals.length === 1) {
    signal = signals[0]
  } else if (typeof AbortSignal.any === 'function') {
    signal = AbortSignal.any(signals)
  } else {
    const controller = new AbortController()
    for (const s of signals) {
      if (s.aborted) {
        controller.abort(s.reason)
        break
      }
      s.addEventListener('abort', () => controller.abort(s.reason), { once: true })
    }
    signal = controller.signal
  }

  return {
    signal,
    cleanup: () => {
      if (timer) clearTimeout(timer)
    },
  }
}

async function request(path, options = {}) {
  const { signal: externalSignal, timeout = DEFAULT_TIMEOUT, ...fetchOptions } = options
  const combined = combineSignals(externalSignal, timeout)

  let res
  try {
    res = await fetch(path, { ...fetchOptions, signal: combined.signal })
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
  } catch (e) {
    // 用户主动暂停/取消：原样抛出，调用方靠 AbortError 识别
    if (e.name === 'AbortError') {
      if (externalSignal?.aborted) throw e
      throw new ApiError(50001, `请求超时（${Math.round(timeout / 1000)} 秒）`, 0)
    }
    if (e.name === 'TimeoutError') {
      throw new ApiError(50001, `请求超时（${Math.round(timeout / 1000)} 秒）`, 0)
    }
    if (e instanceof ApiError) throw e
    throw new ApiError(-1, '无法连接服务端：请确认 Java 服务已在 8081 端口启动', 0)
  } finally {
    combined.cleanup()
  }
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
  return request('/api/file/chunk', {
    method: 'POST',
    body: fd,
    signal,
    timeout: UPLOAD_TIMEOUT,
  })
}

export const completeUpload = (fileId, signal) =>
  request(`/api/file/${fileId}/complete`, {
    method: 'POST',
    signal,
    timeout: MERGE_TIMEOUT,
  })

export const getStatus = (fileId) => request(`/api/file/${fileId}/status`, { timeout: 60_000 })

/**
 * 分页文件列表。
 * @param {object} query page（从 1 开始）、size（1–100）、keyword、status（0/1/null）、sort
 */
export const listFiles = ({
  page = 1,
  size = 10,
  keyword = '',
  status = null,
  sort = 'time_desc',
  signal,
} = {}) => {
  const qs = new URLSearchParams({ page: String(page), size: String(size), sort })
  if (keyword) qs.set('keyword', keyword)
  if (status !== null && status !== undefined && status !== '') qs.set('status', String(status))
  return request(`/api/file/list?${qs.toString()}`, { signal })
}

export const getStats = () => request('/api/file/stats')

export const deleteFile = (fileId) =>
  request(`/api/file/${encodeURIComponent(fileId)}`, { method: 'DELETE', timeout: 60_000 })

export const downloadUrl = (fileId) => `/api/file/${encodeURIComponent(fileId)}/download`
