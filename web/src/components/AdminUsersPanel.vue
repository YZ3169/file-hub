<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { listAdminUsers, updateUserQuota, updateUserStatus } from '../lib/api'
import { auth } from '../lib/auth'
import { formatBytes } from '../lib/format'
import { refreshStats, toast } from '../stores/uploader'

const emit = defineEmits(['close'])

const users = ref([])
const loading = ref(true)
const error = ref('')
const busyId = ref(null)
const quotaDraft = ref({})
const closeButton = ref(null)
let disposed = false
let previousFocus = null

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await listAdminUsers({ page: 1, size: 50 })
    if (disposed) return
    users.value = res.items
    const drafts = {}
    for (const u of res.items) {
      drafts[u.id] =
        u.storageQuotaBytes < 0 ? '-1' : String(Math.round(u.storageQuotaBytes / (1024 * 1024)))
    }
    quotaDraft.value = drafts
  } catch (e) {
    if (!disposed) error.value = e.message || '加载用户列表失败'
  } finally {
    if (!disposed) loading.value = false
  }
}

async function saveQuota(u) {
  const raw = Number(quotaDraft.value[u.id])
  if (!Number.isFinite(raw) || raw < -1) {
    toast('请输入 -1（不限）或非负整数（MB）', 'warn')
    return
  }
  const bytes = raw < 0 ? -1 : Math.round(raw * 1024 * 1024)
  busyId.value = u.id
  try {
    await updateUserQuota(u.id, bytes)
    toast(`已更新 ${u.username} 的配额`, 'success')
    await Promise.all([load(), refreshStats()])
  } catch (e) {
    toast(`更新失败：${e.message}`, 'error')
  } finally {
    busyId.value = null
  }
}

async function toggleEnabled(u) {
  busyId.value = u.id
  try {
    await updateUserStatus(u.id, !u.enabled)
    toast(u.enabled ? `已禁用 ${u.username}` : `已启用 ${u.username}`, 'success')
    await load()
  } catch (e) {
    toast(`操作失败：${e.message}`, 'error')
  } finally {
    busyId.value = null
  }
}

onMounted(() => {
  previousFocus = document.activeElement
  closeButton.value?.focus()
  load()
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
      aria-labelledby="admin-users-title"
      data-testid="admin-users-panel"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="panel-head">
        <div>
          <h2 id="admin-users-title"><BaseIcon name="users" :size="18" /> 用户与配额管理</h2>
          <span>配额填 -1 表示不限制，填正数表示 MB（例如 10240 = 10 GB）</span>
        </div>
        <button
          ref="closeButton"
          class="icon-btn"
          type="button"
          aria-label="关闭用户管理"
          @click="emit('close')"
        >
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <div v-if="error" class="state error" role="alert">{{ error }}</div>
      <div v-else-if="loading" class="state">正在加载用户列表…</div>

      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>用户名</th>
              <th>角色</th>
              <th>已用空间</th>
              <th>配额 (MB, -1=不限)</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>
                <strong>{{ u.username }}</strong>
              </td>
              <td>{{ u.role }}</td>
              <td>{{ formatBytes(u.usedBytes) }}</td>
              <td>
                <div class="quota-edit">
                  <input
                    v-model="quotaDraft[u.id]"
                    type="number"
                    min="-1"
                    :aria-label="`${u.username} 配额`"
                  />
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    :disabled="busyId === u.id"
                    @click="saveQuota(u)"
                  >
                    保存
                  </button>
                </div>
              </td>
              <td>
                <button
                  class="btn btn-sm"
                  :class="u.enabled ? 'btn-ghost' : 'btn-danger'"
                  type="button"
                  :disabled="busyId === u.id || u.id === auth.user?.id"
                  @click="toggleEnabled(u)"
                >
                  {{ u.enabled ? '正常' : '已禁用' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
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
  width: min(820px, 100%);
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

.state {
  padding: 40px 20px;
  text-align: center;
  color: var(--muted);
}

.table-wrap {
  overflow: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  padding: 10px 14px;
  text-align: left;
  border-bottom: 1px solid var(--border);
}

th {
  color: var(--muted);
  background: var(--panel);
}

.quota-edit {
  display: flex;
  gap: 6px;
  align-items: center;
}

.quota-edit input {
  width: 110px;
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--panel-2);
}
</style>
