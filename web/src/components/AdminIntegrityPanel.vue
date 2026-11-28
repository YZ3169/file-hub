<script setup>
import { computed, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { repairIntegrity, scanIntegrity } from '../lib/api'
import { formatBytes, shortHash } from '../lib/format'
import { refreshFiles, refreshStats, toast } from '../stores/uploader'

const emit = defineEmits(['close'])

const limit = ref(100)
const deep = ref(false)
const scanning = ref(false)
const repairing = ref(false)
const result = ref(null)
const selectedIds = ref([])
const error = ref('')

const isUnverifiable = (item) => item?.verdict === 'UNVERIFIABLE'

const repairableItems = computed(() =>
  (result.value?.items || []).filter((i) => !isUnverifiable(i)),
)

const allSelected = computed(
  () => !!repairableItems.value.length && selectedIds.value.length === repairableItems.value.length,
)

async function runScan(deepScan = false) {
  if (scanning.value) return
  deep.value = deepScan
  scanning.value = true
  error.value = ''
  try {
    result.value = await scanIntegrity({ deep: deepScan, limit: limit.value })
    selectedIds.value = []
  } catch (e) {
    error.value = e.message
  } finally {
    scanning.value = false
  }
}

function toggleAll() {
  if (!result.value) return
  selectedIds.value = allSelected.value ? [] : repairableItems.value.map((i) => i.fileId)
}

async function repair() {
  if (!selectedIds.value.length || repairing.value) return
  const count = selectedIds.value.length
  const ok = window.confirm(
    `确认删除选中的 ${count} 条损坏记录？\n只删除数据库中已确认损坏的逻辑记录；物理文件仍按引用计数清理。`,
  )
  if (!ok) return

  repairing.value = true
  error.value = ''
  try {
    const resp = await repairIntegrity([...selectedIds.value])
    const repaired = new Set(resp.items.filter((i) => i.repaired).map((i) => i.fileId))
    result.value = {
      ...result.value,
      items: result.value.items.filter((i) => !repaired.has(i.fileId)),
      brokenCount: result.value.brokenCount - repaired.size,
    }
    selectedIds.value = []
    if (resp.repaired > 0) {
      toast(`已修复 ${resp.repaired} 条损坏记录`, 'success')
      await Promise.all([refreshFiles(), refreshStats()])
    }
    if (resp.failed > 0) {
      toast(
        `${resp.failed} 条未修复：${resp.items.find((i) => !i.repaired)?.reason || '未知原因'}`,
        'warn',
      )
    }
  } catch (e) {
    error.value = e.message
    toast(`修复失败：${e.message}`, 'error')
  } finally {
    repairing.value = false
  }
}

onMounted(() => runScan(false))
</script>

<template>
  <div class="integrity-backdrop" @click.self="emit('close')">
    <section
      class="integrity-panel"
      role="dialog"
      aria-modal="true"
      aria-labelledby="integrity-title"
      data-testid="integrity-panel"
    >
      <header class="panel-head">
        <div>
          <h2 id="integrity-title">完整性扫描</h2>
          <span>检查已完成文件在磁盘上是否仍然存在、大小一致；深度扫描会复算 SHA-256</span>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭完整性面板" @click="emit('close')">
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <div class="controls">
        <label class="limit">
          检查条数
          <select v-model.number="limit" :disabled="scanning">
            <option :value="100">100</option>
            <option :value="500">500</option>
            <option :value="1000">1000</option>
          </select>
        </label>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="scanning"
          @click="runScan(false)"
        >
          <BaseIcon name="search" :size="14" />
          {{ scanning && !deep ? '扫描中…' : '快速扫描' }}
        </button>
        <button
          class="btn btn-primary btn-sm"
          type="button"
          :disabled="scanning"
          @click="runScan(true)"
        >
          <BaseIcon name="shield" :size="14" />
          {{ scanning && deep ? '复算中…' : '深度扫描' }}
        </button>
      </div>

      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <div v-if="result" class="summary" data-testid="integrity-summary">
        <span>
          已检查 <strong>{{ result.checked }}</strong> 条，发现
          <strong :class="{ bad: result.brokenCount }">{{ result.brokenCount }}</strong> 条问题
          <template v-if="result.unverifiableCount > 0">
            ，另有
            <strong>{{ result.unverifiableCount }}</strong>
            条暂时无法验证（已保留数据，可重新扫描重试）
          </template>
        </span>
        <span v-if="result.deep" class="badge">已复算 SHA-256</span>
        <span v-if="result.truncated" class="hint">还有更多记录未检查，可调大条数再扫</span>
      </div>

      <div v-if="result && !result.items.length" class="all-ok">
        <BaseIcon name="check" :size="18" />
        没有发现损坏文件
      </div>

      <div v-else-if="result" class="table-wrap">
        <table class="integrity-table">
          <thead>
            <tr>
              <th class="check">
                <input
                  type="checkbox"
                  aria-label="全选"
                  :checked="allSelected"
                  :disabled="!repairableItems.length"
                  @change="toggleAll"
                />
              </th>
              <th>文件</th>
              <th>所有者</th>
              <th>大小</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in result.items" :key="item.fileId">
              <td class="check">
                <input
                  v-model="selectedIds"
                  type="checkbox"
                  :value="item.fileId"
                  :disabled="isUnverifiable(item)"
                  :aria-label="`选择 ${item.fileName}`"
                />
              </td>
              <td class="name">
                <span :title="item.fileName">{{ item.fileName }}</span>
                <code>{{ shortHash(item.fileId) }}</code>
              </td>
              <td>{{ item.ownerId ?? '历史数据' }}</td>
              <td class="num">{{ formatBytes(item.fileSize) }}</td>
              <td class="reason" :class="{ unverifiable: isUnverifiable(item) }">
                <span v-if="isUnverifiable(item)" class="verdict-tag">暂时无法验证</span>
                {{ item.reason }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="panel-foot">
        <span v-if="result" class="selected">
          已选 {{ selectedIds.length }} / {{ repairableItems.length }} 条可修复
        </span>
        <button
          class="btn btn-danger btn-sm"
          type="button"
          data-testid="repair-button"
          :disabled="!selectedIds.length || repairing"
          @click="repair"
        >
          <BaseIcon name="trash" :size="14" />
          {{
            repairing
              ? '修复中…'
              : `修复选中${selectedIds.length ? `（${selectedIds.length}）` : ''}`
          }}
        </button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.integrity-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 8vh 18px 18px;
  background: rgba(5, 6, 12, 0.62);
  backdrop-filter: blur(6px);
  overflow-y: auto;
}

.integrity-panel {
  width: min(860px, 100%);
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
  border-color: var(--border-strong);
}

.controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;
}

