import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'

vi.mock('../lib/api', async (original) => ({
  ...(await original()),
  initUpload: vi.fn(),
  uploadChunk: vi.fn(),
  completeUpload: vi.fn(),
  getStatus: vi.fn(),
  listFiles: vi.fn(),
  getStats: vi.fn(),
  getConfig: vi.fn(),
  me: vi.fn(),
  logout: vi.fn(),
}))
import * as api from '../lib/api'
import { auth, clearSession, setSession } from '../lib/auth'
import { fingerprintOf, loadPendingTasks, savePendingTask, setStorageUser } from '../lib/storage'
import {
  addFiles,
  bootstrap,
  logout,
  pauseTask,
  resetWorkspace,
  resumeTask,
  startWorkspace,
  state,
} from './uploader'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
function user(id) {
  setSession({ token: `token-${id}`, user: { id, username: `user-${id}`, role: 'USER' } })
  resetWorkspace()
  setStorageUser(id)
}
const emptyFile = (name = 'private.txt') => ({ name, size: 0, lastModified: 1 })
const init = (fileId = 'a-upload') => ({
  fileId,
  chunkTotal: 0,
  chunkSize: 5,
  uploadedIndexes: [],
  instant: false,
})

beforeEach(() => {
  vi.useFakeTimers({
    toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'],
  })
  vi.resetAllMocks()
  vi.stubGlobal('Worker', undefined)
  vi.stubGlobal('crypto', undefined)
  user(1)
  api.listFiles.mockResolvedValue({ items: [], page: 1, size: 10, total: 0 })
  api.getStats.mockResolvedValue({ doneFiles: 0 })
  api.getConfig.mockResolvedValue({ chunkSize: 5, desktop: false })
  api.initUpload.mockResolvedValue(init())
  api.uploadChunk.mockResolvedValue({})
  api.completeUpload.mockResolvedValue({})
  api.logout.mockResolvedValue({})
})
afterEach(async () => {
  clearSession({ notify: false })
  resetWorkspace()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  await flushPromises()
})

