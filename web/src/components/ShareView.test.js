import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', () => {
  class ApiError extends Error {
    constructor(code, message) {
      super(message)
      this.code = code
    }
  }
  return {
    ApiError,
    getPublicConfig: vi.fn(),
    getPublicShareMeta: vi.fn(),
    saveShareToMySpace: vi.fn(),
    verifyShareCode: vi.fn(),
    createDownloadTicket: vi.fn(),
    fetchTextPreview: vi.fn(),
  }
})

import {
  ApiError,
  getPublicConfig,
  getPublicShareMeta,
  saveShareToMySpace,
  verifyShareCode,
} from '../lib/api'
import { auth } from '../lib/auth'
import ShareView from './ShareView.vue'

const TOKEN = 'A'.repeat(43)

enableAutoUnmount(afterEach)

afterEach(() => {
  vi.useRealTimers()
})

function mountView() {
  return mount(ShareView, { props: { token: TOKEN } })
}

async function verifyFile(overrides = {}) {
  verifyShareCode.mockResolvedValue({
    fileId: 'f-1',
    fileName: '报告.pdf',
    fileSize: 2048,
    downloadUrl: '/api/file/f-1/download?ticket=abc',
    ticketExpiresAt: new Date(Date.now() + 60_000).toISOString(),
    ...overrides,
  })
  const wrapper = mountView()
  await flushPromises()
  await wrapper.find('[data-testid="share-code-input"]').setValue('K7Q2')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  return wrapper
}

