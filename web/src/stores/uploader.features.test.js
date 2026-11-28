import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', async (original) => ({
  ...(await original()),
  getFolderTree: vi.fn(),
  createZipTicket: vi.fn(),
  trashFile: vi.fn(),
  deleteFile: vi.fn(),
  listFiles: vi.fn(() => Promise.resolve({ items: [], total: 0, page: 1, size: 10 })),
  getStats: vi.fn(() => Promise.resolve({ doneFiles: 0, quotaBytes: -1, quotaUsedBytes: 0 })),
}))

import * as api from '../lib/api'
import {
  deleteServerFile,
  downloadZip,
  enterDir,
  resetWorkspace,
  retryAllFailed,
  setConcurrency,
  setSpeedLimit,
  setViewMode,
  state,
} from './uploader'

describe('uploader new features', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetWorkspace()
  })

  it('并发数与上传限速配置边界校验正确', () => {
    setConcurrency(6)
    expect(state.concurrency).toBe(6)
    setConcurrency(99)
    expect(state.concurrency).toBe(6)

    setSpeedLimit(2048)
    expect(state.speedLimitKBps).toBe(2048)
    setSpeedLimit(-5)
    expect(state.speedLimitKBps).toBe(0)
  })

  it('retryAllFailed 仅重试失败任务并重置错误字段', () => {
    const failed = {
      id: '1',
      state: 'error',
      errorCode: 500,
      message: 'net err',
      paused: false,
      canceled: false,
    }
    const done = {
      id: '2',
      state: 'done',
      errorCode: 0,
      message: '',
      paused: false,
      canceled: false,
    }
    state.tasks = [failed, done]

    retryAllFailed()
    expect(failed.state).toBe('queued')
    expect(failed.errorCode).toBe(0)
    expect(failed.message).toBe('')
    expect(done.state).toBe('done')
  })

  it('目录视图与 enterDir 调用后端目录树接口', async () => {
    api.getFolderTree.mockResolvedValue({
      currentDir: 'docs',
      breadcrumbs: [
        { name: '全部文件', path: '' },
        { name: 'docs', path: 'docs' },
      ],
      folders: [],
      files: [],
    })

    await enterDir('docs')
    expect(state.viewMode).toBe('tree')
    expect(state.currentDir).toBe('docs')
    expect(api.getFolderTree).toHaveBeenCalledWith(expect.objectContaining({ dir: 'docs' }))

    await setViewMode('list')
    expect(state.viewMode).toBe('list')
    expect(api.listFiles).toHaveBeenCalledOnce()
  })

  it('已完成文件删除走回收站，上传中文件直接删除', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    api.trashFile.mockResolvedValue({})
    api.deleteFile.mockResolvedValue({})

    await deleteServerFile({ fileId: 'f-done', fileName: 'a.pdf', status: 1 })
    expect(api.trashFile).toHaveBeenCalledWith('f-done')
    expect(api.deleteFile).not.toHaveBeenCalled()

    await deleteServerFile({ fileId: 'f-up', fileName: 'b.bin', status: 0 })
    expect(api.deleteFile).toHaveBeenCalledWith('f-up')
  })

  it('downloadZip 申请打包票据并触发下载', async () => {
    api.createZipTicket.mockResolvedValue({
      zipName: 'bundle.zip',
      totalBytes: 4096,
      fileCount: 2,
      downloadUrl: '/api/file/zip?ticket=z1',
    })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    await downloadZip({ folderPath: 'docs' })
    expect(api.createZipTicket).toHaveBeenCalledWith({ fileIds: null, folderPath: 'docs' })
    expect(click).toHaveBeenCalledOnce()
    expect(state.downloads[0]).toMatchObject({ fileName: 'bundle.zip', status: 'submitted' })
  })
})
