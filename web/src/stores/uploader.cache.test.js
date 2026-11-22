import { createHash } from 'node:crypto'
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
import {
  fingerprintOf,
  getCachedHash,
  loadPendingTasks,
  savePendingTask,
  setCachedHash,
  setStorageUser,
} from '../lib/storage'
import { addFiles, pauseTask, resetWorkspace, resumeTask, state } from './uploader'

const digest = (text) => createHash('sha256').update(text).digest('hex')

function fileWithContents(text) {
  const bytes = new TextEncoder().encode(text)
  return {
    name: 'report.txt',
    size: bytes.length,
    lastModified: 1234,
    slice: vi.fn((start, end) => ({
      arrayBuffer: async () => bytes.slice(start, end).buffer,
    })),
  }
}

function saveResume(file, hash) {
  savePendingTask({
    fingerprint: fingerprintOf(file),
    name: file.name,
    size: file.size,
    lastModified: file.lastModified,
    state: 'paused',
    hash,
    fileId: 'old-file-id',
  })
}

const init = (patch = {}) => ({
  fileId: 'current-file-id',
  chunkSize: 4,
  chunkTotal: 1,
  uploadedIndexes: [0],
  instant: false,
  ...patch,
})

async function finishHash() {
  await flushPromises()
  await vi.advanceTimersByTimeAsync(1)
  await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  vi.resetAllMocks()
  vi.stubGlobal('Worker', undefined)
  vi.stubGlobal('crypto', undefined)
  setSession({ token: 'cache-test-token', user: { id: 1 } })
  resetWorkspace()
  setStorageUser(1)
  api.initUpload.mockResolvedValue(init())
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

describe('上传哈希缓存的内容核对', () => {
  it.each(['pending hash', 'cache with pending', 'cache without pending'])(
    '%s 不能让同名同大小同修改时间的新内容误秒传成旧内容',
    async (source) => {
      const file = fileWithContents('NEW!')
      const oldHash = digest('OLD!')
      if (source !== 'cache without pending') {
        saveResume(file, source === 'pending hash' ? oldHash : null)
      }
      setCachedHash(fingerprintOf(file), oldHash)
      // 与服务端一致：旧摘要存在时可以直接秒传，不会进入 complete 整文件校验。
      api.initUpload.mockImplementation(async ({ fileHash }) =>
        init({ instant: fileHash === oldHash }),
      )

      addFiles([file])
      await finishHash()

      expect(api.initUpload).toHaveBeenCalledWith(
        expect.objectContaining({ fileHash: digest('NEW!') }),
        expect.any(AbortSignal),
      )
      expect(state.tasks[0].state).toBe('done')
      expect(state.tasks[0].instant).toBe(false)
      expect(getCachedHash(fingerprintOf(file))).toBe(digest('NEW!'))
      expect(file.slice).toHaveBeenCalledTimes(1)
    },
  )

  it('重新核对未变的续传文件后仍跳过服务端已有分片', async () => {
    const file = fileWithContents('ABCD')
    saveResume(file, digest('ABCD'))
    api.initUpload.mockResolvedValue(init({ chunkSize: 2, chunkTotal: 2, uploadedIndexes: [0] }))

    addFiles([file])
    await finishHash()

    expect(api.uploadChunk).toHaveBeenCalledTimes(1)
    expect(api.uploadChunk).toHaveBeenCalledWith(
      expect.objectContaining({ fileId: 'current-file-id', index: 1, chunkHash: digest('CD') }),
    )
    expect(file.slice.mock.calls).toEqual([
      [0, 4],
      [2, 4],
    ])
    expect(state.tasks[0].state).toBe('done')
    expect(loadPendingTasks()).toEqual([])
  })

  it('同一任务暂停后继续可复用本次已核对的哈希，无需再次读盘', async () => {
    const file = fileWithContents('ABCD')
    let rejectInit
    api.initUpload
      .mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            rejectInit = reject
          }),
      )
      .mockResolvedValueOnce(init({ instant: true }))
    addFiles([file])
    await finishHash()
    const task = state.tasks[0]
    expect(task.state).toBe('checking')

    pauseTask(task)
    resumeTask(task)
    rejectInit(new DOMException('aborted', 'AbortError'))
    await flushPromises()

    expect(api.initUpload).toHaveBeenCalledTimes(2)
    expect(file.slice).toHaveBeenCalledTimes(1)
    expect(task.state).toBe('instant')
  })

  it('本地文件读取失败时不退回旧摘要发起秒传', async () => {
    const file = fileWithContents('NEW!')
    file.slice.mockImplementationOnce(() => ({
      arrayBuffer: async () => {
        throw new Error('文件暂时不可读取')
      },
    }))
    saveResume(file, digest('OLD!'))

    addFiles([file])
    await finishHash()

    expect(state.tasks[0].state).toBe('error')
    expect(api.initUpload).not.toHaveBeenCalled()
    resumeTask(state.tasks[0])
    await finishHash()
    expect(api.initUpload.mock.calls[0][0].fileHash).toBe(digest('NEW!'))
    expect(state.tasks[0].state).toBe('done')
  })
})
