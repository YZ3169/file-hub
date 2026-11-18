import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { Sha256, sha256Hex } from './sha256'

function nodeDigest(bytes) {
  return createHash('sha256').update(bytes).digest('hex')
}

function bytesOf(length) {
  const out = new Uint8Array(length)
  for (let i = 0; i < length; i++) out[i] = (i * 31 + 7) & 0xff
  return out
}

describe('Sha256', () => {
  it.each([0, 1, 55, 56, 57, 63, 64, 65, 127, 128, 1000])(
    '与 Node crypto 对 %i 字节结果一致',
    (length) => {
      const bytes = bytesOf(length)
      expect(sha256Hex(bytes)).toBe(nodeDigest(bytes))
    },
  )

  it('分块喂入与一次性喂入结果一致', () => {
    const bytes = bytesOf(4097)
    const hasher = new Sha256()
    for (let offset = 0; offset < bytes.length; offset += 333) {
      hasher.update(bytes.subarray(offset, Math.min(offset + 333, bytes.length)))
    }
    expect(hasher.digestHex()).toBe(nodeDigest(bytes))
  })

  it('在缓冲区未满时多次喂入也能正确收尾', () => {
    const bytes = bytesOf(200)
    const hasher = new Sha256()
    hasher.update(bytes.subarray(0, 17))
    hasher.update(bytes.subarray(17, 70))
    hasher.update(bytes.subarray(70))
    expect(hasher.digestHex()).toBe(nodeDigest(bytes))
  })

  it('digestHex 可重复调用，不影响后续 update', () => {
    const first = bytesOf(70)
    const second = bytesOf(90)

    const hasher = new Sha256()
    hasher.update(first)
    const a = hasher.digestHex()
    expect(hasher.digestHex()).toBe(a)

    hasher.update(second)
    const combined = new Uint8Array(first.length + second.length)
    combined.set(first)
    combined.set(second, first.length)
    expect(hasher.digestHex()).toBe(nodeDigest(combined))
  })
})
