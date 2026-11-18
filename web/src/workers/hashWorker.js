import { Sha256 } from '../lib/sha256'

/**
 * 整文件增量哈希线程。
 *
 * 主线程只负责收发消息，切片、读盘、压缩都留在 Worker 里；
 * 2GB 文件计算哈希时页面仍然能滚动、能点暂停。
 */
let aborted = false

self.onmessage = async (event) => {
  const msg = event.data || {}

  if (msg.type === 'abort') {
    aborted = true
    return
  }
  if (msg.type !== 'hash') return

  aborted = false
  const { file, step = 4 * 1024 * 1024 } = msg
  const hasher = new Sha256()
  const started = performance.now()

  if (!file || file.size === 0) {
    self.postMessage({ type: 'done', hash: hasher.digestHex(), ms: 0 })
    return
  }

  for (let offset = 0; offset < file.size; offset += step) {
    if (aborted) return
    const blob = file.slice(offset, Math.min(offset + step, file.size))
    const buf = await blob.arrayBuffer()
    if (aborted) return
    hasher.update(new Uint8Array(buf))
    self.postMessage({
      type: 'progress',
      value: Math.min(1, (offset + step) / file.size),
    })
  }

  self.postMessage({
    type: 'done',
    hash: hasher.digestHex(),
    ms: performance.now() - started,
  })
}
