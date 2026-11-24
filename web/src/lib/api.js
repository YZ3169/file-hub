import { clearSession, getToken } from './auth'

export class ApiError extends Error {
  constructor(code, message, status = 0, retryAfterMs = 0) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    // setTimeout 超过有符号 32 位整数会溢出成近乎立即重试。
    this.retryAfterMs = Number.isFinite(retryAfterMs)
      ? Math.min(Math.max(0, retryAfterMs), 2_147_483_647)
      : 0
  }

  /** 哪些错误值得重试：网络/5xx/超时/分片校验失败/存储瞬时失败 */
  get retriable() {
    if (this.status >= 500 || this.status === 429) return true
    return [-1, 50001, 30004, 30005, 20003, 50000].includes(this.code)
  }
}

const DEFAULT_TIMEOUT = 30_000
const UPLOAD_TIMEOUT = 120_000
const MERGE_TIMEOUT = 600_000

function parseRetryAfter(value) {
  const text = value?.trim()
  if (!text) return 0
  if (/^\d+$/.test(text)) return Number(text) * 1000
  // HTTP 日期包含月份名称；不要把负数、小数等无效秒数当作日期。
  return /[a-z]/i.test(text) ? Date.parse(text) - Date.now() : 0
}

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
  const {
    signal: externalSignal,
    timeout = DEFAULT_TIMEOUT,
    auth: withAuth = true,
    ...rest
  } = options
  const combined = combineSignals(externalSignal, timeout)

  const headers = new Headers(rest.headers || {})
  const requestToken = withAuth ? getToken() : null
  if (requestToken) headers.set('Authorization', `Bearer ${requestToken}`)

  let res
  try {
    res = await fetch(path, { ...rest, headers, signal: combined.signal })
    const retryAfterMs = parseRetryAfter(res.headers?.get('Retry-After'))
    const text = await res.text()
    let body = null
    if (text) {
      try {
        body = JSON.parse(text)
      } catch {
        throw new ApiError(
          50000,
          `服务端返回了非 JSON 响应（HTTP ${res.status}）`,
          res.status,
          retryAfterMs,
        )
      }
    }

    if (!res.ok) {
      // 401 代表会话失效；清空本地会话并通知 App 回登录页。
      // 登录/注册接口用 auth:false，密码错误不会被误判成「登录过期」。
      // 账号切换后，旧请求迟到的 401 不能撤销新会话。
      if (res.status === 401 && withAuth && getToken() === requestToken) {
        clearSession()
      }
      throw new ApiError(
        body?.code ?? res.status,
        body?.msg ?? `请求失败（HTTP ${res.status}）`,
        res.status,
        retryAfterMs,
      )
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
    throw new ApiError(-1, '无法连接服务端：请确认 file-hub 已启动', 0)
  } finally {
    combined.cleanup()
  }
}

// ---- 认证 ----

export const register = (username, password) =>
  request('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
    auth: false,
  })

export const login = (username, password) =>
  request('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
    auth: false,
  })

export const me = () => request('/api/auth/me')

export const logout = () => request('/api/auth/logout', { method: 'POST' })

// ---- 文件 ----

export const getConfig = () => request('/api/file/config')

export const checkInstant = (hash, size) =>
  request(`/api/file/check?hash=${encodeURIComponent(hash)}&size=${size}`)

export const initUpload = (payload, signal) =>
  request('/api/file/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal,
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

export const getStatus = (fileId, signal) =>
  request(`/api/file/${fileId}/status`, { timeout: 60_000, signal })

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
  scope = null,
  signal,
} = {}) => {
  const qs = new URLSearchParams({ page: String(page), size: String(size), sort })
  if (keyword) qs.set('keyword', keyword)
  if (status !== null && status !== undefined && status !== '') qs.set('status', String(status))
  if (scope) qs.set('scope', scope)
  return request(`/api/file/list?${qs.toString()}`, { signal })
}

export const getStats = (scope = null) =>
  request(`/api/file/stats${scope ? `?scope=${encodeURIComponent(scope)}` : ''}`)

export const deleteFile = (fileId) =>
  request(`/api/file/${encodeURIComponent(fileId)}`, { method: 'DELETE', timeout: 60_000 })

/**
 * 浏览器下载票据。Bearer Token 无法附加到 <a href>，先换一张 5 分钟的
 * 短期票据，再把返回的 url 交给浏览器。
 */
export const createDownloadTicket = (fileId) =>
  request(`/api/file/${encodeURIComponent(fileId)}/download-ticket`)

// ---- 提取码分享（管理端，需登录）----

export const createShare = (fileId, expiresInDays = 7) =>
  request('/api/share', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileId, expiresInDays }),
  })

export const listShares = ({ page = 1, size = 20 } = {}) =>
  request(`/api/share/mine?page=${page}&size=${size}`)

export const cancelShare = (token) =>
  request(`/api/share/${encodeURIComponent(token)}`, { method: 'DELETE' })

export const resetShareCode = (token) =>
  request(`/api/share/${encodeURIComponent(token)}/reset-code`, { method: 'POST' })

// ---- 提取码分享（匿名，绝不带 Authorization）----
// 匿名页可能同时存在已登录会话；带上 Bearer 只会让 401 误清会话，没有意义。

export const getPublicConfig = () => request('/api/file/config', { auth: false })

export const getPublicShareMeta = (token) =>
  request(`/api/public/share/${encodeURIComponent(token)}`, { auth: false })

export const verifyShareCode = (token, code) =>
  request(`/api/public/share/${encodeURIComponent(token)}/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
    auth: false,
  })

// ---- 管理员：完整性检查 ----

/**
 * 扫描已完成文件。deep=true 会复算每个文件的 SHA-256，大库里可能要跑很久，
 * 因此单独给一个长超时；浅扫描只 stat，几百条通常秒回。
 */
export const scanIntegrity = ({ deep = false, limit = 100 } = {}) =>
  request(`/api/admin/integrity/scan?deep=${deep}&limit=${limit}`, {
    timeout: deep ? 600_000 : 60_000,
  })

export const repairIntegrity = (fileIds) =>
  request('/api/admin/integrity/repair', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileIds }),
    timeout: 600_000,
  })
