import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal()),
  listFiles: vi.fn(),
  getStats: vi.fn(),
  createDownloadTicket: vi.fn(),
}))

import * as api from '../lib/api'
import {
  clearDownloadHistory,
  downloadFile,
  refreshFiles,
  refreshStats,
  resetWorkspace,
  state,
  updateFileQuery,
} from './uploader'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}
const page = (name, current = 1, total = 1) => ({
  items: [{ fileId: name, fileName: name }],
  page: current,
  size: 10,
  total,
})
const file = { fileId: 'file-1', fileName: '报告.pdf', fileSize: 2048, status: 1 }

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  resetWorkspace()
  state.toasts = []
  state.online = true
})
afterEach(() => {
  resetWorkspace()
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('文件列表请求', () => {
  it('末页修改每页数量后回到第一页，保留已应用的查询条件', async () => {
    Object.assign(state.filePage, { page: 8, keyword: '报告', status: 1, sort: 'name_asc' })
    api.listFiles.mockResolvedValueOnce({ ...page('new'), size: 50, total: 75 })
    await updateFileQuery({ size: 50 })
    expect(api.listFiles).toHaveBeenCalledWith(
      expect.objectContaining({
        page: 1,
        size: 50,
        keyword: '报告',
        status: 1,
        sort: 'name_asc',
      }),
    )
    expect(state.filePage.size).toBe(50)
    expect(state.filePage.page).toBe(1)
  })

  it('改变每页数量时，先前查询的迟到响应不能恢复旧数量', async () => {
    const previous = deferred()
    api.listFiles
      .mockReturnValueOnce(previous.promise)
      .mockResolvedValueOnce({ ...page('new'), size: 20 })
    const pending = refreshFiles()
    await updateFileQuery({ size: 20 })
    previous.resolve(page('old'))
    await pending
    expect(state.filePage.size).toBe(20)
    expect(state.files[0].fileName).toBe('new')
  })

  it.each([0, 101, 1.5, 'bad'])('忽略非法每页数量 %s', async (size) => {
    api.listFiles.mockResolvedValueOnce(page('default'))
    await updateFileQuery({ size })
    expect(api.listFiles).toHaveBeenCalledWith(expect.objectContaining({ size: 10 }))
  })

  it('旧搜索迟到时不覆盖新搜索结果，并取消旧请求', async () => {
    const old = deferred()
    api.listFiles.mockReturnValueOnce(old.promise).mockResolvedValueOnce(page('new'))
    const first = updateFileQuery({ keyword: 'old' })
    const signal = api.listFiles.mock.calls[0][0].signal
    await updateFileQuery({ keyword: 'new' })
    expect(signal.aborted).toBe(true)
    old.resolve(page('old'))
    await first
    expect(state.files[0].fileName).toBe('new')
    expect(state.filePage.keyword).toBe('new')
    expect(state.filesLoading).toBe(false)
  })

  it('旧请求报错不影响新请求的加载状态', async () => {
    const old = deferred(),
      latest = deferred()
    api.listFiles.mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const first = refreshFiles(),
      second = refreshFiles()
    old.reject(new Error('旧请求失败'))
    await first
    expect(state.filesLoading).toBe(true)
    expect(state.filesError).toBe('')
    latest.resolve(page('new'))
    await second
    expect(state.filesLoading).toBe(false)
  })

  it('HTTP 错误显示可重试信息，成功后清除错误', async () => {
    api.listFiles
      .mockRejectedValueOnce(new api.ApiError(429, '请求过于频繁', 429))
      .mockResolvedValueOnce(page('new'))
    await refreshFiles()
    expect(state.filesError).toBe('请求过于频繁')
    expect(state.online).toBe(true)
    await refreshFiles()
    expect(state.filesError).toBe('')
  })

  it('删除末页后退回有效页', async () => {
    state.filePage.page = 3
    api.listFiles
      .mockResolvedValueOnce(page('empty', 3, 11))
      .mockResolvedValueOnce(page('last', 2, 11))
    await refreshFiles()
    expect(api.listFiles.mock.calls[1][0].page).toBe(2)
    expect(state.filePage.page).toBe(2)
    expect(state.filesLoading).toBe(false)
  })

  it('退出后的旧列表和统计响应不能恢复前一账号的数据', async () => {
    const files = deferred(),
      stats = deferred()
    api.listFiles.mockReturnValueOnce(files.promise)
    api.getStats.mockReturnValueOnce(stats.promise)
    const pending = Promise.all([refreshFiles(), refreshStats()])
    resetWorkspace()
    files.resolve(page('private'))
    stats.resolve({ doneFiles: 123 })
    await pending
    expect(state.files).toEqual([])
    expect(state.stats.doneFiles).toBe(0)
  })
})

describe('下载请求', () => {
  it('阻止重复点击，交给浏览器后展示真实状态而非下载完成', async () => {
    const ticket = deferred()
    api.createDownloadTicket.mockReturnValueOnce(ticket.promise)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const pending = downloadFile(file)
    expect(state.downloads[0].status).toBe('preparing')
    await downloadFile(file)
    expect(api.createDownloadTicket).toHaveBeenCalledOnce()
    ticket.resolve({ url: '/api/file/file-1/download?ticket=test' })
    await pending
    expect(click).toHaveBeenCalledOnce()
    expect(state.downloads[0].status).toBe('submitted')
    expect(state.toasts[0].text).toContain('已发起')
    expect(JSON.stringify(state.downloads)).not.toContain('ticket=')
  })

  it('票据失败保留错误，可重新申请票据', async () => {
    api.createDownloadTicket
      .mockRejectedValueOnce(new Error('服务暂不可用'))
      .mockResolvedValueOnce({ url: '/download' })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    await downloadFile(file)
    expect(state.downloads[0].status).toBe('error')
    expect(state.downloads[0].error).toBe('服务暂不可用')
    await downloadFile(file)
    expect(state.downloads[0].status).toBe('submitted')
    expect(api.createDownloadTicket).toHaveBeenCalledTimes(2)
  })

  it('退出后收到票据不会启动下载或重建记录', async () => {
    const ticket = deferred()
    api.createDownloadTicket.mockReturnValueOnce(ticket.promise)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const pending = downloadFile(file)
    resetWorkspace()
    ticket.resolve({ url: '/download' })
    await pending
    expect(click).not.toHaveBeenCalled()
    expect(state.downloads).toEqual([])
  })

  it('未完成文件不可下载，清除记录保留正在准备的请求', async () => {
    await downloadFile({ ...file, status: 0 })
    expect(api.createDownloadTicket).not.toHaveBeenCalled()
    state.downloads = [{ status: 'submitted' }, { status: 'preparing' }, { status: 'error' }]
    clearDownloadHistory()
    expect(state.downloads).toEqual([{ status: 'preparing' }])
  })
})