describe('ShareView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    auth.token = null
    getPublicConfig.mockResolvedValue({ desktop: false, chunkSize: 1024 })
    getPublicShareMeta.mockResolvedValue({ state: 'ACTIVE', expiresAt: null, locked: false })
  })

  it('分享不存在时显示链接无效', async () => {
    getPublicShareMeta.mockRejectedValue(new ApiError(42001, '分享不存在或已被取消'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('链接无效')
    expect(wrapper.text()).toContain('分享不存在或已被取消')
  })

  it('已过期时显示分享已过期', async () => {
    getPublicShareMeta.mockRejectedValue(new ApiError(42002, '分享已过期'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('分享已过期')
  })

  it('锁定期间输入框和按钮都禁用', async () => {
    getPublicShareMeta.mockResolvedValue({
      state: 'LOCKED',
      locked: true,
      lockedUntil: '2099-01-01T00:00:00',
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('[data-testid="share-code-input"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="share-submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('输错次数过多')
  })

  it('输入自动转大写并过滤非字母数字', async () => {
    const wrapper = mountView()
    await flushPromises()

    const input = wrapper.find('[data-testid="share-code-input"]')
    await input.setValue('a1!b2c')

    expect(input.element.value).toBe('A1B2')
  })

  it('提取码错误时展示剩余次数', async () => {
    verifyShareCode.mockRejectedValue(new ApiError(42003, '提取码错误，还可尝试 9 次'))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-testid="share-code-input"]').setValue('ZZZZ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(verifyShareCode).toHaveBeenCalledWith(TOKEN, 'ZZZZ')
    expect(wrapper.find('[role="alert"]').text()).toContain('还可尝试 9 次')
  })

  it('校验成功后展示文件名与下载按钮', async () => {
    verifyShareCode.mockResolvedValue({
      fileId: 'f-1',
      fileName: '报告.pdf',
      fileSize: 2048,
      downloadUrl: '/api/file/f-1/download?ticket=abc',
      ticketExpiresAt: '2099-01-01T00:00:00',
      shareExpiresAt: null,
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-testid="share-code-input"]').setValue('K7Q2')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const result = wrapper.find('[data-testid="share-result"]')
    expect(result.text()).toContain('报告.pdf')
    expect(result.text()).toContain('2.0 KB')
    expect(result.text()).toContain('下载')
  })

  it('本机访问时提示链接只在这台电脑有效', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('127.0.0.1')
  })

  it('网络加载失败可以重试，恢复后展示提取码输入框', async () => {
    getPublicShareMeta.mockRejectedValueOnce(new Error('无法连接服务端'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('无法连接服务端')
    await wrapper.find('.state-box button').trigger('click')
    await flushPromises()

    expect(getPublicShareMeta).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="share-code-input"]').exists()).toBe(true)
  })

  it('锁定时间到期后自动恢复输入和提取操作', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    getPublicShareMeta.mockResolvedValue({
      state: 'ACTIVE',
      locked: true,
      lockedUntil: new Date(Date.now() + 2000).toISOString(),
    })
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="share-code-input"]').element.disabled).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)

    expect(wrapper.find('[data-testid="share-code-input"]').element.disabled).toBe(false)
    await wrapper.find('[data-testid="share-code-input"]').setValue('K7Q2')
    expect(wrapper.find('[data-testid="share-submit"]').element.disabled).toBe(false)
    expect(wrapper.find('.locked').exists()).toBe(false)
  })

  it('下载凭证过期后禁用下载并可返回重新校验', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    const wrapper = await verifyFile({
      ticketExpiresAt: new Date(Date.now() + 2000).toISOString(),
    })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    expect(wrapper.find('[data-testid="share-download"]').element.disabled).toBe(false)

    await vi.advanceTimersByTimeAsync(2000)

    expect(wrapper.find('[data-testid="share-download"]').element.disabled).toBe(true)
    await wrapper.find('[data-testid="share-download"]').trigger('click')
    expect(click).not.toHaveBeenCalled()
    await wrapper.find('.hint.warn button').trigger('click')
    expect(wrapper.find('[data-testid="share-code-input"]').element.value).toBe('')
    expect(wrapper.find('[data-testid="share-result"]').exists()).toBe(false)
  })

  it('后台暂停计时器后点击也会检查凭证是否已过期', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    const wrapper = await verifyFile()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    vi.setSystemTime(Date.now() + 61_000)
    await wrapper.find('[data-testid="share-download"]').trigger('click')

    expect(click).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="share-download"]').element.disabled).toBe(true)
  })

  it('发起下载后说明浏览器接管进度，不声称下载完成', async () => {
    const wrapper = await verifyFile()
    let downloaded
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      downloaded = { url: this.getAttribute('href'), name: this.download }
    })

    await wrapper.find('[data-testid="share-download"]').trigger('click')

    expect(downloaded).toEqual({ url: '/api/file/f-1/download?ticket=abc', name: '报告.pdf' })
    expect(wrapper.find('.download-feedback').text()).toContain('已请求下载')
    expect(wrapper.find('.download-feedback').text()).not.toContain('下载完成')
    expect(wrapper.find('[data-testid="share-download"]').text()).toBe('再次下载')
  })

  it('免提取码公开分享无需输入提取码即可直接提取，支持预览和登录后转存网盘', async () => {
    auth.token = 'logged-in'
    getPublicShareMeta.mockResolvedValue({
      state: 'ACTIVE',
      requireCode: false,
      maxDownloads: 5,
      downloadCount: 1,
      locked: false,
    })
    verifyShareCode.mockResolvedValue({
      fileId: 'f-1',
      fileName: 'notes.txt',
      fileSize: 512,
      downloadUrl: '/api/file/f-1/download?ticket=tk-99',
      ticketExpiresAt: new Date(Date.now() + 60_000).toISOString(),
    })
    saveShareToMySpace.mockResolvedValue({ fileId: 'f-copy', relativePath: 'notes.txt' })

    const wrapper = mount(ShareView, {
      props: { token: TOKEN },
      global: { stubs: { FilePreviewDialog: { template: '<div data-testid="preview-stub" />' } } },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="share-code-input"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="share-open-hint"]').exists()).toBe(true)

    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(verifyShareCode).toHaveBeenCalledWith(TOKEN, '')

    await wrapper.find('[data-testid="share-preview"]').trigger('click')
    expect(wrapper.find('[data-testid="preview-stub"]').exists()).toBe(true)

    await wrapper.find('[data-testid="share-save"]').trigger('click')
    await flushPromises()
    expect(saveShareToMySpace).toHaveBeenCalledWith(TOKEN, { code: null, ticket: 'tk-99' })
    expect(wrapper.find('[data-testid="share-saved"]').text()).toContain('notes.txt')
  })
})
