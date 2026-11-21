import { markRaw, reactive } from 'vue'
import * as api from '../lib/api'
import { ApiError } from '../lib/api'
import { auth, clearSession, onUnauthorized, setSession } from '../lib/auth'
import { Sha256 } from '../lib/sha256'
import {
  fingerprintOf,
  findPendingTask,
  getCachedHash,
  getTheme,
  loadPendingTasks,
  removeCachedHash,
  removePendingTask,
  savePendingTask,
  setCachedHash,
  setStorageUser,
  setTheme,
} from '../lib/storage'

const MAX_CHUNK_RETRIES = 4
const MAX_HEAL_ROUNDS = 2
const ACTIVE_STATES = new Set(['queued', 'hashing', 'checking', 'uploading', 'merging'])
const FINISHED_STATES = new Set(['done', 'instant', 'canceled'])
let workspaceEpoch = 0
let workspaceToken = auth.token

class StoppedError extends Error {
  constructor(reason = 'stopped') {
    super(reason)
    this.name = 'StoppedError'
  }
}

function emptyStats() {
  return { doneFiles: 0, uploadingFiles: 0, logicalBytes: 0, physicalBytes: 0, savedBytes: 0 }
}

function uid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export const state = reactive({
  booted: false,
  online: true,
  config: { chunkSize: 5 * 1024 * 1024, backend: '', desktop: false },
  tasks: [],
  files: [],
  filesLoading: false,
  filesError: '',
  downloads: [],
  filePage: {
    page: 1,
    size: 10,
    total: 0,
    keyword: '',
    status: null,
    sort: 'time_desc',
  },
  stats: emptyStats(),
  concurrency: 4,
  toasts: [],
  processing: false,
  pendingResumable: [],
  theme: getTheme(),
})

// Token 失效（401）时 api 层会清空会话并触发这里：回到登录页，别让上传队列继续跑
onUnauthorized(() => {
  resetWorkspace()
  toast('登录已过期，请重新登录', 'warn')
})

let toastSeq = 0

export function toast(text, type = 'info') {
  const id = ++toastSeq
  state.toasts.push({ id, text, type })
  setTimeout(() => {
    const i = state.toasts.findIndex((t) => t.id === id)
    if (i >= 0) state.toasts.splice(i, 1)
  }, 4500)
}

export function applyTheme() {
  document.documentElement.dataset.theme = state.theme
}

export function toggleTheme() {
  state.theme = state.theme === 'dark' ? 'light' : 'dark'
  setTheme(state.theme)
  applyTheme()
}

function refreshPending() {
  state.pendingResumable = loadPendingTasks()
}

function isStopped(task) {
  return !isCurrentTask(task) || task.canceled || task.paused || task.controller?.signal.aborted
}

function isCurrentTask(task) {
  return task._workspaceEpoch === workspaceEpoch && task._token === auth.token
}

function isStopError(e) {
  return e?.name === 'AbortError' || e?.name === 'StoppedError'
}

function stopError(task) {
  return new StoppedError(task.canceled ? 'canceled' : 'paused')
}

