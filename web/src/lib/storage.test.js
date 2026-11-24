import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearUserStorage,
  findPendingTask,
  fingerprintOf,
  getCachedHash,
  getTheme,
  loadPendingTasks,
  removeCachedHash,
  removePendingTask,
  savePendingTask,
  setCachedHash,
  setStorageUser,
  setTheme,
} from './storage'

function pendingTask(overrides = {}) {
  return {
    fingerprint: 'demo.bin|1024|1700000000000',
    name: 'demo.bin',
    size: 1024,
    lastModified: 1700000000000,
    hash: 'abc',
    fileId: 'f-1',
    chunkSize: 256,
    chunkTotal: 4,
    uploadedChunks: 2,
    state: 'uploading',
    ...overrides,
  }
}

describe('storage 按用户隔离', () => {
  beforeEach(() => {
    setStorageUser(null)
  })

  it('未设置用户时不读写任何记录', () => {
    savePendingTask(pendingTask())
    expect(loadPendingTasks()).toEqual([])
    expect(getCachedHash('x')).toBeNull()
  })

  it('不同用户的 pending 记录互不可见', () => {
    setStorageUser(1)
    savePendingTask(pendingTask())
    expect(loadPendingTasks()).toHaveLength(1)
    expect(findPendingTask('demo.bin|1024|1700000000000')?.fileId).toBe('f-1')

    setStorageUser(2)
    expect(loadPendingTasks()).toEqual([])

    setStorageUser(1)
    expect(loadPendingTasks()).toHaveLength(1)
  })

  it('同名同大小同修改时间的文件按目录隔离断点，普通文件保留旧指纹', () => {
    setStorageUser(1)
    const file = { name: 'report.txt', size: 4, lastModified: 1234 }
    expect(fingerprintOf(file)).toBe('report.txt|4|1234')
    const paths = ['资料/甲/report.txt', '资料/乙/report.txt']
    for (const relativePath of paths) {
      const fingerprint = fingerprintOf({ ...file, webkitRelativePath: relativePath })
      savePendingTask(pendingTask({ fingerprint, relativePath, name: file.name }))
    }
    expect(loadPendingTasks()).toHaveLength(2)
    for (const relativePath of paths) {
      expect(findPendingTask(`${relativePath}|4|1234`).relativePath).toBe(relativePath)
    }
    removePendingTask(`${paths[0]}|4|1234`)
    expect(loadPendingTasks().map((task) => task.relativePath)).toEqual([paths[1]])
  })

  it('完成任务不落盘，已有记录会被覆盖', () => {
    setStorageUser(7)
    savePendingTask(pendingTask())
    savePendingTask(pendingTask({ uploadedChunks: 3, state: 'uploading' }))
    expect(loadPendingTasks()).toHaveLength(1)
    expect(loadPendingTasks()[0].uploadedCount).toBe(3)

    savePendingTask(pendingTask({ state: 'done' }))
    expect(loadPendingTasks()).toEqual([])
  })

  it('removePendingTask 只删指定指纹', () => {
    setStorageUser(1)
    savePendingTask(pendingTask())
    savePendingTask(pendingTask({ fingerprint: 'b.bin|2|3', name: 'b.bin' }))
    removePendingTask('demo.bin|1024|1700000000000')
    expect(loadPendingTasks()).toHaveLength(1)
    expect(loadPendingTasks()[0].fileName).toBe('b.bin')
  })

  it('clearUserStorage 清理该用户的断点与哈希缓存', () => {
    setStorageUser(9)
    savePendingTask(pendingTask())
    setCachedHash('fp', 'hash')
    clearUserStorage(9)
    expect(localStorage.getItem('filehub.9.pendingTasks.v3')).toBeNull()
    expect(localStorage.getItem('filehub.9.hashCache.v2')).toBeNull()
  })
})

describe('hash cache', () => {
  beforeEach(() => {
    setStorageUser(1)
  })

  it('写入、读取、删除', () => {
    setCachedHash('a', 'hash-a')
    expect(getCachedHash('a')).toBe('hash-a')
    removeCachedHash('a')
    expect(getCachedHash('a')).toBeNull()
  })

  it('超过 120 条时淘汰最早写入的键', () => {
    for (let i = 0; i < 130; i++) setCachedHash(`fp-${i}`, `hash-${i}`)
    const raw = JSON.parse(localStorage.getItem('filehub.1.hashCache.v2'))
    const keys = Object.keys(raw)
    // 超过 120 时一次性裁到 100，之后继续写入会回到 100–120 区间
    expect(keys.length).toBeLessThanOrEqual(120)
    expect(keys.length).toBeGreaterThan(100)
    expect(raw['fp-0']).toBeUndefined()
    expect(raw['fp-129']).toBe('hash-129')
  })
})

describe('theme', () => {
  it('默认深色，可切换并持久化', () => {
    expect(getTheme()).toBe('dark')
    setTheme('light')
    expect(getTheme()).toBe('light')
  })
})
