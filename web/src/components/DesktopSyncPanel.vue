<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import {
  addDesktopSync,
  deleteDesktopSync,
  listDesktopSync,
  pickDesktopSyncFolder,
  runDesktopSync,
  toggleDesktopSync,
} from '../lib/api'
import { refreshActiveView, refreshStats, toast } from '../stores/uploader'

const emit = defineEmits(['close'])

const rules = ref([])
const loading = ref(true)
const busy = ref(false)
const picking = ref(false)
const localDir = ref('')
const grantId = ref('')
const targetDir = ref('desktop-sync')
const error = ref('')
const hasNativePicker = Boolean(window.chrome?.webview?.postMessage)

function onWebViewMessage(event) {
  const msg = event?.data
  if (!msg || msg.type !== 'pickedFolder') return
  if (msg.error) {
    error.value = msg.error
    return
  }
  const res = msg.result
  if (res && res.code === 0 && res.data?.grantId) {
    grantId.value = res.data.grantId
    localDir.value = res.data.localDir || ''
    error.value = ''
    return
  }
  if (res?.msg) {
    error.value = res.msg
  }
}

async function pickLocalFolder() {
  if (busy.value || picking.value) return
  if (hasNativePicker) {
    window.chrome?.webview?.postMessage({ type: 'pickFolder' })
    return
  }
  picking.value = true
  error.value = ''
  try {
    const picked = await pickDesktopSyncFolder()
    if (picked?.grantId) {
      grantId.value = picked.grantId
      localDir.value = picked.localDir || ''
    }
  } catch (e) {
    error.value = e.message || '打开系统目录选择器失败'
  } finally {
    picking.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    rules.value = await listDesktopSync()
  } catch (e) {
    error.value = e.message || '加载同步规则失败'
  } finally {
    loading.value = false
  }
}

async function addRule() {
  if (!grantId.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await addDesktopSync(grantId.value, targetDir.value.trim())
    grantId.value = ''
    localDir.value = ''
    toast('已添加本地目录同步规则并开始首次扫描', 'success')
    await load()
  } catch (e) {
    error.value = e.message || '添加失败'
  } finally {
    busy.value = false
  }
}

async function toggle(rule) {
  try {
    await toggleDesktopSync(rule.id, !rule.enabled)
    await load()
  } catch (e) {
    toast(`切换失败：${e.message}`, 'error')
  }
}

async function syncNow(rule) {
  busy.value = true
  try {
    await runDesktopSync(rule.id)
    toast('同步扫描完成', 'success')
    await Promise.all([load(), refreshActiveView(), refreshStats()])
  } catch (e) {
    toast(`同步失败：${e.message}`, 'error')
  } finally {
    busy.value = false
  }
}

async function remove(rule) {
  if (!window.confirm(`移除同步规则「${rule.localDir}」？已同步的云端文件不会被删除。`)) return
  try {
    await deleteDesktopSync(rule.id)
    await load()
  } catch (e) {
    toast(`移除失败：${e.message}`, 'error')
  }
}

onMounted(() => {
  window.chrome?.webview?.addEventListener('message', onWebViewMessage)
  load()
})

onBeforeUnmount(() => {
  window.chrome?.webview?.removeEventListener('message', onWebViewMessage)
})
</script>

<template>
  <div class="panel-backdrop" @click.self="emit('close')">
    <section
      class="panel-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="sync-title"
      data-testid="desktop-sync-panel"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="panel-head">
        <div>
          <h2 id="sync-title"><BaseIcon name="refresh" :size="18" /> 本地文件夹自动备份同步</h2>
          <span>实时监听本地文件夹变更，内容有变化时自动通过 SHA-256 秒传备份到 FileHub</span>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭同步管理" @click="emit('close')">
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <div class="panel-body">
        <form class="add-form" @submit.prevent="addRule">
          <div class="row">
            <input
              v-model="localDir"
              type="text"
              readonly
              placeholder="请点击右侧按钮，在系统窗口中选择并授权本地目录"
              @click="pickLocalFolder"
            />
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="busy || picking"
              @click="pickLocalFolder"
            >
              {{ picking ? '选择中…' : '选择目录…' }}
            </button>
          </div>
          <div class="row">
            <input
              v-model="targetDir"
              type="text"
              placeholder="远端目标目录（例如 backups/notes）"
            />
            <button
              class="btn btn-primary btn-sm"
              type="submit"
              :disabled="busy || picking || !grantId"
            >
              添加同步
            </button>
          </div>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
        </form>

        <div v-if="loading" class="empty">正在加载同步规则…</div>
        <div v-else-if="!rules.length" class="empty">还没有配置同步目录</div>
        <div v-else class="rule-list">
          <div v-for="rule in rules" :key="rule.id" class="rule-item">
            <div class="rule-meta">
              <strong>{{ rule.localDir }} → /{{ rule.targetDir }}</strong>
              <span>
                {{ rule.enabled ? '监听中' : '已暂停' }} · 累计同步 {{ rule.syncedFiles }} 个文件
                <template v-if="rule.lastSyncAt">
                  · 最近：{{ rule.lastSyncAt.slice(0, 19).replace('T', ' ') }}</template
                >
              </span>
              <span v-if="rule.lastError" class="err-note">{{ rule.lastError }}</span>
            </div>
            <div class="rule-actions">
              <button
                class="btn btn-ghost btn-sm"
                type="button"
                :disabled="busy"
                @click="syncNow(rule)"
              >
                立即同步
              </button>
              <button class="btn btn-ghost btn-sm" type="button" @click="toggle(rule)">
                {{ rule.enabled ? '暂停' : '启用' }}
              </button>
              <button class="btn btn-danger btn-sm" type="button" @click="remove(rule)">
                移除
              </button>
            </div>
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
  width: min(740px, 100%);
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
  padding: 15px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.panel-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 2px;
  font-size: 16px;
}

.panel-head span {
  font-size: 12px;
  color: var(--muted);
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
}

.panel-body {
  padding: 18px;
  overflow: auto;
  display: grid;
  gap: 16px;
}

.add-form {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.row {
  display: flex;
  gap: 8px;
}

.row input {
  flex: 1;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  font-size: 13px;
}

.error {
  margin: 0;
  color: var(--bad);
  font-size: 12.5px;
}

.empty {
  padding: 28px;
  text-align: center;
  color: var(--muted);
}

.rule-list {
  display: grid;
  gap: 10px;
}

.rule-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.rule-meta {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.rule-meta strong {
  font-size: 13px;
  word-break: break-all;
}

.rule-meta span {
  font-size: 12px;
  color: var(--muted);
}

.err-note {
  color: var(--bad) !important;
}

.rule-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
</style>
