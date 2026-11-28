import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', () => ({
  createDownloadTicket: vi.fn(),
  fetchTextPreview: vi.fn(),
}))

vi.mock('../lib/clipboard', () => ({
  copyText: vi.fn(() => Promise.resolve(true)),
}))

vi.mock('../stores/uploader', () => ({
  toast: vi.fn(),
}))

import { createDownloadTicket, fetchTextPreview } from '../lib/api'
import FilePreviewDialog from './FilePreviewDialog.vue'

describe('FilePreviewDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    createDownloadTicket.mockResolvedValue({ url: '/api/file/f-1/download?ticket=tk' })
  })

  it('图片文件渲染 img 且附带 inline=true 参数', async () => {
    const wrapper = mount(FilePreviewDialog, {
      props: { file: { fileId: 'f-1', fileName: 'photo.png', fileSize: 1024 } },
    })
    await flushPromises()

    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/api/file/f-1/download?ticket=tk&inline=true')
  })

  it('文本文件调用 Range 文本预览并展示行号与截断提示', async () => {
    fetchTextPreview.mockResolvedValue({
      text: 'line one\nline two',
      truncated: true,
      totalSize: 5_000_000,
    })

    const wrapper = mount(FilePreviewDialog, {
      props: {
        file: { fileId: 'f-2', fileName: 'app.log', fileSize: 5_000_000 },
        directUrl: '/api/file/f-2/download?ticket=share',
      },
    })
    await flushPromises()

    expect(createDownloadTicket).not.toHaveBeenCalled()
    expect(fetchTextPreview).toHaveBeenCalledWith('/api/file/f-2/download?ticket=share&inline=true')
    expect(wrapper.find('.truncated-banner').text()).toContain('前 2 MB')
    const lines = wrapper.findAll('.code-line')
    expect(lines).toHaveLength(2)
    expect(lines[0].text()).toContain('1line one')
  })

  it('不支持在线预览的格式展示回退下载引导', async () => {
    const wrapper = mount(FilePreviewDialog, {
      props: { file: { fileId: 'f-3', fileName: 'installer.iso', fileSize: 10_000_000 } },
    })
    await flushPromises()

    expect(createDownloadTicket).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('暂不支持在线预览此格式')
    await wrapper.find('.center-state button').trigger('click')
    expect(wrapper.emitted('download')).toHaveLength(1)
  })

  it('Markdown 文件默认安全渲染并可切换查看源码', async () => {
    fetchTextPreview.mockResolvedValue({
      text: '# 项目说明\n\n点击 [恶链](javascript:alert(1)) 与 [主页](https://example.com)\n\n<script>alert(1)</script>',
      truncated: false,
      totalSize: 80,
    })

    const wrapper = mount(FilePreviewDialog, {
      props: {
        file: { fileId: 'f-md', fileName: 'README.md', fileSize: 80 },
      },
    })
    await flushPromises()

    const mdView = wrapper.find('[data-testid="markdown-preview"]')
    expect(mdView.exists()).toBe(true)
    expect(mdView.find('h1').text()).toBe('项目说明')
    expect(mdView.find('script').exists()).toBe(false)
    const links = mdView.findAll('a')
    expect(links).toHaveLength(1)
    expect(links[0].attributes('href')).toBe('https://example.com')

    await wrapper.find('[data-testid="toggle-markdown-mode"]').trigger('click')
    expect(wrapper.find('[data-testid="markdown-preview"]').exists()).toBe(false)
    expect(wrapper.find('.code-view').exists()).toBe(true)
  })

  it('传入文件列表时支持上一张/下一张与键盘方向键切换', async () => {
    const first = { fileId: 'f-1', fileName: 'photo.png', fileSize: 1024 }
    const second = { fileId: 'f-2', fileName: 'note.txt', fileSize: 128 }
    const wrapper = mount(FilePreviewDialog, {
      props: {
        file: first,
        items: [first, second],
      },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="preview-prev"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="preview-next"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('1 / 2')

    await wrapper.find('[data-testid="preview-next"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([second])

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }))
    expect(wrapper.emitted('select')?.[1]).toEqual([second])

    await wrapper.setProps({ file: second })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }))
    expect(wrapper.emitted('select')?.[2]).toEqual([first])
  })
})
