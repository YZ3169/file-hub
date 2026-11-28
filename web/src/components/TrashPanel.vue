<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { deleteFile, emptyTrash, listTrash, restoreFile } from '../lib/api'
import { formatBytes, formatDate } from '../lib/format'
import { refreshActiveView, refreshStats, toast } from '../stores/uploader'

const emit = defineEmits(['close'])

const items = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const closeButton = ref(null)
let disposed = false
let previousFocus = null

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const res = await listTrash({ page: target, size: 20 })
    if (disposed) return
    items.value = res.items
    total.value = res.total
    page.value = res.page
  } catch (e) {
    if (!disposed) error.value = e.message || '回收站加载失败'
  } finally {
    if (!disposed) loading.value = false
  }
}

async function restore(file) {
  if (busy.value) return
  busy.value = true
  try {
    await restoreFile(file.fileId)
    toast(`已还原「${file.relativePath || file.fileName}」`, 'success')
    await Promise.all([load(page.value), refreshActiveView(), refreshStats()])
  } catch (e) {
    toast(`还原失败：${e.message}`, 'error')
  } finally {
    busy.value = false
  }
}

async function destroy(file) {
  if (busy.value) return
  if (
    !window.confirm(
      `彻底删除「${file.relativePath || file.fileName}」？此操作不可恢复，无其他引用时将同步清理物理文件。`,
    )
  ) {
    return
  }
  busy.value = true
  try {
    await deleteFile(file.fileId)
    toast(`已彻底删除「${file.fileName}」`, 'success')
    await Promise.all([load(page.value), refreshStats()])
  } catch (e) {
    toast(`删除失败：${e.message}`, 'error')
  } finally {
    busy.value = false
  }
}

async function clearAll() {
  if (busy.value || !items.value.length) return
  if (!window.confirm(`确认清空回收站中全部 ${total.value} 个文件？此操作不可恢复。`)) return
  busy.value = true
  try {
    const res = await emptyTrash()
    toast(`已清空回收站（共清理 ${res.cleared} 个文件）`, 'success')
    await Promise.all([load(1), refreshStats()])
  } catch (e) {
    toast(`清空失败：${e.message}`, 'error')
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  previousFocus = document.activeElement
  closeButton.value?.focus()
  load(1)
})

onBeforeUnmount(() => {
  disposed = true
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<template>
  <div class="panel-backdrop" @click.self="emit('close')">
    <section
      class="panel-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="trash-title"
      data-testid="trash-panel"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="panel-head">
        <div>
          <h2 id="trash-title"><BaseIcon name="trash" :size="18" /> 回收站</h2>
          <span>共 {{ total }} 项 · 移入后保留 30 天，到期自动清理</span>
        </div>
        <div class="head-right">
          <button
            v-if="total > 0"
            class="btn btn-danger btn-sm"
            type="button"
            :disabled="busy"
            @click="clearAll"
          >
            清空回收站
          </button>
          <button
            ref="closeButton"
            class="icon-btn"
            type="button"
            aria-label="关闭回收站"
            @click="emit('close')"
          >
            <BaseIcon name="x" :size="16" />
          </button>
        </div>
      </header>

      <div v-if="error" class="state error" role="alert">
        <span>{{ error }}</span>
        <button class="btn btn-sm" type="button" @click="load(page)">重试</button>
      </div>
      <div v-else-if="loading" class="state">正在加载回收站…</div>
      <div v-else-if="!items.length" class="state">
        <BaseIcon name="trash" :size="24" />
        <strong>回收站是空的</strong>
        <span>误删的文件会暂时保留在这里，可随时还原</span>
      </div>

      <div v-else class="list-wrap">
        <div v-for="file in items" :key="file.fileId" class="trash-row">
          <div class="info">
            <strong :title="file.relativePath || file.fileName">{{
              file.relativePath || file.fileName
            }}</strong>
            <span>
              {{ formatBytes(file.fileSize) }} · 删除于
              {{ formatDate(file.deletedAt || file.createTime) }}
            </span>
          </div>
          <div class="actions">
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="busy"
              @click="restore(file)"
            >
              <BaseIcon name="rotate-ccw" :size="14" /> 还原
            </button>
            <button
              class="btn btn-danger btn-sm"
              type="button"
              :disabled="busy"
              @click="destroy(file)"
            >
              彻底删除
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.panel-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(5, 8, 18, 0.72);
  backdrop-filter: blur(8px);
}

.panel-dialog {
  display: flex;
  flex-direction: column;
  width: min(720px, 100%);
  max-height: 82vh;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--bg);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.5);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.panel-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 2px;
  font-size: 17px;
}

.panel-head span {
  color: var(--muted);
  font-size: 12px;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 20px;
  color: var(--muted);
}

.state strong {
  color: var(--text);
}

.list-wrap {
  overflow: auto;
  padding: 8px 0;
}

.trash-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
}

.trash-row:last-child {
  border-bottom: 0;
}

.info {
  min-width: 0;
}

.info strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13.5px;
}

.info span {
  font-size: 12px;
  color: var(--muted);
}

.actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
</style>
