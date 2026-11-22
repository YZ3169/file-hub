import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { copyText } from './clipboard'

const originalExecCommand = Object.getOwnPropertyDescriptor(document, 'execCommand')

beforeEach(() => {
  vi.stubGlobal('isSecureContext', false)
  vi.stubGlobal('navigator', { clipboard: undefined })
  Object.defineProperty(document, 'execCommand', {
    configurable: true,
    value: vi.fn(() => true),
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
  if (originalExecCommand) Object.defineProperty(document, 'execCommand', originalExecCommand)
  else delete document.execCommand
})

function focusButton(inDialog = false) {
  const container = document.createElement(inDialog ? 'dialog' : 'div')
  if (inDialog) container.setAttribute('open', '')
  const button = document.createElement('button')
  container.appendChild(button)
  document.body.appendChild(container)
  button.focus()
  return { button, container }
}

describe('copyText', () => {
  it('安全环境直接使用 Clipboard API，不移动焦点', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('isSecureContext', true)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { button } = focusButton()

    expect(await copyText('完整摘要')).toBe(true)
    expect(writeText).toHaveBeenCalledWith('完整摘要')
    expect(document.execCommand).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(button)
  })

  it('不支持 Clipboard API 时选中全部内容，复制后恢复焦点并清理临时控件', async () => {
    const { button } = focusButton()
    document.execCommand.mockImplementation((command) => {
      const area = document.activeElement
      expect(command).toBe('copy')
      expect(area.tagName).toBe('TEXTAREA')
      expect(area.parentElement).toBe(document.body)
      expect(area.value.slice(area.selectionStart, area.selectionEnd)).toBe('abc123')
      return true
    })

    expect(await copyText('abc123')).toBe(true)
    expect(document.activeElement).toBe(button)
    expect(document.querySelector('textarea')).toBeNull()
  })

  it('原生弹窗中的回退控件留在弹窗内，避免放到不可交互的背景区域', async () => {
    const { button, container } = focusButton(true)
    document.execCommand.mockImplementation(() => {
      const area = document.activeElement
      expect(area.parentElement).toBe(container)
      expect(area.value.slice(area.selectionStart, area.selectionEnd)).toBe('a'.repeat(64))
      return true
    })

    expect(await copyText('a'.repeat(64))).toBe(true)
    expect(document.activeElement).toBe(button)
    expect(container.querySelector('textarea')).toBeNull()
  })

  it('Clipboard API 权限失败后仍可在弹窗内回退复制', async () => {
    vi.stubGlobal('isSecureContext', true)
    vi.stubGlobal('navigator', {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('permission denied')) },
    })
    const { button, container } = focusButton(true)
    document.execCommand.mockImplementation(() => {
      expect(document.activeElement.parentElement).toBe(container)
      return true
    })

    expect(await copyText('fallback')).toBe(true)
    expect(document.activeElement).toBe(button)
  })

  it.each([false, new Error('copy unavailable')])(
    '回退失败时返回 false，清理控件并恢复焦点',
    async (result) => {
      const { button } = focusButton(true)
      document.execCommand.mockImplementation(() => {
        if (result instanceof Error) throw result
        return result
      })

      expect(await copyText('hash')).toBe(false)
      expect(document.activeElement).toBe(button)
      expect(document.querySelector('textarea')).toBeNull()
    },
  )
})
