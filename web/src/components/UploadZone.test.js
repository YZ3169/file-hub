import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// 上传行为测试无需加载 public/ 中的图标资源。
vi.mock('./AppIcon.vue', () => ({ default: { template: '<span />' } }))

vi.mock('../stores/uploader', () => ({
  addFiles: vi.fn(),
  toast: vi.fn(),
  state: { config: { chunkSize: 1024, backend: '本地存储' } },
}))

import { auth } from '../lib/auth'
import { relativePathOf } from '../lib/uploadFiles'
import { addFiles, toast } from '../stores/uploader'
import UploadZone from './UploadZone.vue'

let wrapper
beforeEach(() => {
  vi.clearAllMocks()
  auth.token = 'current-user'
  wrapper = mount(UploadZone)
})
afterEach(() => wrapper.unmount())

function dropEntry(entry) {
  return wrapper.trigger('drop', {
    dataTransfer: { items: [{ kind: 'file', webkitGetAsEntry: () => entry }] },
  })
}

describe('UploadZone', () => {
  it('选择文件和选择文件夹各打开自己的选择器，输入框点击不会冒泡重复打开', async () => {
    const plain = wrapper.get('input:not([webkitdirectory])').element
    const folder = wrapper.get('input[webkitdirectory]').element
    Object.defineProperty(folder, 'webkitdirectory', { value: true })
    const plainClick = vi.spyOn(plain, 'click')
    const folderClick = vi.spyOn(folder, 'click')
    await wrapper.get('button.btn-primary').trigger('click')
    expect(plainClick).toHaveBeenCalledOnce()
    expect(folderClick).not.toHaveBeenCalled()
    await wrapper.get('button:not(.btn-primary)').trigger('click')
    expect(folderClick).toHaveBeenCalledOnce()
    expect(plainClick).toHaveBeenCalledOnce()
  })

  it('文件夹选择保留路径并重置 input，允许再次选择同一个文件夹', async () => {
    const picker = wrapper.get('input[webkitdirectory]')
    const file = new File(['x'], 'data.txt')
    Object.defineProperty(file, 'webkitRelativePath', { value: 'root/data.txt' })
    Object.defineProperty(picker.element, 'files', { value: [file] })
    const reset = vi.spyOn(picker.element, 'value', 'set')
    await picker.trigger('change')
    await picker.trigger('change')
    expect(addFiles).toHaveBeenCalledTimes(2)
    expect(relativePathOf(addFiles.mock.calls[0][0][0])).toBe('root/data.txt')
    expect(reset).toHaveBeenCalledWith('')
  })

  it('拖入空文件夹时提示而不是生成错误文件任务', async () => {
    await dropEntry({
      name: 'empty',
      isDirectory: true,
      createReader: () => ({ readEntries: (resolve) => resolve([]) }),
    })
    await flushPromises()
    expect(addFiles).not.toHaveBeenCalled()
    expect(toast).toHaveBeenCalledWith('文件夹为空，没有可上传的文件', 'warn')
  })

  it('读取失败提示错误并且不上传不完整的文件集合', async () => {
    await dropEntry({
      name: 'locked.txt',
      isFile: true,
      file: (_, reject) => reject(new Error('无法访问')),
    })
    await flushPromises()
    expect(addFiles).not.toHaveBeenCalled()
    expect(toast).toHaveBeenCalledWith(expect.stringContaining('locked.txt'), 'error')
  })

  it.each(['account', 'unmount'])(
    '异步读取期间发生 %s 时，不向新工作区添加旧文件',
    async (reason) => {
      let finish
      await dropEntry({ name: 'private.txt', isFile: true, file: (resolve) => (finish = resolve) })
      if (reason === 'account') auth.token = 'other-user'
      else wrapper.unmount()
      finish(new File(['private'], 'private.txt'))
      await flushPromises()
      expect(addFiles).not.toHaveBeenCalled()
      expect(toast).not.toHaveBeenCalled()
    },
  )

  it('支持 Ctrl+V 粘贴文件进入上传队列，并忽略输入框内的文本粘贴', () => {
    const pasted = new File(['png-bytes'], 'image.png', { type: 'image/png' })
    const evt = Object.assign(new Event('paste', { bubbles: true, cancelable: true }), {
      clipboardData: { files: [pasted] },
    })
    window.dispatchEvent(evt)
    expect(addFiles).toHaveBeenCalledOnce()
    expect(addFiles.mock.calls[0][0][0].name).toMatch(/^screenshot-.*\.png$/)

    vi.clearAllMocks()
    const input = document.createElement('input')
    document.body.appendChild(input)
    try {
      const inputEvt = Object.assign(new Event('paste', { bubbles: true, cancelable: true }), {
        clipboardData: { files: [pasted] },
      })
      input.dispatchEvent(inputEvt)
      expect(addFiles).not.toHaveBeenCalled()
    } finally {
      input.remove()
    }
  })
})
