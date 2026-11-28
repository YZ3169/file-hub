import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../lib/api', () => ({
  scanIntegrity: vi.fn(),
  repairIntegrity: vi.fn(),
}))

vi.mock('../stores/uploader', () => ({
  refreshFiles: vi.fn().mockResolvedValue(),
  refreshStats: vi.fn().mockResolvedValue(),
  toast: vi.fn(),
}))

import AdminIntegrityPanel from './AdminIntegrityPanel.vue'
import { repairIntegrity, scanIntegrity } from '../lib/api'
import { refreshFiles, toast } from '../stores/uploader'

const BROKEN = {
  fileId: '1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8',
  fileName: '损坏文件.bin',
  ownerId: 7,
  fileSize: 1024,
  reason: '物理文件不存在',
  verdict: 'CORRUPT',
}

function scanResult(overrides = {}) {
  return {
    deep: false,
    checked: 3,
    brokenCount: 1,
    unverifiableCount: 0,
    truncated: false,
    items: [BROKEN],
    ...overrides,
  }
}

describe('AdminIntegrityPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.confirm = vi.fn(() => true)
  })

  it('打开后自动浅扫描并渲染问题行', async () => {
    scanIntegrity.mockResolvedValue(scanResult())
    const wrapper = mount(AdminIntegrityPanel)
    await flushPromises()

    expect(scanIntegrity).toHaveBeenCalledWith({ deep: false, limit: 100 })
    expect(wrapper.get('[data-testid="integrity-summary"]').text()).toContain('发现')
    expect(wrapper.text()).toContain('损坏文件.bin')
    expect(wrapper.text()).toContain('物理文件不存在')
    expect(wrapper.get('[data-testid="repair-button"]').attributes('disabled')).toBeDefined()
  })

  it('没有问题时显示通过状态，不出现修复按钮可用态', async () => {
    scanIntegrity.mockResolvedValue(scanResult({ checked: 5, brokenCount: 0, items: [] }))
    const wrapper = mount(AdminIntegrityPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('没有发现损坏文件')
    expect(wrapper.get('[data-testid="repair-button"]').attributes('disabled')).toBeDefined()
  })

  it('选择后启用修复，确认后调用接口并移除已修复行', async () => {
    scanIntegrity.mockResolvedValue(scanResult())
    repairIntegrity.mockResolvedValue({
      repaired: 1,
      failed: 0,
      items: [{ fileId: BROKEN.fileId, repaired: true, reason: null }],
    })

    const wrapper = mount(AdminIntegrityPanel)
    await flushPromises()

    const rowCheckbox = wrapper.get('tbody input[type="checkbox"]')
    await rowCheckbox.setValue(true)

    const repairBtn = wrapper.get('[data-testid="repair-button"]')
    expect(repairBtn.attributes('disabled')).toBeUndefined()

    await repairBtn.trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalled()
    expect(repairIntegrity).toHaveBeenCalledWith([BROKEN.fileId])
    expect(wrapper.text()).not.toContain('损坏文件.bin')
    expect(wrapper.text()).toContain('没有发现损坏文件')
    expect(toast).toHaveBeenCalledWith(expect.stringContaining('已修复 1 条'), 'success')
    expect(refreshFiles).toHaveBeenCalled()
  })

  it('全选/取消全选切换所有行', async () => {
    scanIntegrity.mockResolvedValue(
      scanResult({
        checked: 4,
        brokenCount: 2,
        items: [
          BROKEN,
          { ...BROKEN, fileId: '2f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8', fileName: 'b.bin' },
        ],
      }),
    )
    const wrapper = mount(AdminIntegrityPanel)
    await flushPromises()

    await wrapper.get('thead input[type="checkbox"]').setValue(true)
    expect(wrapper.get('[data-testid="repair-button"]').text()).toContain('（2）')

    await wrapper.get('thead input[type="checkbox"]').setValue(false)
    expect(wrapper.get('[data-testid="repair-button"]').attributes('disabled')).toBeDefined()
  })

  it('扫描失败时展示错误信息', async () => {
    scanIntegrity.mockRejectedValue(new Error('无法连接服务端'))
    const wrapper = mount(AdminIntegrityPanel)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法连接服务端')
  })

  it('暂时无法验证的记录不可勾选且不计入全选，允许保留后重试', async () => {
    const unverifiable = {
      fileId: '3f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8',
      fileName: 'timeout.bin',
      ownerId: 7,
      fileSize: 2048,
      reason: '访问存储失败：S3 timeout',
      verdict: 'UNVERIFIABLE',
    }
    scanIntegrity.mockResolvedValue(
      scanResult({
        checked: 2,
        brokenCount: 1,
        unverifiableCount: 1,
        items: [BROKEN, unverifiable],
      }),
    )

    const wrapper = mount(AdminIntegrityPanel)
    await flushPromises()

    expect(wrapper.get('[data-testid="integrity-summary"]').text()).toContain('暂时无法验证')
    const checkboxes = wrapper.findAll('tbody input[type="checkbox"]')
    expect(checkboxes[0].attributes('disabled')).toBeUndefined()
    expect(checkboxes[1].attributes('disabled')).toBeDefined()

    await wrapper.get('thead input[type="checkbox"]').setValue(true)
    expect(wrapper.get('[data-testid="repair-button"]').text()).toContain('（1）')
  })
})
