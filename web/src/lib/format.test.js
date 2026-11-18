import { describe, expect, it } from 'vitest'
import { formatBytes, formatDate, formatDuration, formatSpeed, percent, shortHash } from './format'

describe('formatBytes', () => {
  it('把不同量级格式化成可读单位', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(1024 ** 2)).toBe('1.0 MB')
    expect(formatBytes(1.25 * 1024 ** 3)).toBe('1.25 GB')
    expect(formatBytes(2.5 * 1024 ** 4)).toBe('2.50 TB')
  })

  it('非法输入返回占位符', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes('abc')).toBe('—')
  })
})

describe('formatSpeed / formatDuration', () => {
  it('速度为零或负数时显示占位符', () => {
    expect(formatSpeed(0)).toBe('—')
    expect(formatSpeed(-1)).toBe('—')
    expect(formatSpeed(2048)).toBe('2.0 KB/s')
  })

  it('时长按秒、分、小时分档', () => {
    expect(formatDuration(0)).toBe('—')
    expect(formatDuration(8.4)).toBe('8 秒')
    expect(formatDuration(65)).toBe('1 分 05 秒')
    expect(formatDuration(3725)).toBe('1 小时 02 分')
    expect(formatDuration(Number.NaN)).toBe('—')
  })
})

describe('formatDate', () => {
  it('空值或非法日期返回占位符', () => {
    expect(formatDate(null)).toBe('—')
    expect(formatDate('not-a-date')).toBe('—')
  })

  it('输出本地化日期时间', () => {
    const iso = new Date(2024, 0, 2, 3, 4).toISOString()
    const text = formatDate(iso)
    expect(text).toContain('2024')
    expect(text).toContain('02')
  })
})

describe('percent / shortHash', () => {
  it('百分比被限制在 0–100', () => {
    expect(percent(0, 0)).toBe(0)
    expect(percent(50, 200)).toBe(25)
    expect(percent(-10, 100)).toBe(0)
    expect(percent(150, 100)).toBe(100)
  })

  it('短哈希保留首尾，空值返回空串', () => {
    expect(shortHash('')).toBe('')
    expect(shortHash('0123456789abcdef0123456789abcdef')).toBe('0123456789…abcdef')
  })
})