.limit {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-right: auto;
  color: var(--muted);
  font-size: 12px;
}

.limit select {
  padding: 5px 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  color: var(--text);
  font: inherit;
  font-size: 12px;
}

.summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 14px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--muted);
  font-size: 12.5px;
}

.summary strong {
  color: var(--text);
}

.summary .bad {
  color: var(--bad);
}

.badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--brand-2) 14%, transparent);
  color: var(--brand-2);
  font-size: 11px;
}

.hint {
  color: var(--warn, #d97706);
  font-size: 11.5px;
}

.all-ok {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 26px 16px;
  border: 1px dashed var(--border-strong);
  border-radius: 12px;
  color: var(--ok);
  font-size: 13px;
  justify-content: center;
}

.table-wrap {
  max-height: 46vh;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 12px;
}

.integrity-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

.integrity-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 9px 10px;
  background: var(--panel-2);
  border-bottom: 1px solid var(--border);
  color: var(--muted);
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.integrity-table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}

.integrity-table tr:last-child td {
  border-bottom: 0;
}

.check {
  width: 34px;
  text-align: center;
}

.name {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-width: 280px;
}

.name span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name code {
  color: var(--muted-2);
  font-family: var(--mono);
  font-size: 10.5px;
}

.num {
  white-space: nowrap;
  color: var(--muted);
}

.reason {
  color: var(--bad);
}

.reason.unverifiable {
  color: var(--muted);
}

.verdict-tag {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  border: 1px solid var(--border);
  font-size: 11px;
  color: var(--muted);
}

.error {
  margin: 0;
  padding: 9px 12px;
  border-radius: 9px;
  background: color-mix(in srgb, var(--bad) 12%, transparent);
  color: var(--bad);
  font-size: 12px;
}

.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.selected {
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 640px) {
  .integrity-backdrop {
    padding-top: 3vh;
  }

  .integrity-panel {
    padding: 16px 14px 14px;
  }

  .integrity-table th:nth-child(3),
  .integrity-table td:nth-child(3),
  .integrity-table th:nth-child(4),
  .integrity-table td:nth-child(4) {
    display: none;
  }
}
</style>
