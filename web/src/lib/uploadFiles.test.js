import { describe, expect, it, vi } from 'vitest'
import { collectDroppedFiles, relativePathOf } from './uploadFiles'

function fileEntry(name, file = new File(['content'], name)) {
  return { name, isFile: true, file: (resolve) => resolve(file) }
}

function directory(name, batches) {
  const readEntries = vi.fn((resolve) => resolve(batches.shift() || []))
  return { name, isDirectory: true, createReader: () => ({ readEntries }), readEntries }
}

function transfer(...entries) {
  return {
    items: entries.map((entry) => ({ kind: 'file', webkitGetAsEntry: () => entry })),
  }
}

describe('collectDroppedFiles', () => {
  it('递归读取每个目录的所有批次，保留根目录和中文子目录路径', async () => {
    const rootFiles = Array.from({ length: 100 }, (_, i) => fileEntry(`${i}.txt`))
    const nested = directory('子目录', [[fileEntry('report.txt')], [fileEntry('second.txt')]])
    const root = directory('资料', [rootFiles, [nested, fileEntry('last.txt')]])
    const files = await collectDroppedFiles(transfer(root))
    expect(files).toHaveLength(103)
    expect(files.map(relativePathOf)).toContain('资料/子目录/report.txt')
    expect(files.map(relativePathOf)).toContain('资料/子目录/second.txt')
    expect(relativePathOf(files.at(-1))).toBe('资料/last.txt')
    expect(root.readEntries).toHaveBeenCalledTimes(3)
    expect(nested.readEntries).toHaveBeenCalledTimes(3)
  })

  it('混合文件和文件夹时保留所有文件，不给普通文件添加目录路径', async () => {
    const plain = new File(['standalone'], 'plain.txt')
    const folder = directory('folder', [[fileEntry('plain.txt')]])
    const files = await collectDroppedFiles({
      items: [
        { kind: 'file', getAsFile: () => plain },
        { kind: 'string', getAsString: () => 'ignored' },
        { kind: 'file', webkitGetAsEntry: () => folder },
      ],
    })
    expect(files).toHaveLength(2)
    expect(files[0]).toBe(plain)
    expect(relativePathOf(files[0])).toBe('')
    expect(relativePathOf(files[1])).toBe('folder/plain.txt')
  })

  it('在第一个异步读取前取得所有拖入句柄', async () => {
    let finishFirst
    const first = { name: 'first.txt', isFile: true, file: (resolve) => (finishFirst = resolve) }
    const secondGetter = vi.fn(() => fileEntry('second.txt'))
    const result = collectDroppedFiles({
      items: [
        { kind: 'file', webkitGetAsEntry: () => first },
        { kind: 'file', webkitGetAsEntry: secondGetter },
      ],
    })
    expect(secondGetter).toHaveBeenCalledOnce()
    finishFirst(new File(['first'], 'first.txt'))
    expect(await result).toHaveLength(2)
  })

  it('没有 entries API 时兼容普通 FileList，选择文件夹使用浏览器相对路径', async () => {
    const file = new File(['x'], 'test.txt')
    Object.defineProperty(file, 'webkitRelativePath', { value: 'root/test.txt' })
    expect(await collectDroppedFiles({ files: [file] })).toEqual([file])
    expect(relativePathOf(file)).toBe('root/test.txt')
  })

  it('空目录返回空集合，零字节文件仍上传', async () => {
    expect(await collectDroppedFiles(transfer(directory('empty', [])))).toEqual([])
    const emptyFile = new File([], 'empty.txt')
    expect(await collectDroppedFiles(transfer(fileEntry('empty.txt', emptyFile)))).toEqual([
      emptyFile,
    ])
  })

  it('子目录读取失败时拒绝整个批次，并指明出错路径', async () => {
    const unreadable = {
      name: 'locked',
      isDirectory: true,
      createReader: () => ({
        readEntries: (_, reject) => reject(new Error('permission denied')),
      }),
    }
    await expect(
      collectDroppedFiles(transfer(directory('root', [[fileEntry('ok.txt'), unreadable]]))),
    ).rejects.toThrow('root/locked')
  })

  it('无法读取文件句柄时不会静默跳过', async () => {
    await expect(collectDroppedFiles({ items: [{ kind: 'file' }] })).rejects.toThrow(
      '无法读取拖入的文件',
    )
  })
})
