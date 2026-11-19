import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', () => ({
  createShare: vi.fn(),
  resetShareCode: vi.fn(),
}))

vi.mock('../lib/clipboard', () => ({
  copyText: vi.fn(() => Promise.resolve(true)),
  buildShareUrl: vi.fn((token) => `http://localhost:8081/#/s/${token}`),
}))

vi.mock('../stores/uploader', async () => {
  const { reactive } = await import('vue')
  return {
    state: reactive({ config: { desktop: false } }),
    toast: vi.fn(),
  }
})

import { createShare, resetShareCode } from '../lib/api'
import { copyText } from '../lib/clipboard'
import { state } from '../stores/uploader'
import ShareDialog from './ShareDialog.vue'

const TOKEN = 'A'.repeat(43)

const file = { fileId: 'f-1', fileName: '报告.pdf' }

function created(overrides = {}) {
  return {
    token: TOKEN,
    code: 'K7Q2',
    sharePath: `/#/s/${TOKEN}`,
    expiresAt: '2026-12-01T10:00:00',
    createTime: '2026-11-19T10:00:00',
    ...overrides,
  }
}

function mountDialog() {
  return mount(ShareDialog, {
    props: { file },
    global: { stubs: { teleport: true } },
  })
}

describe('ShareDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.config = { desktop: false }
  })

  it('默认 7 天，创建后展示链接与 4 位提取码', async () => {
    createShare.mockResolvedValue(created())
    const wrapper = mountDialog()

    await wrapper.find('[data-testid="share-create"]').trigger('click')
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith('f-1', 7)
    expect(wrapper.find('[data-testid="share-link"]').element.value).toBe(
      `http://localhost:8081/#/s/${TOKEN}`,
    )
    expect(wrapper.find('[data-testid="share-code"]').text()).toBe('K7Q2')
    expect(wrapper.text()).toContain('只显示这一次')
  })

  it('可以切换有效期，点击复制链接调用剪贴板', async () => {
    createShare.mockResolvedValue(created())
    const wrapper = mountDialog()

    const choices = wrapper.findAll('.choice')
    await choices[0].trigger('click') // 1 天
    await wrapper.find('[data-testid="share-create"]').trigger('click')
    await flushPromises()

    expect(createShare).toHaveBeenCalledWith('f-1', 1)

    const copyButton = wrapper.findAll('.result-row button')[0]
    await copyButton.trigger('click')
    await flushPromises()

    expect(copyText).toHaveBeenCalledWith(`http://localhost:8081/#/s/${TOKEN}`)
  })

  it('换一个提取码会调用服务端并更新展示', async () => {
    createShare.mockResolvedValue(created())
    resetShareCode.mockResolvedValue(created({ code: 'M9X4' }))
    const wrapper = mountDialog()

    await wrapper.find('[data-testid="share-create"]').trigger('click')
    await flushPromises()

    const resetButton = wrapper.findAll('button').find((b) => b.text().includes('换一个提取码'))
    await resetButton.trigger('click')
    await flushPromises()

    expect(resetShareCode).toHaveBeenCalledWith(TOKEN)
    expect(wrapper.find('[data-testid="share-code"]').text()).toBe('M9X4')
  })

  it('桌面版显著提示 127.0.0.1 链接只能本机打开', () => {
    state.config = { desktop: true }
    const wrapper = mountDialog()

    expect(wrapper.text()).toContain('127.0.0.1')
    expect(wrapper.text()).toContain('服务器版')
  })

  it('创建失败时展示服务端文案', async () => {
    createShare.mockRejectedValue(new Error('文件已失效，无法分享'))
    const wrapper = mountDialog()

    await wrapper.find('[data-testid="share-create"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('文件已失效')
  })
})
