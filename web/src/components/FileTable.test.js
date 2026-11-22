import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../stores/uploader', async () => {
  const { reactive } = await import('vue')
  return {
    state: reactive({
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
    }),
    deleteServerFile: vi.fn(),
    downloadFile: vi.fn(),
    clearDownloadHistory: vi.fn(),
    goFilePage: vi.fn(),
    refreshFiles: vi.fn(() => Promise.resolve()),
    refreshStats: vi.fn(() => Promise.resolve()),
    updateFileQuery: vi.fn(),
    toast: vi.fn(),
  }
})

vi.mock('../lib/api', () => ({
  createShare: vi.fn(),
  listShares: vi.fn(() => Promise.resolve({ items: [], total: 0, page: 1, size: 20 })),
  cancelShare: vi.fn(),
  resetShareCode: vi.fn(),
}))

import { downloadFile, goFilePage, refreshFiles, state, updateFileQuery } from '../stores/uploader'
import FileTable from './FileTable.vue'

function file(overrides = {}) {
  return {
    fileId: 'f-1',
    fileName: 'report.pdf',
    fileHash: '0123456789abcdef0123456789abcdef',
    fileSize: 2048,
    status: 1,
    createTime: new Date(2024, 0, 2, 3, 4).toISOString(),
    ...overrides,
  }
}

