const droppedPaths = new WeakMap()

/** 文件夹选择和拖拽使用同一份相对路径，普通文件仍返回空字符串。 */
export function relativePathOf(file) {
  return file.webkitRelativePath || droppedPaths.get(file) || ''
}

async function readEntry(entry, parent = '') {
  const path = parent ? `${parent}/${entry.name}` : entry.name
  try {
    if (entry.isFile) {
      const file = await new Promise((resolve, reject) => entry.file(resolve, reject))
      if (parent) droppedPaths.set(file, path)
      return [file]
    }
    if (entry.isDirectory) {
      const reader = entry.createReader()
      const files = []
      // Chromium 每次最多返回一批条目；必须读到空批次才算遍历完成。
      for (;;) {
        const entries = await new Promise((resolve, reject) => reader.readEntries(resolve, reject))
        if (!entries.length) return files
        for (const child of entries) {
          for (const file of await readEntry(child, path)) files.push(file)
        }
      }
    }
    throw new Error('无法识别此文件类型')
  } catch (error) {
    throw new Error(`无法读取「${path}」：${error.message || '请检查文件访问权限'}`, {
      cause: error,
    })
  }
}

export async function collectDroppedFiles(dataTransfer) {
  // DataTransfer 在 drop 回调结束后可能进入保护模式，先同步取得全部句柄。
  const fallback = Array.from(dataTransfer?.files || [])
  const items = Array.from(dataTransfer?.items || []).filter((item) => item.kind === 'file')
  if (!items.length) return fallback
  const sources = items.map((item, index) => ({
    entry: item.getAsEntry?.() || item.webkitGetAsEntry?.(),
    file: item.getAsFile?.() || fallback[index],
  }))
  const files = []
  for (const { entry, file } of sources) {
    if (entry) {
      for (const file of await readEntry(entry)) files.push(file)
    } else if (file) files.push(file)
    else throw new Error('无法读取拖入的文件，请重新选择文件或文件夹')
  }
  return files
}
