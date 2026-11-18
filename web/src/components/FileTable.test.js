import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../stores/uploader', async () => {
  const { reactive } = await import('vue')
  return {
    state: reactive({
      files: [],
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
    goFilePage: vi.fn(),
    refreshFiles: vi.fn(() => Promise.resolve()),
    refreshStats: vi.fn(() => Promise.resolve()),
    updateFileQuery: vi.fn(),
  }
})

import { goFilePage, state, updateFileQuery } from '../stores/uploader'
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
})
