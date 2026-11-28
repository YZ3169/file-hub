<script setup>
import { computed } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { state } from '../stores/uploader'
import { formatBytes } from '../lib/format'

const cards = computed(() => [
  {
    icon: 'file',
    label: '已完成文件',
    value: String(state.stats.doneFiles ?? 0),
    hint: state.stats.uploadingFiles ? `${state.stats.uploadingFiles} 个任务上传中` : '逻辑文件数',
    tone: 'brand',
  },
  {
    icon: 'archive',
    label: '逻辑总量',
    value: formatBytes(state.stats.logicalBytes),
    hint: '所有文件大小之和',
    tone: 'cyan',
  },
  {
    icon: 'server',
    label: '实际占用',
    value: formatBytes(state.stats.physicalBytes),
    hint: '按内容去重后的磁盘占用',
    tone: 'violet',
  },
  {
    icon: 'spark',
    label: '去重节省',
    value: formatBytes(state.stats.savedBytes),
    hint: '秒传与合并去重省下的空间',
    tone: 'green',
  },
])

const quotaInfo = computed(() => {
  const limit = Number(state.stats.quotaBytes)
  if (!Number.isFinite(limit) || limit < 0) return null
  const used = Math.max(0, Number(state.stats.quotaUsedBytes ?? state.stats.logicalBytes ?? 0))
  const percent = limit === 0 ? 100 : Math.min(100, Math.round((used / limit) * 100))
  const tone = percent >= 95 ? 'bad' : percent >= 80 ? 'warn' : 'ok'
  return { used, limit, percent, tone }
})
</script>

<template>
  <section class="stats-wrap">
    <div class="stats">
      <article
        v-for="card in cards"
        :key="card.label"
        class="stat-card"
        :class="`tone-${card.tone}`"
      >
        <div class="stat-icon">
          <BaseIcon :name="card.icon" :size="18" />
        </div>
        <div class="stat-body">
          <span class="stat-label">{{ card.label }}</span>
          <strong class="stat-value">{{ card.value }}</strong>
          <span class="stat-hint">{{ card.hint }}</span>
        </div>
      </article>
    </div>
    <div v-if="quotaInfo" class="quota-bar" data-testid="quota-bar">
      <div class="quota-head">
        <span>
          <BaseIcon name="hard-drive" :size="14" />
          存储配额：已用 {{ formatBytes(quotaInfo.used) }} / {{ formatBytes(quotaInfo.limit) }}
        </span>
        <strong :class="`quota-${quotaInfo.tone}`">{{ quotaInfo.percent }}%</strong>
      </div>
      <div
        class="quota-track"
        role="progressbar"
        :aria-valuenow="quotaInfo.percent"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <div
          class="quota-fill"
          :class="`quota-${quotaInfo.tone}`"
          :style="{ width: `${quotaInfo.percent}%` }"
        ></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.stat-card {
  display: flex;
  gap: 13px;
  align-items: flex-start;
  padding: 16px 16px 15px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(var(--blur));
  min-width: 0;
}

.stat-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 12px;
  background: var(--panel-2);
  border: 1px solid var(--border);
}

.tone-brand .stat-icon {
  color: var(--brand-light);
}
.tone-cyan .stat-icon {
  color: var(--brand-2);
}
.tone-violet .stat-icon {
  color: var(--violet);
}
.tone-green .stat-icon {
  color: var(--ok);
}

.stat-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stat-label {
  color: var(--muted);
  font-size: 12px;
  letter-spacing: 0.02em;
}

.stat-value {
  margin: 3px 0 2px;
  font-size: clamp(18px, 1.8vw, 23px);
  font-weight: 680;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stat-hint {
  color: var(--muted-2);
  font-size: 11.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (max-width: 980px) {
  .stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .stats {
    grid-template-columns: 1fr;
  }
}

.stats-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.quota-bar {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(var(--blur));
}

.quota-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
  color: var(--muted);
  margin-bottom: 8px;
}

.quota-head span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.quota-head strong {
  font-variant-numeric: tabular-nums;
}

.quota-track {
  height: 7px;
  border-radius: 999px;
  background: var(--panel-2);
  overflow: hidden;
}

.quota-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.25s ease;
}

.quota-ok {
  color: var(--ok);
}
.quota-fill.quota-ok {
  background: linear-gradient(90deg, var(--brand), var(--ok));
}
.quota-warn {
  color: var(--warn);
}
.quota-fill.quota-warn {
  background: var(--warn);
}
.quota-bad {
  color: var(--bad);
}
.quota-fill.quota-bad {
  background: var(--bad);
}
</style>
