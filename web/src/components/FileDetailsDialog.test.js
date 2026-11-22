import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/clipboard', () => ({ copyText: vi.fn() }))

import { copyText } from '../lib/clipboard'
import { formatDate } from '../lib/format'
import FileDetailsDialog from './FileDetailsDialog.vue'

const HASH = '0123456789abcdef'.repeat(4)
const file = {
  fileId: 'details-1',
  fileName: '这是一份完整文件名-项目资料.zip',
  fileSize: 123456789,
  createTime: '2026-11-21T10:20:30',
  status: 1,
  fileHash: HASH,
}

const wrappers = []
const originalShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')

function render(overrides = {}) {
  const wrapper = mount(FileDetailsDialog, {
    props: { file: { ...file, ...overrides } },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('FileDetailsDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    copyText.mockResolvedValue(true)
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(function () {
        this.open = true
        this.querySelector('[autofocus]')?.focus()
      }),
    })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true,
      value: vi.fn(function () {
        this.open = false
      }),
    })
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    document.body.innerHTML = ''
    for (const [name, descriptor] of [
      ['showModal', originalShowModal],
      ['close', originalClose],
    ]) {
      if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor)
      else delete HTMLDialogElement.prototype[name]
    }
  })

  it('使用原生模态对话框并展示完整文件名、精确大小、时间和完整哈希', () => {
    const wrapper = render()
    expect(HTMLDialogElement.prototype.showModal).toHaveBeenCalledOnce()
    expect(wrapper.element.open).toBe(true)
    expect(wrapper.get('[data-testid="file-details-name"]').text()).toBe(file.fileName)
    expect(wrapper.get('[data-testid="file-details-bytes"]').text()).toBe('123,456,789 字节')
    expect(wrapper.text()).toContain('117.7 MB')
    expect(wrapper.text()).toContain(formatDate(file.createTime))
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.get('[data-testid="file-details-hash"]').element.value).toBe(HASH)
    expect(wrapper.get('[data-testid="file-details-hash"]').attributes('readonly')).toBeDefined()
  })

  it('零字节文件显示精确大小且已完成文件可请求下载', async () => {
    const wrapper = render({ fileSize: 0 })
    expect(wrapper.text()).toContain('0 B')
    expect(wrapper.get('[data-testid="file-details-bytes"]').text()).toBe('0 字节')
    await wrapper.get('[data-testid="file-details-download"]').trigger('click')
    expect(wrapper.emitted('download')).toEqual([[{ ...file, fileSize: 0 }]])
  })

  it('未完成文件说明未完成校验并阻止下载', async () => {
    const wrapper = render({ status: 0 })
    expect(wrapper.text()).toContain('上传中')
    expect(wrapper.text()).toContain('尚未完成上传与校验')
    expect(wrapper.text()).toContain('预期值')
    expect(wrapper.get('[data-testid="file-details-download"]').element.disabled).toBe(true)
    await wrapper.get('[data-testid="file-details-download"]').trigger('click')
    expect(wrapper.emitted('download')).toBeUndefined()
  })

  it('父组件准备下载时禁用按钮，准备结束后可再次下载', async () => {
    const wrapper = render()
    await wrapper.setProps({ downloading: true })
    const button = wrapper.get('[data-testid="file-details-download"]')
    expect(button.element.disabled).toBe(true)
    expect(button.text()).toContain('准备中…')
    expect(button.attributes('aria-busy')).toBe('true')
    await button.trigger('click')
    expect(wrapper.emitted('download')).toBeUndefined()
    await wrapper.setProps({ downloading: false })
    await button.trigger('click')
    expect(wrapper.emitted('download')).toEqual([[file]])
  })

  it('未知或缺失的元数据不伪造校验值或文件大小', () => {
    const wrapper = render({ fileHash: null, fileSize: null, createTime: null, status: 9 })
    expect(wrapper.get('[data-testid="file-details-bytes"]').text()).toBe('—')
    expect(wrapper.text()).toContain('未知状态')
    expect(wrapper.text()).toContain('暂无 SHA-256 记录')
    expect(wrapper.get('[data-testid="copy-file-hash"]').element.disabled).toBe(true)
    expect(wrapper.get('[data-testid="file-details-download"]').element.disabled).toBe(true)
  })

  it('复制完整哈希并显示成功反馈，不把复制描述为已校验', async () => {
    const wrapper = render()
    await wrapper.get('[data-testid="copy-file-hash"]').trigger('click')
    await flushPromises()
    expect(copyText).toHaveBeenCalledWith(HASH)
    expect(wrapper.get('[role="status"]').text()).toContain('SHA-256 已复制')
    expect(wrapper.get('[role="status"]').text()).toContain('可与本地文件的校验值对比')
    expect(wrapper.get('[role="status"]').text()).not.toContain('校验通过')
  })

  it.each([false, new Error('clipboard denied')])(
    '复制失败保留可手动选择的完整哈希',
    async (result) => {
      if (result instanceof Error) copyText.mockRejectedValueOnce(result)
      else copyText.mockResolvedValueOnce(result)
      const wrapper = render()
      await wrapper.get('[data-testid="copy-file-hash"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[role="status"]').text()).toContain('复制失败')
      const text = wrapper.get('[data-testid="file-details-hash"]')
      expect(text.element.disabled).toBe(false)
      text.element.select()
      expect(text.element.selectionEnd - text.element.selectionStart).toBe(HASH.length)
    },
  )

  it('复制等待期间阻止重复点击，文件变化后忽略旧复制结果', async () => {
    let finishCopy
    copyText.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishCopy = resolve
        }),
    )
    const wrapper = render()
    const button = wrapper.get('[data-testid="copy-file-hash"]')
    button.element.click()
    button.element.click()
    await flushPromises()
    expect(copyText).toHaveBeenCalledOnce()
    expect(button.element.disabled).toBe(true)
    await wrapper.setProps({ file: { ...file, fileId: 'another', fileHash: 'a'.repeat(64) } })
    finishCopy(true)
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(button.element.disabled).toBe(false)
    expect(wrapper.get('[data-testid="file-details-hash"]').element.value).toBe('a'.repeat(64))
  })

  it('原生 Escape 取消事件和关闭按钮通知父组件', async () => {
    const wrapper = render()
    const cancel = new Event('cancel', { cancelable: true })
    wrapper.element.dispatchEvent(cancel)
    expect(cancel.defaultPrevented).toBe(true)
    expect(wrapper.emitted('close')).toHaveLength(1)
    await wrapper.get('[aria-label="关闭文件详情"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('关闭并卸载弹窗后将焦点恢复到打开它的按钮', () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = render()
    expect(document.activeElement).toBe(wrapper.get('[aria-label="关闭文件详情"]').element)
    wrapper.unmount()
    expect(HTMLDialogElement.prototype.close).toHaveBeenCalledOnce()
    expect(document.activeElement).toBe(opener)
  })

  it('仅点击对话框外的遮罩关闭，内容区域不会误关闭', async () => {
    const wrapper = render()
    vi.spyOn(wrapper.element, 'getBoundingClientRect').mockReturnValue({
      left: 50,
      right: 500,
      top: 50,
      bottom: 600,
    })
    await wrapper.trigger('click', { clientX: 100, clientY: 100 })
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.trigger('click', { clientX: 10, clientY: 10 })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
