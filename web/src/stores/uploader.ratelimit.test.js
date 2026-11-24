import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', async (original) => ({
  ...(await original()),
  initUpload: vi.fn(),
  uploadChunk: vi.fn(),
  completeUpload: vi.fn(),
  listFiles: vi.fn(),
  getStats: vi.fn(),
}))

import * as api from '../lib/api'
import { clearSession, setSession } from '../lib/auth'
import { setStorageUser } from '../lib/storage'
import { addFiles, cancelTask, pauseTask, resetWorkspace, resumeTask, state } from './uploader'

const init = {
  fileId: 'rate-limit-file',
  chunkSize: 1,
  chunkTotal: 1,
  uploadedIndexes: [],
  instant: false,
}

const file = () => ({
  name: 'report.txt',
  webkitRelativePath: 'folder/report.txt',
  size: 1,
  lastModified: 1,
  slice: () => ({ arrayBuffer: async () => new Uint8Array([1]).buffer }),
})

const limited = () => new api.ApiError(40002, '请求过于频繁', 429, 30_000)

async function flushUpload() {
  await flushPromises()
  await vi.advanceTimersByTimeAsync(0)
  await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  vi.resetAllMocks()
  vi.stubGlobal('Worker', undefined)
  vi.stubGlobal('crypto', undefined)
  setSession({ token: 'rate-limit-token', user: { id: 1 } })
  resetWorkspace()
  setStorageUser(1)
  api.initUpload.mockResolvedValue(init)
  api.uploadChunk.mockResolvedValue({})
  api.completeUpload.mockResolvedValue({})
  api.listFiles.mockResolvedValue({ items: [], page: 1, size: 10, total: 0 })
  api.getStats.mockResolvedValue({ doneFiles: 0 })
})

afterEach(async () => {
  clearSession({ notify: false })
  resetWorkspace()
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  await flushPromises()
})

describe('文件夹批量上传的限流等待', () => {
  it('超过 60 个小文件时等待限流窗口后继续整个队列，不将后续文件标记失败', async () => {
    let requests = 0
    api.initUpload.mockImplementation(async () => {
      if (++requests === 61) throw limited()
      return { ...init, instant: true }
    })
    addFiles(
      Array.from({ length: 62 }, (_, index) => ({
        name: `${index}.txt`,
        webkitRelativePath: `folder/${index}.txt`,
        size: 0,
        lastModified: 1,
      })),
    )
    await flushUpload()
    expect(api.initUpload).toHaveBeenCalledTimes(61)
    expect(state.tasks.filter((task) => task.state === 'instant')).toHaveLength(60)
    expect(state.tasks[60].message).toContain('等待 30 秒后自动继续')
    expect(state.tasks[61].state).toBe('queued')

    await vi.advanceTimersByTimeAsync(29_999)
    expect(api.initUpload).toHaveBeenCalledTimes(61)
    await vi.advanceTimersByTimeAsync(1)
    await flushUpload()
    expect(api.initUpload).toHaveBeenCalledTimes(63)
    expect(state.tasks.every((task) => task.state === 'instant')).toBe(true)
  })

  it.each(['initUpload', 'uploadChunk', 'completeUpload'])(
    '%s 遵守 Retry-After，等待结束后自动完成上传',
    async (method) => {
      api[method].mockRejectedValueOnce(limited())
      addFiles([file()])
      await flushUpload()
      const task = state.tasks[0]
      expect(api[method]).toHaveBeenCalledOnce()
      expect(task.message).toContain('等待 30 秒后自动继续')
      await vi.advanceTimersByTimeAsync(29_999)
      expect(api[method]).toHaveBeenCalledOnce()
      await vi.advanceTimersByTimeAsync(1)
      await flushUpload()
      expect(api[method]).toHaveBeenCalledTimes(2)
      expect(task.state).toBe('done')
    },
  )

  it.each(['initUpload', 'uploadChunk', 'completeUpload'])(
    '%s 限流等待可立即暂停，不会由旧定时器自行继续，手动继续后能完成',
    async (method) => {
      api[method].mockRejectedValueOnce(limited())
      addFiles([file()])
      await flushUpload()
      const task = state.tasks[0]
      pauseTask(task)
      await flushUpload()
      expect(task.state).toBe('paused')
      expect(task._running).toBe(false)
      await vi.advanceTimersByTimeAsync(30_000)
      expect(api[method]).toHaveBeenCalledOnce()
      resumeTask(task)
      await flushUpload()
      expect(task.state).toBe('done')
    },
  )

  it('取消限流等待时不再发起重试', async () => {
    api.initUpload.mockRejectedValueOnce(limited())
    addFiles([file()])
    await flushUpload()
    const task = state.tasks[0]
    cancelTask(task)
    await flushUpload()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(api.initUpload).toHaveBeenCalledOnce()
    expect(task.state).toBe('canceled')
    expect(task._running).toBe(false)
  })
})
