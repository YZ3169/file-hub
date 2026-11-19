<script setup>
import { computed, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { cancelShare, listShares, resetShareCode } from '../lib/api'
import { buildShareUrl, copyText } from '../lib/clipboard'
import { toast } from '../stores/uploader'

const emit = defineEmits(['close'])

const SIZE = 20
const STATE_LABEL = {
  ACTIVE: '有效',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
  FILE_MISSING: '文件已删除',
}

const items = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const newCode = ref(null)
const busyToken = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))

async function load(target = page.value) {
  loading.value = true
  error.value = ''
  try {
    const data = await listShares({ page: target, size: SIZE })
    items.value = data.items
    total.value = data.total
    page.value = data.page
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function pretty(ts) {
  return ts ? ts.replace('T', ' ').slice(0, 16) : '永久有效'
}

async function copy(text, label) {
  const copied = await copyText(text)
  toast(copied ? `${label}已复制` : '复制失败，请手动选中文本复制', copied ? 'success' : 'warn')
}

function copyLink(item) {
  copy(buildShareUrl(item.token), '链接')
}

async function regenerate(item) {
  if (busyToken.value) return
  busyToken.value = item.token
  try {
    const resp = await resetShareCode(item.token)
    newCode.value = { token: item.token, code: resp.code, expiresAt: resp.expiresAt }
    toast('已生成新的提取码，旧提取码立即失效', 'success')
    await load()
  } catch (e) {
    toast(`重置失败：${e.message}`, 'error')
  } finally {
    busyToken.value = ''
  }
}

async function cancel(item) {
  if (busyToken.value) return
  if (!window.confirm(`取消分享「${item.fileName}」？链接和提取码会立即失效。`)) return
  busyToken.value = item.token
  try {
    await cancelShare(item.token)
    if (newCode.value?.token === item.token) newCode.value = null
    toast('分享已取消', 'success')
    await load()
  } catch (e) {
    toast(`取消失败：${e.message}`, 'error')
  } finally {
    busyToken.value = ''
  }
}

onMounted(() => load(1))
</script>

<template>
  <div class="shares-backdrop" @click.self="emit('close')">
    <section
      class="shares-panel"
      role="dialog"
      aria-modal="true"
      aria-labelledby="shares-title"
      data-testid="shares-panel"
    >
      <header class="panel-head">
        <div>
          <h2 id="shares-title">我的分享</h2>
          <span>共 {{ total }} 个分享 · 取消或过期后链接立即失效</span>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭我的分享" @click="emit('close')">
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <div v-if="newCode" class="new-code" data-testid="new-share-code">
        <div>
          <strong
            >新提取码：<code>{{ newCode.code }}</code></strong
          >
          <span> 到期：{{ pretty(newCode.expiresAt) }} · 只显示这一次，请立即保存 </span>
        </div>
        <button class="btn btn-ghost btn-sm" type="button" @click="copy(newCode.code, '提取码')">
          复制
        </button>
      </div>

      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <div v-if="!loading && !items.length" class="empty">
        <BaseIcon name="link" :size="22" />
        <strong>还没有分享</strong>
        <span>在文件列表点「分享」，生成链接和 4 位提取码</span>
      </div>

      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>文件</th>
              <th>状态</th>
              <th>到期</th>
              <th class="num">提取次数</th>
              <th class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.token">
              <td>
                <div class="name-cell">
                  <strong :title="item.fileName">{{ item.fileName }}</strong>
                  <span>{{ item.token.slice(0, 10) }}…</span>
                </div>
              </td>
              <td>
                <span class="state" :class="item.state.toLowerCase()">
                  <i></i>
                  {{ STATE_LABEL[item.state] || item.state }}
                  <template v-if="item.locked"> · 锁定中</template>
                </span>
              </td>
              <td class="time">{{ pretty(item.expiresAt) }}</td>
              <td class="num">{{ item.downloadCount }}</td>
              <td class="col-actions">
                <div class="row-actions">
                  <template v-if="item.state === 'ACTIVE'">
                    <button
                      class="icon-link"
                      type="button"
                      title="复制链接"
                      :aria-label="`复制 ${item.fileName} 的分享链接`"
                      @click="copyLink(item)"
                    >
                      <BaseIcon name="link" :size="15" />
                    </button>
                    <button
                      class="icon-link"
                      type="button"
                      title="换一个提取码"
                      :disabled="busyToken === item.token"
                      :aria-label="`重置 ${item.fileName} 的提取码`"
                      @click="regenerate(item)"
                    >
                      <BaseIcon name="refresh" :size="15" />
                    </button>
                    <button
                      class="icon-link danger"
                      type="button"
                      title="取消分享"
                      :disabled="busyToken === item.token"
                      :aria-label="`取消 ${item.fileName} 的分享`"
                      @click="cancel(item)"
                    >
                      <BaseIcon name="trash" :size="15" />
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="panel-foot">
        <button class="btn btn-ghost btn-sm" type="button" :disabled="loading" @click="load(page)">
          <BaseIcon name="refresh" :size="14" />
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <span class="spacer"></span>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="page <= 1 || loading"
          aria-label="上一页"
          @click="load(page - 1)"
        >
          上一页
        </button>
        <span class="page-indicator">{{ page }} / {{ totalPages }}</span>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="page >= totalPages || loading"
          aria-label="下一页"
          @click="load(page + 1)"
        >
          下一页
        </button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.shares-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 8vh 18px 18px;
  background: rgba(5, 6, 12, 0.62);
  backdrop-filter: blur(6px);
  overflow-y: auto;
}

.shares-panel {
  width: min(760px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px 22px 18px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.45);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel-head h2 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 650;
}

.panel-head span {
  color: var(--muted);
  font-size: 12px;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
}

.icon-btn:hover {
  color: var(--text);
}

.new-code {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px dashed color-mix(in srgb, var(--brand-2) 50%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--brand-2) 8%, var(--panel-2));
}

.new-code div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.new-code code {
  font-family: var(--mono);
  font-size: 17px;
  letter-spacing: 0.24em;
}

.new-code span {
  color: var(--muted);
}

.error {
  margin: 0;
  padding: 9px 12px;
  border: 1px solid color-mix(in srgb, var(--bad) 40%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, var(--bad) 10%, var(--panel));
  color: var(--bad);
  font-size: 12px;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 38px 18px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-md);
  background: var(--panel-2);
  color: var(--muted);
  text-align: center;
}

.empty strong {
  color: var(--text);
  font-size: 13.5px;
}

.empty span {
  font-size: 12px;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

th {
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  color: var(--muted);
  font-size: 11px;
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
}

td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}

.name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-width: 300px;
}

.name-cell strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-cell span {
  color: var(--muted-2);
  font-family: var(--mono);
  font-size: 10.5px;
}

.state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--muted);
  white-space: nowrap;
}

.state i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.state.active {
  color: var(--ok);
}

.state.expired,
.state.file_missing {
  color: var(--warn);
}

.state.cancelled {
  color: var(--muted-2);
}

.time {
  color: var(--muted);
  white-space: nowrap;
}

.num {
  text-align: right;
}

.col-actions {
  width: 120px;
  text-align: right;
}

.row-actions {
  display: inline-flex;
  gap: 2px;
}

.icon-link {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.icon-link:hover:not(:disabled) {
  background: var(--hover);
  color: var(--text);
}

.icon-link.danger:hover:not(:disabled) {
  color: var(--bad);
}

.icon-link:disabled {
  opacity: 0.45;
  cursor: default;
}

.panel-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}

.page-indicator {
  min-width: 52px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
}
</style>