describe('FileTable', () => {
  beforeEach(() => {
    state.files = []
    state.filesLoading = false
    state.filesError = ''
    state.downloads = []
    state.filePage = {
      page: 1,
      size: 10,
      total: 0,
      keyword: '',
      status: null,
      sort: 'time_desc',
    }
    vi.clearAllMocks()
  })

  it('没有文件时显示空状态', () => {
    const wrapper = mount(FileTable)

    expect(wrapper.text()).toContain('还没有文件')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('有关键字但无结果时提示清除筛选', async () => {
    state.filePage.keyword = '不存在的文件'
    const wrapper = mount(FileTable)

    expect(wrapper.text()).toContain('没有匹配的文件')
    await wrapper.find('.empty-files button').trigger('click')

    expect(updateFileQuery).toHaveBeenCalledWith({
      keyword: '',
      status: null,
      sort: 'time_desc',
    })
  })

  it('渲染文件行、大小和状态', () => {
    state.files = [file(), file({ fileId: 'f-2', fileName: 'archive.zip', status: 0 })]
    state.filePage.total = 2

    const wrapper = mount(FileTable)

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('report.pdf')
    expect(rows[0].text()).toContain('2.0 KB')
    expect(rows[0].text()).toContain('已完成')
    expect(rows[1].text()).toContain('上传中')
  })

  it('每页数量选择使用服务端分页，不在当前页内裁切文件', async () => {
    state.files = [file()]
    state.filePage.total = 56
    const wrapper = mount(FileTable)
    await wrapper.find('[aria-label="每页显示数量"]').setValue('50')
    expect(updateFileQuery).toHaveBeenCalledWith({ size: 50 })
  })

  it('点击文件名可查看详情并从详情发起下载', async () => {
    state.files = [file()]
    state.filePage.total = 1
    const wrapper = mount(FileTable, {
      global: {
        stubs: {
          FileDetailsDialog: {
            props: ['file', 'downloading'],
            emits: ['download', 'close'],
            template:
              '<section data-testid="detail-stub"><span>{{ file.fileName }}</span><button @click="$emit(\'download\')">下载</button></section>',
          },
        },
      },
    })
    await wrapper.find('[aria-label="查看 report.pdf 的详情"]').trigger('click')
    expect(wrapper.find('[data-testid="detail-stub"]').text()).toContain('report.pdf')
    await wrapper.find('[data-testid="detail-stub"] button').trigger('click')
    expect(downloadFile).toHaveBeenCalledWith(state.files[0])
  })

  it('列表刷新后详情同步上传状态，文件消失时关闭且不会自行重开', async () => {
    state.files = [file({ status: 0 })]
    state.filePage.total = 1
    const wrapper = mount(FileTable, {
      global: { stubs: { FileDetailsDialog: true } },
    })
    await wrapper.find('[aria-label="查看 report.pdf 的详情"]').trigger('click')
    expect(wrapper.findComponent({ name: 'FileDetailsDialog' }).props('file').status).toBe(0)

    state.files = [file({ status: 1 })]
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'FileDetailsDialog' }).props('file').status).toBe(1)

    state.files = []
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'FileDetailsDialog' }).exists()).toBe(false)
    state.files = [file()]
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'FileDetailsDialog' }).exists()).toBe(false)
    wrapper.unmount()
  })

  it('搜索提交时去掉首尾空格并回到第一页', async () => {
    state.files = [file()]
    state.filePage.total = 1
    state.filePage.page = 2
    const wrapper = mount(FileTable)

    await wrapper.find('input[type="search"]').setValue('  报告  ')
    await wrapper.find('form').trigger('submit')

    expect(updateFileQuery).toHaveBeenCalledWith({ keyword: '报告' })
  })

  it('分页按钮在边界禁用，翻页调用 goFilePage', async () => {
    state.files = [file()]
    state.filePage.total = 25

    const wrapper = mount(FileTable)

    const prev = wrapper.find('button[aria-label="上一页"]')
    const next = wrapper.find('button[aria-label="下一页"]')
    expect(prev.attributes('disabled')).toBeDefined()
    expect(next.attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('1 / 3')

    await next.trigger('click')
    expect(goFilePage).toHaveBeenCalledWith(2)
  })

  it('状态筛选和排序变化会重置查询', async () => {
    state.files = [file()]
    state.filePage.total = 1
    const wrapper = mount(FileTable)

    const [statusSelect, sortSelect] = wrapper.findAll('select')
    await statusSelect.setValue('1')
    await sortSelect.setValue('size_asc')

    expect(updateFileQuery).toHaveBeenCalledWith({ status: 1 })
    expect(updateFileQuery).toHaveBeenCalledWith({ sort: 'size_asc' })
  })

  it('分享按钮只对已完成文件显示，点击打开分享弹窗', async () => {
    state.files = [file(), file({ fileId: 'f-2', fileName: 'archive.zip', status: 0 })]
    state.filePage.total = 2
    const wrapper = mount(FileTable)

    const rows = wrapper.findAll('tbody tr')
    expect(rows[0].find('[data-testid="share-file-button"]').exists()).toBe(true)
    expect(rows[1].find('[data-testid="share-file-button"]').exists()).toBe(false)

    await rows[0].find('[data-testid="share-file-button"]').trigger('click')
    expect(wrapper.find('[data-testid="share-dialog"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="share-dialog"]').text()).toContain('report.pdf')
  })

  it('「我的分享」按钮打开分享管理面板', async () => {
    const wrapper = mount(FileTable)

    await wrapper.find('[data-testid="my-shares-button"]').trigger('click')
    expect(wrapper.find('[data-testid="shares-panel"]').exists()).toBe(true)
  })

  it('加载时不误报空列表，失败时提供重试', async () => {
    state.filesLoading = true
    const wrapper = mount(FileTable)
    expect(wrapper.text()).toContain('正在加载文件')
    expect(wrapper.text()).not.toContain('还没有文件')
    state.filesLoading = false
    state.filesError = '请求过于频繁，请稍后再试'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="alert"]').text()).toContain(state.filesError)
    expect(wrapper.text()).not.toContain('还没有文件')
    await wrapper.find('[role="alert"] button').trigger('click')
    expect(refreshFiles).toHaveBeenCalledOnce()
  })

  it('准备期间禁用下载，上传中的文件不能下载', async () => {
    state.files = [file(), file({ fileId: 'f-2', status: 0 })]
    state.downloads = [{ id: 'd-1', fileId: 'f-1', fileName: 'report.pdf', status: 'preparing' }]
    const wrapper = mount(FileTable)
    const button = wrapper.find('button[aria-label="下载 report.pdf"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.download-button')).toHaveLength(1)
    expect(wrapper.text()).toContain('上传完成后可下载')
    await button.trigger('click')
    expect(downloadFile).not.toHaveBeenCalled()
    state.downloads = []
    await wrapper.vm.$nextTick()
    await button.trigger('click')
    expect(downloadFile).toHaveBeenCalledWith(state.files[0])
  })

  it('下载失败可在最近下载中重试', async () => {
    state.downloads = [
      {
        id: 'd-1',
        fileId: 'f-1',
        fileName: 'report.pdf',
        fileSize: 2048,
        status: 'error',
        error: '网络中断',
      },
    ]
    const wrapper = mount(FileTable)
    const panel = wrapper.find('[aria-label="最近下载"]')
    expect(panel.text()).toContain('网络中断')
    await panel.find('button[aria-label="重试下载 report.pdf"]').trigger('click')
    expect(downloadFile).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'f-1', status: 1 }))
  })
})
