import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', () => ({
  getFolderTree: vi.fn(),
  moveFile: vi.fn(() => Promise.resolve({})),
  batchMove: vi.fn(() => Promise.resolve({ succeeded: 2 })),
  moveFolder: vi.fn(() => Promise.resolve({})),
}))

vi.mock('../stores/uploader', () => ({
  refreshActiveView: vi.fn(() => Promise.resolve()),
  toast: vi.fn(),
}))

import { getFolderTree, moveFile, moveFolder } from '../lib/api'
import { refreshActiveView } from '../stores/uploader'
import MoveDialog from './MoveDialog.vue'

describe('MoveDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('目录选择器可浏览目录并同步目标目录输入框与移动提交', async () => {
    getFolderTree
      .mockResolvedValueOnce({
        currentDir: '',
        breadcrumbs: [{ name: '全部文件', path: '' }],
        folders: [{ name: 'docs', path: 'docs' }],
      })
      .mockResolvedValueOnce({
        currentDir: 'docs',
        breadcrumbs: [
          { name: '全部文件', path: '' },
          { name: 'docs', path: 'docs' },
        ],
        folders: [{ name: '2026', path: 'docs/2026' }],
      })

    const wrapper = mount(MoveDialog, {
      props: {
        mode: 'file',
        file: { fileId: 'f-1', fileName: 'report.pdf', relativePath: 'report.pdf' },
      },
    })
    await flushPromises()

    expect(getFolderTree).toHaveBeenCalledWith({ dir: '' })
    await wrapper.find('.picker-item').trigger('click')
    await flushPromises()

    expect(getFolderTree).toHaveBeenCalledWith({ dir: 'docs' })
    const inputs = wrapper.findAll('input')
    expect(inputs[0].element.value).toBe('docs')

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(moveFile).toHaveBeenCalledWith('f-1', 'docs', 'report.pdf')
    expect(refreshActiveView).toHaveBeenCalled()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('移动文件夹模式会过滤自身路径并自动拼接叶目录名', async () => {
    getFolderTree
      .mockResolvedValueOnce({
        currentDir: '',
        breadcrumbs: [{ name: '全部文件', path: '' }],
        folders: [
          { name: 'docs', path: 'docs' },
          { name: 'archive', path: 'archive' },
        ],
      })
      .mockResolvedValueOnce({
        currentDir: 'archive',
        breadcrumbs: [
          { name: '全部文件', path: '' },
          { name: 'archive', path: 'archive' },
        ],
        folders: [],
      })

    const wrapper = mount(MoveDialog, {
      props: {
        mode: 'folder',
        folderPath: 'docs',
      },
    })
    await flushPromises()

    const items = wrapper.findAll('.picker-item')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('archive')

    await items[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('input').element.value).toBe('archive/docs')

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(moveFolder).toHaveBeenCalledWith('docs', 'archive/docs')
  })
})