describe('上传任务的账号隔离', () => {
  it('哈希读取期间切换账号，不写新账号哈希缓存、不以新账号初始化旧文件', async () => {
    const read = deferred()
    addFiles([
      {
        name: 'secret.txt',
        size: 3,
        lastModified: 1,
        slice: () => ({ arrayBuffer: () => read.promise }),
      },
    ])
    const oldTask = state.tasks[0]
    user(2)
    read.resolve(new Uint8Array([1, 2, 3]).buffer)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(oldTask._running).toBe(false)
    expect(api.initUpload).not.toHaveBeenCalled()
    expect(localStorage.getItem('filehub.2.hashCache.v2')).toBeNull()
    expect(loadPendingTasks()).toEqual([])
    expect(state.toasts).toEqual([])
  })

  it('旧初始化请求挂起时新账号仍能上传，迟到结果不能覆盖新队列', async () => {
    const old = deferred(),
      current = deferred()
    api.initUpload.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    addFiles([emptyFile()])
    await flushPromises()
    const oldSignal = api.initUpload.mock.calls[0][1]
    user(2)
    expect(oldSignal.aborted).toBe(true)
    addFiles([emptyFile('new.txt')])
    await flushPromises()
    expect(api.initUpload).toHaveBeenCalledTimes(2)
    old.resolve(init('private-id'))
    await flushPromises()
    expect(state.processing).toBe(true)
    expect(loadPendingTasks()).toEqual([])
    current.resolve(init('new-id'))
    await flushPromises()
    expect(api.completeUpload).toHaveBeenCalledTimes(1)
    expect(api.completeUpload.mock.calls[0][0]).toBe('new-id')
    expect(state.tasks[0].state).toBe('done')
    expect(state.toasts.every((t) => !t.text.includes('private.txt'))).toBe(true)
  })

  it.each(['success', 'failure'])('旧合并的 %s 回调不删除新账号的同名断点记录', async (outcome) => {
    const merge = deferred()
    api.completeUpload.mockReturnValueOnce(merge.promise)
    const file = emptyFile()
    addFiles([file])
    await flushPromises()
    expect(state.tasks[0].state).toBe('merging')
    user(2)
    savePendingTask({
      fingerprint: fingerprintOf(file),
      name: file.name,
      state: 'paused',
      fileId: 'belongs-to-b',
    })
    if (outcome === 'success') merge.resolve({})
    else merge.reject(new api.ApiError(30003, '哈希不匹配'))
    await flushPromises()
    expect(loadPendingTasks()[0].fileId).toBe('belongs-to-b')
    expect(state.toasts).toEqual([])
    expect(api.listFiles).not.toHaveBeenCalled()
  })

  it('网络瞬断会重试初始化，不把暂时断网当成永久失败', async () => {
    api.initUpload
      .mockRejectedValueOnce(new api.ApiError(-1, '网络中断', 0))
      .mockResolvedValueOnce(init())
    addFiles([emptyFile()])
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(api.initUpload).toHaveBeenCalledTimes(2)
    expect(state.tasks[0].state).toBe('done')
  })

  it('暂停后的慢分片全部结束后才开始续传，不串入新一轮', async () => {
    api.initUpload.mockResolvedValue({ ...init(), chunkSize: 1, chunkTotal: 2 })
    const first = deferred(),
      second = deferred()
    api.uploadChunk
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockResolvedValue({})
    addFiles([
      {
        name: 'two.bin',
        size: 2,
        lastModified: 1,
        slice: () => ({ arrayBuffer: async () => new Uint8Array([1]).buffer }),
      },
    ])
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    const task = state.tasks[0]
    expect(api.uploadChunk).toHaveBeenCalledTimes(2)
    pauseTask(task)
    resumeTask(task)
    first.reject(new DOMException('aborted', 'AbortError'))
    await flushPromises()
    expect(api.initUpload).toHaveBeenCalledTimes(1)
    second.resolve({})
    await flushPromises()
    expect(api.initUpload).toHaveBeenCalledTimes(2)
    expect(task.uploadedChunks).toBe(2)
    expect(task.state).toBe('done')
  })
})

describe('登录恢复与退出', () => {
  it('退出立即停止本地工作，迟到撤销响应不退出新账号', async () => {
    const revoke = deferred()
    api.logout.mockReturnValueOnce(revoke.promise)
    const pending = logout()
    expect(auth.token).toBeNull()
    expect(state.processing).toBe(false)
    user(2)
    revoke.resolve({})
    await pending
    expect(auth.token).toBe('token-2')
  })

  it('旧会话恢复响应不覆盖新账号身份', async () => {
    const me = deferred()
    api.me.mockReturnValueOnce(me.promise)
    const pending = bootstrap()
    user(2)
    me.resolve({ id: 1, username: 'user-1', role: 'ADMIN' })
    await pending
    expect(auth.user.id).toBe(2)
    expect(auth.user.role).toBe('USER')
    expect(api.getConfig).not.toHaveBeenCalled()
  })

  it('退出后工作区配置慢响应不会再加载文件列表', async () => {
    const config = deferred()
    api.getConfig.mockReturnValueOnce(config.promise)
    const pending = startWorkspace()
    await logout()
    config.resolve({ desktop: true, chunkSize: 123 })
    await pending
    expect(api.listFiles).not.toHaveBeenCalled()
    expect(state.config.chunkSize).not.toBe(123)
  })

  it('首次恢复会话时退出，不会一直停留在加载屏幕', async () => {
    auth.ready = false
    const me = deferred()
    api.me.mockReturnValueOnce(me.promise)
    const pending = bootstrap()
    await logout()
    expect(auth.ready).toBe(true)
    me.resolve({ id: 1 })
    await pending
    expect(auth.token).toBeNull()
    expect(auth.ready).toBe(true)
  })
})
