import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { listShares, resetShareCode } from '../lib/api'
import { toast } from '../stores/uploader'
import SharesPanel from './SharesPanel.vue'

vi.mock('../lib/api', () => ({
  listShares: vi.fn(),
  resetShareCode: vi.fn(),
  cancelShare: vi.fn(),
}))
vi.mock('../stores/uploader', () => ({ toast: vi.fn() }))
enableAutoUnmount(afterEach)
const item = {
  token: 'test-token',
  fileName: '报告.pdf',
  state: 'ACTIVE',
  downloadCount: 0,
  expiresAt: null,
}
const data = (page = 1) => ({ items: [item], page, total: 21 })
function deferred() {
  let resolve
  const promise = new Promise((res) => {
    resolve = res
  })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetAllMocks()
  listShares.mockResolvedValue(data())
})

describe('分享管理加载与操作', () => {
  it('加载期间显示等待信息，失败后只显示错误与重试', async () => {
    const pending = deferred()
    listShares.mockReturnValueOnce(pending.promise).mockRejectedValueOnce(new Error('网络断开'))
    const wrapper = mount(SharesPanel)
    expect(wrapper.find('[role="status"]').text()).toContain('正在加载分享')
    expect(wrapper.text()).not.toContain('还没有分享')
    pending.resolve(data())
    await flushPromises()
    await wrapper.find('[aria-label="下一页"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('网络断开')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('还没有分享')
    listShares.mockResolvedValueOnce(data(2))
    await wrapper.find('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(listShares).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    expect(wrapper.find('table').text()).toContain('报告.pdf')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('成功返回空列表后才显示空状态', async () => {
    listShares.mockResolvedValue({ items: [], total: 0, page: 1 })
    const wrapper = mount(SharesPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有分享')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('重置提取码时禁用翻页，关闭后迟到响应不弹旧账号的提示', async () => {
    const pending = deferred()
    resetShareCode.mockReturnValueOnce(pending.promise)
    const wrapper = mount(SharesPanel)
    await flushPromises()
    await wrapper.find('[aria-label="重置 报告.pdf 的提取码"]').trigger('click')
    expect(wrapper.find('[aria-label="下一页"]').element.disabled).toBe(true)
    wrapper.unmount()
    pending.resolve({ code: 'AB12', expiresAt: null })
    await flushPromises()
    expect(toast).not.toHaveBeenCalled()
    expect(listShares).toHaveBeenCalledTimes(1)
  })

  it('打开时聚焦关闭按钮，Esc 可关闭并恢复原来的焦点', async () => {
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = mount(SharesPanel, { attachTo: document.body })
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.find('[aria-label="关闭我的分享"]').element)
    await wrapper.find('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
    expect(document.activeElement).toBe(opener)
    opener.remove()
  })
})
