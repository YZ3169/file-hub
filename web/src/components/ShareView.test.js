import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
    verifyShareCode: vi.fn(),
  }
})

import { ApiError, getPublicConfig, getPublicShareMeta, verifyShareCode } from '../lib/api'
import ShareView from './ShareView.vue'

const TOKEN = 'A'.repeat(43)

function mountView() {
  return mount(ShareView, { props: { token: TOKEN } })
}

describe('ShareView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
})
