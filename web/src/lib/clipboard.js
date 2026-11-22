/**
 * 复制文本。
 *
 * 浏览器只在 HTTPS 或 localhost 下提供 navigator.clipboard；Docker/局域网用
 * http://192.168.x.x:8081 访问时它是 undefined，必须回退到隐藏 textarea +
 * execCommand（虽然已标为弃用，但仍是这条路径上唯一可用的办法）。
 */
export async function copyText(text) {
  if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒或页面失焦，继续走回退
    }
  }

  if (typeof document === 'undefined') return false

  const previousFocus = document.activeElement
  let container = previousFocus?.closest('dialog[open]')
  if (!container) {
    try {
      container = document.querySelector('dialog:modal')
    } catch {
      // 较旧浏览器支持 dialog，但未必支持 :modal 选择器。
      container = Array.from(document.querySelectorAll('dialog[open]')).pop()
    }
  }

  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  // showModal() 会使弹窗外的节点 inert：放到 body 可能返回复制成功却未改剪贴板。
  const fallbackRoot = container || document.body
  fallbackRoot.appendChild(area)
  try {
    area.focus({ preventScroll: true })
    area.select()
    area.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area.remove()
    if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
  }
}

/** 用当前页面的 origin 拼分享链接，不写死端口/主机（反向代理时同样正确） */
export function buildShareUrl(token) {
  const { origin, pathname } = window.location
  return `${origin}${pathname}#/s/${token}`
}
