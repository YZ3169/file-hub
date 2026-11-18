const THEME_KEY = 'filehub.theme'

// 断点续传记录与哈希缓存必须按用户隔离：换账号登录时绝不能看到、
// 更不能复用上一个用户的上传任务 fileId。
let storageUserId = null

export function setStorageUser(userId) {
  storageUserId = userId ? String(userId) : null
}

function pendingKey() {
  return storageUserId ? `filehub.${storageUserId}.pendingTasks.v3` : null
}

function hashKey() {
  return storageUserId ? `filehub.${storageUserId}.hashCache.v2` : null
}

export function clearUserStorage(userId) {
  if (!userId) return
  try {
    localStorage.removeItem(`filehub.${userId}.pendingTasks.v3`)
    localStorage.removeItem(`filehub.${userId}.hashCache.v2`)
  } catch {
    // ignore
  }
}

function readJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

function writeJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // 隐私模式 / 配额满时静默降级：断点续传仍在服务端，只是少了本地加速信息
  }
}

/**
 * 文件指纹。刷新页面后浏览器拿不到原 File 对象，只能让用户重新选择文件；
 * 用「文件名 + 大小 + 修改时间」把新旧两次选择对上，才能接上原来的上传任务。
 */
export function fingerprintOf(file) {
  return `${file.name}|${file.size}|${file.lastModified}`
}

export function loadPendingTasks() {
  if (!storageUserId) return []
  const list = readJson(pendingKey(), [])
  return Array.isArray(list) ? list : []
}

export function savePendingTask(task) {
  if (!task?.fingerprint || !storageUserId) return
  const list = loadPendingTasks().filter((t) => t.fingerprint !== task.fingerprint)
  if (task.state !== 'done' && task.state !== 'instant' && task.state !== 'canceled') {
    list.unshift({
      fingerprint: task.fingerprint,
      fileName: task.name,
      fileSize: task.size,
      lastModified: task.lastModified,
      hash: task.hash || null,
      fileId: task.fileId || null,
      chunkSize: task.chunkSize || 0,
      chunkTotal: task.chunkTotal || 0,
      uploadedCount: task.uploadedChunks || 0,
      updatedAt: Date.now(),
    })
  }
  writeJson(pendingKey(), list.slice(0, 30))
}

export function removePendingTask(fingerprint) {
  if (!fingerprint || !storageUserId) return
  writeJson(
    pendingKey(),
    loadPendingTasks().filter((t) => t.fingerprint !== fingerprint),
  )
}

export function findPendingTask(fingerprint) {
  return loadPendingTasks().find((t) => t.fingerprint === fingerprint) || null
}

export function getCachedHash(fingerprint) {
  if (!storageUserId) return null
  return readJson(hashKey(), {})[fingerprint] || null
}

export function setCachedHash(fingerprint, hash) {
  if (!storageUserId) return
  const map = readJson(hashKey(), {})
  map[fingerprint] = hash
  const keys = Object.keys(map)
  if (keys.length > 120) {
    // 简单淘汰：删掉最早写入的键（对象保持插入顺序）
    for (const k of keys.slice(0, keys.length - 100)) delete map[k]
  }
  writeJson(hashKey(), map)
}

export function removeCachedHash(fingerprint) {
  if (!storageUserId) return
  const map = readJson(hashKey(), {})
  delete map[fingerprint]
  writeJson(hashKey(), map)
}

export function getTheme() {
  try {
    return localStorage.getItem(THEME_KEY) || 'dark'
  } catch {
    return 'dark'
  }
}

export function setTheme(theme) {
  try {
    localStorage.setItem(THEME_KEY, theme)
  } catch {
    // ignore
  }
}
