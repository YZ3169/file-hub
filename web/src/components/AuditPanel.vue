<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { listAuditLogs } from '../lib/api'
import { auth } from '../lib/auth'
import { formatDate } from '../lib/format'

const emit = defineEmits(['close'])

const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const items = ref([])
const total = ref(0)
const page = ref(1)
const action = ref('')
const scopeAll = ref(false)
const loading = ref(true)
const error = ref('')
const closeButton = ref(null)
let disposed = false
let previousFocus = null

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const res = await listAuditLogs({
      page: target,
      size: 20,
      action: action.value.trim(),
      scope: isAdmin.value && scopeAll.value ? 'all' : '',
    })
    if (disposed) return
    items.value = res.items
    total.value = res.total
    page.value = res.page
  } catch (e) {
    if (!disposed) error.value = e.message || '加载日志失败'
  } finally {
    if (!disposed) loading.value = false
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
      aria-labelledby="audit-title"
      data-testid="audit-panel"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="panel-head">
        <div>
          <h2 id="audit-title"><BaseIcon name="activity" :size="18" /> 操作审计日志</h2>
          <span>共 {{ total }} 条记录</span>
        </div>
        <div class="controls">
          <select v-model="action" aria-label="动作筛选" @change="load(1)">
            <option value="">全部动作</option>
            <option value="LOGIN">登录</option>
            <option value="UPLOAD_DONE">上传完成</option>
            <option value="TRASH">移入回收站</option>
            <option value="RESTORE">回收站还原</option>
            <option value="DELETE">彻底删除</option>
            <option value="SHARE_CREATE">创建分享</option>
            <option value="SHARE_SAVE">分享转存</option>
            <option value="ZIP_DOWNLOAD">打包下载</option>
            <option value="WEBDAV_PUT">WebDAV 写入</option>
            <option value="PASSWORD_CHANGE">修改密码</option>
          </select>
          <label v-if="isAdmin" class="scope-check">
            <input v-model="scopeAll" type="checkbox" @change="load(1)" />
            <span>全站用户</span>
          </label>
          <button
            ref="closeButton"
            class="icon-btn"
            type="button"
            aria-label="关闭审计日志"
            @click="emit('close')"
          >
            <BaseIcon name="x" :size="16" />
          </button>
        </div>
      </header>

      <div v-if="error" class="state error" role="alert">{{ error }}</div>
      <div v-else-if="loading" class="state">正在加载审计记录…</div>
      <div v-else-if="!items.length" class="state">暂无操作记录</div>

      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>用户</th>
              <th>动作</th>
              <th>目标 / 详情</th>
              <th>IP</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in items" :key="log.id">
              <td class="mono">{{ formatDate(log.createdAt) }}</td>
              <td>{{ log.username || '—' }}</td>
              <td>
                <span class="action-pill">{{ log.action }}</span>
              </td>
              <td class="detail" :title="log.detail || log.targetId">
                {{ log.detail || log.targetId || '—' }}
              </td>
              <td class="mono">{{ log.clientIp || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer v-if="total > 20" class="pager">
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="page <= 1 || loading"
          @click="load(page - 1)"
        >
          上一页
        </button>
        <span>第 {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }} 页</span>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="page * 20 >= total || loading"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </footer>
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
  width: min(860px, 100%);
  max-height: 84vh;
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
  gap: 12px;
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

.controls {
  display: flex;
  align-items: center;
  gap: 10px;
}

.controls select {
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  font-size: 12.5px;
}

.scope-check {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
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
  flex: 1;
  overflow: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

th,
td {
  padding: 10px 14px;
  text-align: left;
  border-bottom: 1px solid var(--border);
}

th {
  color: var(--muted);
  font-weight: 600;
  background: var(--panel);
}

.mono {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--muted);
  white-space: nowrap;
}

.action-pill {
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--panel-2);
  border: 1px solid var(--border);
  font-family: var(--mono);
  font-size: 11px;
}

.detail {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 10px 18px;
  border-top: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--muted);
}
</style>
