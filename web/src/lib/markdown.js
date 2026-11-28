/**
 * 零依赖安全 Markdown 解析器：输出结构化 AST 供 Vue 模板渲染，
 * 绝不使用 v-html，链接协议严格限制为 http / https / mailto / 页内锚点。
 */

const SAFE_URL = /^(https?:\/\/|mailto:|#)/i

export function isSafeUrl(url) {
  return typeof url === 'string' && SAFE_URL.test(url.trim())
}

export function parseInline(text) {
  const runs = []
  let rest = String(text ?? '')
  while (rest.length > 0) {
    // 行内代码
    const codeMatch = /^`([^`]+)`/.exec(rest)
    if (codeMatch) {
      runs.push({ type: 'code', text: codeMatch[1] })
      rest = rest.slice(codeMatch[0].length)
      continue
    }
    // 链接 [label](url)
    const linkMatch = /^\[([^\]]+)\]\(([^)\s]+)\)/.exec(rest)
    if (linkMatch) {
      const [, label, href] = linkMatch
      if (isSafeUrl(href)) {
        runs.push({ type: 'link', text: label, href: href.trim() })
      } else {
        runs.push({ type: 'text', text: label })
      }
      rest = rest.slice(linkMatch[0].length)
      continue
    }
    // 粗体 **text**
    const boldMatch = /^\*\*([^*]+)\*\*/.exec(rest)
    if (boldMatch) {
      runs.push({ type: 'strong', text: boldMatch[1] })
      rest = rest.slice(boldMatch[0].length)
      continue
    }
    // 斜体 *text*
    const emMatch = /^\*([^*]+)\*/.exec(rest)
    if (emMatch) {
      runs.push({ type: 'em', text: emMatch[1] })
      rest = rest.slice(emMatch[0].length)
      continue
    }
    // 普通字符，推到下一个可能语法标记前
    const nextSpecial = rest.slice(1).search(/[`[*[]/)
    const take = nextSpecial >= 0 ? nextSpecial + 1 : rest.length
    const chunk = rest.slice(0, take)
    const last = runs[runs.length - 1]
    if (last && last.type === 'text') {
      last.text += chunk
    } else {
      runs.push({ type: 'text', text: chunk })
    }
    rest = rest.slice(take)
  }
  return runs
}

export function parseMarkdown(source) {
  const lines = String(source ?? '')
    .replace(/\r\n?/g, '\n')
    .split('\n')
  const blocks = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) {
      i++
      continue
    }

    // 围栏代码块
    const fence = /^```\s*([A-Za-z0-9_-]*)\s*$/.exec(line)
    if (fence) {
      const codeLines = []
      i++
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        codeLines.push(lines[i])
        i++
      }
      if (i < lines.length) i++
      blocks.push({ type: 'code', lang: fence[1] || '', text: codeLines.join('\n') })
      continue
    }

    // 标题
    const heading = /^(#{1,6})\s+(.+?)\s*#*$/.exec(line)
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, runs: parseInline(heading[2]) })
      i++
      continue
    }

    // 分隔线
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      blocks.push({ type: 'hr' })
      i++
      continue
    }

    // 引用块
    if (/^\s*>\s?/.test(line)) {
      const qLines = []
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        qLines.push(lines[i].replace(/^\s*>\s?/, ''))
        i++
      }
      blocks.push({ type: 'blockquote', runs: parseInline(qLines.join(' ')) })
      continue
    }

    // 无序列表
    if (/^\s*[-*+]\s+/.test(line)) {
      const items = []
      while (i < lines.length && /^\s*[-*+]\s+/.test(lines[i])) {
        items.push(parseInline(lines[i].replace(/^\s*[-*+]\s+/, '')))
        i++
      }
      blocks.push({ type: 'ul', items })
      continue
    }

    // 有序列表
    if (/^\s*\d+\.\s+/.test(line)) {
      const items = []
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(parseInline(lines[i].replace(/^\s*\d+\.\s+/, '')))
        i++
      }
      blocks.push({ type: 'ol', items })
      continue
    }

    // 普通段落
    const pLines = []
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^```/.test(lines[i]) &&
      !/^#{1,6}\s+/.test(lines[i]) &&
      !/^\s*>\s?/.test(lines[i]) &&
      !/^\s*[-*+]\s+/.test(lines[i]) &&
      !/^\s*\d+\.\s+/.test(lines[i]) &&
      !/^\s*([-*_])(\s*\1){2,}\s*$/.test(lines[i])
    ) {
      pLines.push(lines[i].trim())
      i++
    }
    blocks.push({ type: 'paragraph', runs: parseInline(pLines.join(' ')) })
  }

  return blocks
}
