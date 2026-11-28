import { describe, expect, it } from 'vitest'
import { isSafeUrl, parseMarkdown } from './markdown'

describe('markdown parser', () => {
  it('校验允许的链接协议并拦截危险协议', () => {
    expect(isSafeUrl('https://example.com/docs')).toBe(true)
    expect(isSafeUrl('http://localhost:8080/a')).toBe(true)
    expect(isSafeUrl('mailto:team@example.com')).toBe(true)
    expect(isSafeUrl('#section-1')).toBe(true)

    expect(isSafeUrl('javascript:alert(1)')).toBe(false)
    expect(isSafeUrl('JAVASCRIPT:alert(1)')).toBe(false)
    expect(isSafeUrl('data:text/html,<script>alert(1)</script>')).toBe(false)
    expect(isSafeUrl('vbscript:msgbox(1)')).toBe(false)
  })

  it('解析标题、列表、引用、分割线与代码块 AST', () => {
    const blocks = parseMarkdown(
      [
        '# 标题一',
        '',
        '包含 **粗体**、*斜体*、`code` 与 [文档](https://example.com)。',
        '',
        '> 引用内容',
        '',
        '- 项目 A',
        '- 项目 B',
        '',
        '1. 步骤一',
        '2. 步骤二',
        '',
        '---',
        '',
        '```js',
        'const x = 1 < 2',
        '```',
      ].join('\n'),
    )

    expect(blocks.map((b) => b.type)).toEqual([
      'heading',
      'paragraph',
      'blockquote',
      'ul',
      'ol',
      'hr',
      'code',
    ])
    expect(blocks[0]).toEqual({
      type: 'heading',
      level: 1,
      runs: [{ type: 'text', text: '标题一' }],
    })
    expect(blocks[1].runs).toEqual([
      { type: 'text', text: '包含 ' },
      { type: 'strong', text: '粗体' },
      { type: 'text', text: '、' },
      { type: 'em', text: '斜体' },
      { type: 'text', text: '、' },
      { type: 'code', text: 'code' },
      { type: 'text', text: ' 与 ' },
      { type: 'link', text: '文档', href: 'https://example.com' },
      { type: 'text', text: '。' },
    ])
    expect(blocks[3]).toEqual({
      type: 'ul',
      items: [[{ type: 'text', text: '项目 A' }], [{ type: 'text', text: '项目 B' }]],
    })
    expect(blocks[4]).toEqual({
      type: 'ol',
      items: [[{ type: 'text', text: '步骤一' }], [{ type: 'text', text: '步骤二' }]],
    })
    expect(blocks[6]).toEqual({ type: 'code', lang: 'js', text: 'const x = 1 < 2' })
  })

  it('将不安全链接和原始 HTML 标签作为纯文本处理', () => {
    const blocks = parseMarkdown(
      '点击 [XSS](javascript:alert(1)) 以及 <img src=x onerror=alert(1)> <script>bad()</script>',
    )
    expect(blocks).toHaveLength(1)
    expect(blocks[0].type).toBe('paragraph')
    const linkTokens = blocks[0].runs.filter((t) => t.type === 'link')
    expect(linkTokens).toHaveLength(0)
    expect(blocks[0].runs.map((t) => t.text).join('')).toContain('XSS')
    expect(blocks[0].runs.map((t) => t.text).join('')).toContain('<script>bad()</script>')
  })
})