function sleep(ms, task) {
  return new Promise((resolve, reject) => {
    if (isStopped(task)) {
      reject(stopError(task))
      return
    }
    const signal = task.controller?.signal
    let timer
    const cleanup = () => signal?.removeEventListener('abort', onAbort)
    const onAbort = () => {
      clearTimeout(timer)
      cleanup()
      reject(stopError(task))
    }
    timer = setTimeout(() => {
      cleanup()
      resolve()
    }, ms)
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function newTask(file, fingerprint, saved) {
  return {
    id: uid(),
    _workspaceEpoch: workspaceEpoch,
    _token: auth.token,
    file: markRaw(file),
    fingerprint,
    name: file.name,
    size: file.size,
    lastModified: file.lastModified,
    state: 'queued',
    message: '',
    errorCode: 0,
    hash: saved?.hash || null,
    hashProgress: 0,
    hashMs: 0,
    hashFromCache: Boolean(saved?.hash),
    fileId: saved?.fileId || null,
    chunkSize: 0,
    chunkTotal: 0,
    instant: false,
    chunkStates: [],
    retryTotal: 0,
    healRounds: 0,
    completedBytes: 0,
    uploadedChunks: 0,
    skippedChunks: 0,
    speed: 0,
    eta: 0,
    paused: false,
    canceled: false,
    controller: null,
    startedAt: 0,
    finishedAt: 0,
    _running: false,
    _resumeRequested: false,
    _chunkError: null,
    _lastPersist: 0,
    _samples: markRaw([]),
  }
}

function bytesForIndexes(task, indexes) {
  if (!task.chunkSize) return 0
  let bytes = 0
  for (const i of indexes) {
    bytes += Math.min(task.chunkSize, task.size - i * task.chunkSize)
  }
  return bytes
}

function updateSpeed(task) {
  const samples = task._samples
  const now = performance.now()
  while (samples.length > 1 && samples[0].t < now - 5000) samples.shift()
  if (samples.length >= 2) {
    const first = samples[0]
    const last = samples[samples.length - 1]
    const dt = (last.t - first.t) / 1000
    if (dt > 0.2) {
      task.speed = Math.max(0, (last.bytes - first.bytes) / dt)
      const remaining = Math.max(0, task.size - task.completedBytes)
      task.eta = task.speed > 1 ? remaining / task.speed : 0
    }
  }
}

function persistProgress(task) {
  const now = Date.now()
  if (now - task._lastPersist < 1500) return
  task._lastPersist = now
  savePendingTask(task)
  refreshPending()
}

function createHashWorker() {
  if (typeof Worker === 'undefined') return null
  try {
    return new Worker(new URL('../workers/hashWorker.js', import.meta.url), { type: 'module' })
  } catch (e) {
    console.warn('当前环境不支持 Worker，哈希回退到主线程', e)
    return null
  }
}

/** 把 Worker 的 progress/done/error 消息包装成 Promise，并在暂停/取消时立即中断 */
function hashFileInWorker(task, worker) {
  return new Promise((resolve, reject) => {
    const signal = task.controller?.signal
    let settled = false

    const finish = (fn, value) => {
      if (settled) return
      settled = true
      signal?.removeEventListener('abort', onAbort)
      worker.terminate()
      fn(value)
    }
    const onAbort = () => finish(reject, stopError(task))

    if (signal?.aborted) {
      onAbort()
      return
    }
    signal?.addEventListener('abort', onAbort, { once: true })
    worker.onmessage = (event) => {
      const msg = event.data || {}
      if (msg.type === 'progress') {
        task.hashProgress = Math.min(1, Number(msg.value) || 0)
      } else if (msg.type === 'done') {
        task.hashProgress = 1
        finish(resolve, { hash: msg.hash, ms: msg.ms })
      }
    }
    worker.onerror = (event) => {
      event.preventDefault?.()
      finish(reject, new Error(`哈希线程失败：${event.message || '未知错误'}`))
    }
    worker.postMessage({ type: 'hash', file: task.file, step: 4 * 1024 * 1024 })
  })
}

async function hashFile(task) {
  const step = 4 * 1024 * 1024
  task.state = 'hashing'
  task.hashProgress = 0
  task.message = '正在增量计算 SHA-256（内存占用与文件大小无关）'

  if (task.size === 0) {
    task.hashProgress = 1
    return new Sha256().digestHex()
  }

  // 优先放到 Worker：主线程只收进度，不读盘、不算哈希，页面不会卡住
  const worker = createHashWorker()
  if (worker) {
    try {
      const { hash, ms } = await hashFileInWorker(task, worker)
      task.hashMs = ms || 0
      return hash
    } catch (e) {
      if (isStopError(e)) throw e
      // Worker 自身异常（浏览器限制/CSP 等）时退回主线程，别让整个上传失败
      console.warn('Worker 哈希失败，回退主线程', e)
    }
  }

  const hasher = new Sha256()
  const started = performance.now()
  for (let offset = 0; offset < task.size; offset += step) {
    if (isStopped(task)) throw stopError(task)
    const blob = task.file.slice(offset, Math.min(offset + step, task.size))
    const buf = await blob.arrayBuffer()
    hasher.update(new Uint8Array(buf))
    task.hashProgress = Math.min(1, (offset + step) / task.size)
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  task.hashMs = performance.now() - started
  task.hashProgress = 1
  return hasher.digestHex()
}

function toHex(bytes) {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/** 直接对已经读进内存的分片字节算摘要，避免再把 Blob 读一遍 */
async function sha256OfBuffer(buffer) {
  if (globalThis.crypto?.subtle) {
    const digest = await globalThis.crypto.subtle.digest('SHA-256', buffer)
    return toHex(new Uint8Array(digest))
  }
  const hasher = new Sha256()
  hasher.update(new Uint8Array(buffer))
  return hasher.digestHex()
}

async function callWithRetry(fn, task, attempts = 3) {
  let lastError
  for (let i = 0; i < attempts; i++) {
    if (isStopped(task)) throw stopError(task)
    try {
      const result = await fn()
      if (isStopped(task)) throw stopError(task)
      return result
    } catch (e) {
      if (isStopped(task)) throw stopError(task)
      lastError = e
      if (isStopError(e)) throw e
      const canRetry = !(e instanceof ApiError) || e.retriable
      if (i === attempts - 1 || !canRetry) throw e
      await sleep(600 * 2 ** i + Math.random() * 300, task)
    }
  }
  throw lastError
}

async function uploadOneChunk(task, index) {
  const start = index * task.chunkSize
  const end = Math.min(start + task.chunkSize, task.size)
  const expectedBytes = end - start

  // 只从文件读一次：把分片读成 ArrayBuffer，摘要和上传都用这一份字节。
  // 之前是「slice 算摘要 → 再把同一个 Blob 交给 FormData」，同一片要读两遍盘。
  let uploadBlob
  let chunkHash
  try {
    const blob = task.file.slice(start, end)
    const buffer = await blob.arrayBuffer()
    chunkHash = await sha256OfBuffer(buffer)
    uploadBlob = new Blob([buffer])
  } catch (e) {
    task.chunkStates[index] = 'failed'
    const err = new Error(`分片 ${index + 1} 读取失败：${e.message}`)
    err.code = 0
    throw err
  }

  for (let attempt = 0; attempt <= MAX_CHUNK_RETRIES; attempt++) {
    if (isStopped(task)) throw stopError(task)
    task.chunkStates[index] = attempt === 0 ? 'uploading' : 'retrying'

    try {
      await api.uploadChunk({
        fileId: task.fileId,
        index,
        blob: uploadBlob,
        chunkHash,
        signal: task.controller?.signal,
      })
      if (isStopped(task)) throw stopError(task)
      task.chunkStates[index] = 'done'
      task.uploadedChunks += 1
      task.completedBytes += expectedBytes
      task.retryTotal += attempt
      task._samples.push({ t: performance.now(), bytes: task.completedBytes })
      updateSpeed(task)
      task.message = `已上传 ${task.uploadedChunks}/${task.chunkTotal} 片${
        task.skippedChunks ? ` · 跳过 ${task.skippedChunks} 片` : ''
      }`
      persistProgress(task)
      return
    } catch (e) {
      if (isStopped(task)) throw stopError(task)
      if (isStopError(e)) throw e
      const canRetry = !(e instanceof ApiError) || e.retriable
      if (attempt >= MAX_CHUNK_RETRIES || !canRetry) {
        task.chunkStates[index] = 'failed'
        const err = new Error(`分片 ${index + 1} 上传失败：${e.message}`)
        err.code = e.code || 0
        throw err
      }
      task.chunkStates[index] = 'retrying'
      const delay = Math.min(8000, 400 * 2 ** attempt) + Math.random() * 250
      task.message = `分片 ${index + 1} 失败，${(delay / 1000).toFixed(1)}s 后第 ${attempt + 1} 次重试…`
      await sleep(delay, task)
    }
  }
}

async function uploadMissing(task) {
  const pending = []
  for (let i = 0; i < task.chunkTotal; i++) {
    if (task.chunkStates[i] !== 'done') pending.push(i)
  }
  if (!pending.length) return

  task.state = 'uploading'
  task._chunkError = null
  task.message =
    pending.length === task.chunkTotal
      ? `开始上传 ${pending.length} 个分片（并发 ${Math.min(state.concurrency, pending.length)}）`
      : `断点续传：跳过 ${task.chunkTotal - pending.length} 片，补传 ${pending.length} 片`

  let cursor = 0
  const workerCount = Math.min(state.concurrency, pending.length)

  const worker = async () => {
    while (true) {
      if (isStopped(task) || task._chunkError) return
      const i = pending[cursor++]
      if (i === undefined) return
      try {
        await uploadOneChunk(task, i)
      } catch (e) {
        if (isStopError(e)) throw e
        task._chunkError = e
        return
      }
    }
  }

  // 暂停后先等同一轮的所有分片退出，避免慢回调串入“继续”后的新一轮。
  const results = await Promise.allSettled(Array.from({ length: workerCount }, () => worker()))

  if (isStopped(task)) throw stopError(task)
  const rejected = results.find((result) => result.status === 'rejected')
  if (rejected) throw rejected.reason
  if (task._chunkError) throw task._chunkError
}

async function realignWithServer(task) {
  const st = await callWithRetry(() => api.getStatus(task.fileId, task.controller?.signal), task, 3)
  task.chunkSize = st.chunkSize
  task.chunkTotal = st.chunkTotal

  if (st.done) {
    task.chunkStates = Array.from({ length: st.chunkTotal }, () => 'done')
    task.uploadedChunks = st.chunkTotal
    task.completedBytes = task.size
    return true
  }

  const have = new Set(st.uploadedIndexes)
  task.chunkStates = Array.from({ length: st.chunkTotal }, (_, i) =>
    have.has(i) ? 'done' : 'pending',
  )
  task.uploadedChunks = have.size
  task.skippedChunks = have.size
  task.completedBytes = bytesForIndexes(task, have)
  task._samples.length = 0
  task._samples.push({ t: performance.now(), bytes: task.completedBytes })
  task.speed = 0
  task.eta = 0
  task.state = 'uploading'
  messageForSkipped(task)
  await uploadMissing(task)
  return false
}

function messageForSkipped(task) {
  if (task.skippedChunks > 0) {
    task.message = `断点续传：服务端已有 ${task.skippedChunks}/${task.chunkTotal} 片，只补缺失分片`
  }
}

async function completeOnce(task) {
  let lastError
  for (let i = 0; i < 3; i++) {
    if (isStopped(task)) throw stopError(task)
    try {
      const result = await api.completeUpload(task.fileId, task.controller?.signal)
      if (isStopped(task)) throw stopError(task)
      return result
    } catch (e) {
      if (isStopped(task)) throw stopError(task)
      lastError = e
      if (isStopError(e)) throw e
      if (e instanceof ApiError && [30001, 30002, 30003, 30005].includes(e.code)) throw e
      if (i === 2) throw e
      await sleep(700 * 2 ** i + Math.random() * 200, task)
    }
  }
  throw lastError
}

async function completeWithHeal(task) {
  for (let round = 0; ; round++) {
    try {
      return await completeOnce(task)
    } catch (e) {
      if (isStopped(task)) throw stopError(task)
      if (e.code === 30003 && task.hashFromCache) {
        removeCachedHash(task.fingerprint)
        task.hash = null
        task.hashFromCache = false
        throw new Error('缓存的哈希与实际文件内容不一致，已清除缓存；请点击重试，会重新计算哈希', {
          cause: e,
        })
      }
      if ((e.code === 30005 || e.code === 30001) && round < MAX_HEAL_ROUNDS) {
        task.healRounds += 1
        task.message =
          e.code === 30005
            ? `服务端发现损坏分片并已剔除，正在只补传坏片（第 ${task.healRounds} 轮自愈）`
            : `服务端报告分片缺失，正在重新对齐后补传（第 ${task.healRounds} 轮自愈）`
        await realignWithServer(task)
        continue
      }
      throw e
    }
  }
}

async function runTask(task) {
  if (task._running || !isCurrentTask(task)) return
  task._running = true
  task.canceled = false
  task.paused = false
  task.errorCode = 0
  task._chunkError = null
  task.controller = markRaw(new AbortController())
  task.startedAt = task.startedAt || Date.now()

  try {
    // 1) 哈希：有缓存就跳过。缓存只当加速，最终仍由服务端整文件校验兜底
    if (!task.hash) {
      task.hash = await hashFile(task)
      if (isStopped(task)) throw stopError(task)
      setCachedHash(task.fingerprint, task.hash)
      task.hashFromCache = false
    }
    if (isStopped(task)) throw stopError(task)

    // 2) init：秒传命中 / 复用未完成任务 / 新建任务，三选一
    task.state = 'checking'
    task.message = task.hashFromCache
      ? '使用上次缓存的哈希，正在校验秒传…'
      : '正在校验秒传并初始化任务…'
    const init = await callWithRetry(
      () =>
        api.initUpload(
          {
            fileName: task.name,
            fileSize: task.size,
            fileHash: task.hash,
          },
          task.controller?.signal,
        ),
      task,
      3,
    )
    if (isStopped(task)) throw stopError(task)

    task.fileId = init.fileId
    task.chunkSize = init.chunkSize
    task.chunkTotal = init.chunkTotal

    if (init.instant) {
      task.instant = true
      task.state = 'instant'
      task.completedBytes = task.size
      task.finishedAt = Date.now()
      task.speed = 0
      task.eta = 0
      task.message = '秒传命中：服务端已有相同内容（SHA-256 相同），本次上传 0 字节'
      removePendingTask(task.fingerprint)
      refreshPending()
      toast(`⚡ ${task.name} 秒传完成，0 字节上传`, 'success')
      state.filePage.page = 1
      refreshFiles()
      refreshStats()
      return
    }

    // 3) 以服务端返回的已传序号为准重建分片状态
    const serverSet = new Set(init.uploadedIndexes)
    task.chunkStates = Array.from({ length: init.chunkTotal }, (_, i) =>
      serverSet.has(i) ? 'done' : 'pending',
    )
    task.uploadedChunks = serverSet.size
    task.skippedChunks = serverSet.size
    task.completedBytes = bytesForIndexes(task, serverSet)
    task._samples.length = 0
    task._samples.push({ t: performance.now(), bytes: task.completedBytes })
    savePendingTask(task)
    refreshPending()
    messageForSkipped(task)

    // 4) 只传缺失分片
    await uploadMissing(task)
    if (isStopped(task)) throw stopError(task)

    // 5) 合并 + 完整性校验（含坏片自愈）
    task.state = 'merging'
    task.message = '分片已齐，正在流式合并并逐片 + 整文件校验 SHA-256…'
    await completeWithHeal(task)
    if (isStopped(task)) throw stopError(task)

    task.state = 'done'
    task.finishedAt = Date.now()
    task.hashProgress = 1
    task.completedBytes = task.size
    task.speed = 0
    task.eta = 0
    task.message = '上传完成：服务端逐片校验 + 整文件 SHA-256 均通过'
    removePendingTask(task.fingerprint)
    refreshPending()
    toast(`✅ ${task.name} 上传完成`, 'success')
    // 新文件按时间排在最新一页，回到第 1 页让用户能立刻看到
    state.filePage.page = 1
    refreshFiles()
    refreshStats()
  } catch (e) {
    // 退出/切换账号后的回调不能写入当前用户的缓存、断点记录或提示。
    if (!isCurrentTask(task)) return
    if (task.canceled) {
      task.state = 'canceled'
      task.message = '已取消'
    } else if (task.paused || isStopError(e)) {
      task.state = 'paused'
      task.message = task.hash ? '已暂停：继续时从断点处补传缺失分片' : '已暂停'
      savePendingTask(task)
      refreshPending()
    } else {
      task.state = 'error'
      task.errorCode = e.code || 0
      task.message = e.message || '上传失败'
      savePendingTask(task)
      refreshPending()
      toast(`❌ ${task.name} 上传失败：${task.message}`, 'error')
    }
  } finally {
    task._running = false
    task.controller = null
    if (task._resumeRequested) {
      task._resumeRequested = false
      if (!task.canceled && isCurrentTask(task)) {
        task.paused = false
        task.state = 'queued'
      }
    }
  }
}

async function processQueue() {
  if (state.processing) return
  const epoch = workspaceEpoch
  state.processing = true
  try {
    while (epoch === workspaceEpoch) {
      const task = state.tasks.find(
        (t) => isCurrentTask(t) && t.state === 'queued' && !t.paused && !t.canceled,
      )
      if (!task) break
      await runTask(task)
    }
  } finally {
    if (epoch === workspaceEpoch) state.processing = false
  }
}

export function addFiles(fileList) {
  const files = Array.from(fileList || [])
  if (!files.length) return

  let added = 0
  for (const file of files) {
    const fingerprint = fingerprintOf(file)
    const duplicate = state.tasks.some(
      (t) => t.fingerprint === fingerprint && !FINISHED_STATES.has(t.state),
    )
    if (duplicate) {
      toast(`${file.name} 已在队列中`, 'warn')
      continue
    }
    const saved = findPendingTask(fingerprint)
    if (saved && !saved.hash) {
      saved.hash = getCachedHash(fingerprint)
    }
    state.tasks.push(newTask(file, fingerprint, saved))
    added += 1
  }

  if (added > 0) processQueue()
}

export function pauseTask(task) {
  if (!ACTIVE_STATES.has(task.state)) return
  task.paused = true
  task.state = 'paused'
  task.message = task.hash ? '已暂停：继续时从断点处补传缺失分片' : '已暂停'
  task.controller?.abort()
  savePendingTask(task)
  refreshPending()
}

export function resumeTask(task) {
  if (task.state !== 'paused' && task.state !== 'error') return
  task.paused = false
  task.canceled = false
  task.errorCode = 0
  task.message = ''
  if (task._running) {
    task._resumeRequested = true
    return
  }
  task.state = 'queued'
  processQueue()
}

export function cancelTask(task) {
  task.canceled = true
  task.controller?.abort()
  if (!task._running) {
    task.state = 'canceled'
    task.message = '已取消'
  }
  removePendingTask(task.fingerprint)
  refreshPending()
}

export function removeTask(task) {
  if (task._running) {
    cancelTask(task)
    return
  }
  state.tasks = state.tasks.filter((t) => t.id !== task.id)
  // 用户主动从队列移除，等于放弃续传：同时清掉本地断点记录，
  // 否则下次再选同名文件还会提示"有未完成任务"。
  if (!state.tasks.some((t) => t.fingerprint === task.fingerprint)) {
    removePendingTask(task.fingerprint)
    refreshPending()
  }
}

export function clearFinished() {
  state.tasks = state.tasks.filter((t) => !FINISHED_STATES.has(t.state))
}

export function clearAllTasks() {
  state.tasks = []
}

let filesRequest = 0
let statsRequest = 0
let filesController = null

export async function refreshFiles() {
  const requestId = ++filesRequest
  filesController?.abort()
  filesController = new AbortController()
  state.filesLoading = true
  state.filesError = ''
  try {
    const res = await api.listFiles({ ...state.filePage, signal: filesController.signal })
    // 搜索、翻页或切换账号后，迟到的响应不能覆盖当前列表。
    if (requestId !== filesRequest) return
    state.files = res.items
    state.filePage.total = res.total
    state.filePage.page = res.page
    state.filePage.size = res.size
    state.online = true

    // 删掉最后一页的最后一条后，当前页会空掉：自动退到新的最后一页
    const maxPage = Math.max(1, Math.ceil(res.total / res.size))
    if (res.page > maxPage) {
      state.filePage.page = maxPage
      return await refreshFiles()
    }
  } catch (e) {
    if (requestId !== filesRequest || e.name === 'AbortError') return
    state.filesError = e.message || '文件列表加载失败，请重试'
    // 只有真正连不上服务端才算离线；401/403/429 不应把顶部显示成「服务未启动」
    if (!(e instanceof ApiError) || e.status === 0) state.online = false
  } finally {
    if (requestId === filesRequest) state.filesLoading = false
  }
}

/** 改搜索词/状态/排序时回到第 1 页 */
export async function updateFileQuery(patch = {}) {
  if ('keyword' in patch) state.filePage.keyword = patch.keyword || ''
  if ('status' in patch) state.filePage.status = patch.status
  if ('sort' in patch) state.filePage.sort = patch.sort
  state.filePage.page = 1
  await refreshFiles()
}

export async function goFilePage(page) {
  const maxPage = Math.max(1, Math.ceil(state.filePage.total / state.filePage.size))
  const next = Math.min(maxPage, Math.max(1, page))
  if (next === state.filePage.page) return
  state.filePage.page = next
  await refreshFiles()
}

export async function refreshStats() {
  const requestId = ++statsRequest
  try {
    const stats = await api.getStats()
    if (requestId !== statsRequest) return
    state.stats = stats
    state.online = true
  } catch (e) {
    if (requestId !== statsRequest) return
    if (!(e instanceof ApiError) || e.status === 0) state.online = false
  }
}

export async function deleteServerFile(file) {
  const ok = window.confirm(
    `确认删除「${file.fileName}」？\n若已没有其他记录引用同一份物理文件，磁盘上的文件会一并删除。`,
  )
  if (!ok) return
  const epoch = workspaceEpoch
  try {
    await api.deleteFile(file.fileId)
    if (epoch !== workspaceEpoch) return
    toast(`已删除 ${file.fileName}`, 'success')
    await Promise.all([refreshFiles(), refreshStats()])
  } catch (e) {
    if (epoch !== workspaceEpoch) return
    toast(`删除失败：${e.message}`, 'error')
  }
}

export async function downloadFile(file) {
  if (file.status !== 1) return
  if (state.downloads.some((item) => item.fileId === file.fileId && item.status === 'preparing')) {
    return
  }
  const epoch = workspaceEpoch
  const download = reactive({
    id: uid(),
    fileId: file.fileId,
    fileName: file.fileName,
    fileSize: file.fileSize,
    status: 'preparing',
    error: '',
  })
  state.downloads = [
    download,
    ...state.downloads.filter((item) => item.status === 'preparing'),
    ...state.downloads.filter((item) => item.status !== 'preparing').slice(0, 4),
  ]
  try {
    const ticket = await api.createDownloadTicket(file.fileId)
    if (epoch !== workspaceEpoch) return
    const a = document.createElement('a')
    a.href = ticket.url
    a.download = file.fileName
    document.body.appendChild(a)
    a.click()
    a.remove()
    download.status = 'submitted'
    toast(`已发起 ${file.fileName} 的下载，请在下载列表中查看保存进度`, 'success')
  } catch (e) {
    if (epoch !== workspaceEpoch) return
    download.status = 'error'
    download.error = e.message || '下载请求失败，请重试'
    toast(`下载失败：${e.message}`, 'error')
  }
}

export function clearDownloadHistory() {
  state.downloads = state.downloads.filter((item) => item.status === 'preparing')
}

/** 登录后加载该用户的工作区：配置、本地断点记录、文件列表、统计 */
export async function startWorkspace() {
  if (!auth.token || !auth.user) return
  if (workspaceToken !== auth.token) resetWorkspace()
  const token = auth.token
  const epoch = workspaceEpoch
  const isCurrent = () => token === auth.token && epoch === workspaceEpoch
  setStorageUser(auth.user.id)
  state.pendingResumable = loadPendingTasks()
  try {
    const config = await api.getConfig()
    if (!isCurrent()) return
    state.config = config
    state.online = true
  } catch (e) {
    if (!isCurrent()) return
    if (!(e instanceof ApiError) || e.status === 0) {
      state.online = false
      toast('无法连接服务端：请先启动 file-hub', 'error')
    }
  }
  await Promise.all([refreshFiles(), refreshStats()])
}

/** 退出登录/会话失效：停止传输并清空内存态，但保留本用户的本地断点记录 */
export function resetWorkspace() {
  workspaceEpoch++
  workspaceToken = auth.token
  filesRequest++
  statsRequest++
  filesController?.abort()
  filesController = null
  for (const task of state.tasks) {
    task.controller?.abort()
  }
  state.tasks = []
  state.processing = false
  state.files = []
  state.filesLoading = false
  state.filesError = ''
  state.downloads = []
  state.toasts = []
  state.filePage = {
    page: 1,
    size: 10,
    total: 0,
    keyword: '',
    status: null,
    sort: 'time_desc',
  }
  state.stats = emptyStats()
  state.pendingResumable = []
  setStorageUser(null)
}

export async function logout() {
  // 先以当前凭证发出撤销请求，再立即停止本地任务；网络慢不应阻塞退出。
  const revoke = api.logout()
  clearSession({ notify: false })
  resetWorkspace()
  auth.ready = true
  state.booted = true
  try {
    await revoke
  } catch {
    // 网络不通或 Token 已失效都不阻塞退出
  }
}

export async function bootstrap() {
  applyTheme()
  startTicker()
  const token = auth.token
  const epoch = workspaceEpoch
  const isCurrent = () => token === auth.token && epoch === workspaceEpoch
  try {
    if (token) {
      const user = await api.me()
      if (!isCurrent()) return
      // 服务端是唯一权威：本地角色只是缓存，每次启动都刷新一次
      setSession({ token, user })
      await startWorkspace()
    }
  } catch (e) {
    if (!isCurrent()) return
    if (e.status !== 401) {
      state.online = false
      toast('无法连接服务端：请先启动 file-hub', 'error')
    }
  } finally {
    if (token === auth.token || !auth.token) {
      auth.ready = true
      state.booted = true
    }
  }
}

let tickerStarted = false

/** 测速定时器只在启动时创建一次；模块被测试 import 时不应产生后台定时器 */
function startTicker() {
  if (tickerStarted) return
  tickerStarted = true
  setInterval(() => {
    const now = performance.now()
    for (const task of state.tasks) {
      if (task.state !== 'uploading') continue
      const samples = task._samples
      while (samples.length > 1 && samples[0].t < now - 5000) samples.shift()
      if (samples.length >= 2) {
        const first = samples[0]
        const last = samples[samples.length - 1]
        const dt = (last.t - first.t) / 1000
        if (dt > 0.3) {
          task.speed = Math.max(0, (last.bytes - first.bytes) / dt)
          const remaining = Math.max(0, task.size - task.completedBytes)
          task.eta = task.speed > 1 ? remaining / task.speed : 0
        }
        if (now - last.t > 4000) {
          task.speed = 0
          task.eta = 0
        }
      }
    }
  }, 800)
}
