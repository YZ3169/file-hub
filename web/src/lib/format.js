export function formatBytes(n) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—'
  const v = Number(n)
  if (v < 1024) return `${v} B`
  if (v < 1024 ** 2) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 ** 3) return `${(v / 1024 ** 2).toFixed(1)} MB`
  if (v < 1024 ** 4) return `${(v / 1024 ** 3).toFixed(2)} GB`
  return `${(v / 1024 ** 4).toFixed(2)} TB`
}

export function formatSpeed(bps) {
  if (!bps || bps <= 0) return '—'
  return `${formatBytes(bps)}/s`
}

export function formatDuration(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return '—'
  const s = Math.round(seconds)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  if (m < 60) return `${m} 分 ${String(rest).padStart(2, '0')} 秒`
  const h = Math.floor(m / 60)
  return `${h} 小时 ${String(m % 60).padStart(2, '0')} 分`
}

export function formatDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(d)
}

export function percent(part, total) {
  if (!total) return 0
  return Math.max(0, Math.min(100, (part / total) * 100))
}

export function shortHash(hash) {
  if (!hash) return ''
  return `${hash.slice(0, 10)}…${hash.slice(-6)}`
}
