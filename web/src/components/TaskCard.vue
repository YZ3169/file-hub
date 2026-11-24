<script setup>
import { computed } from 'vue'
import BaseIcon from './BaseIcon.vue'
import ChunkGrid from './ChunkGrid.vue'
import { cancelTask, downloadFile, pauseTask, removeTask, resumeTask } from '../stores/uploader'
import { formatBytes, formatDuration, formatSpeed, percent, shortHash } from '../lib/format'

const props = defineProps({
  task: { type: Object, required: true },
})

const ACTIVE = new Set(['queued', 'hashing', 'checking', 'uploading', 'merging'])

const LABELS = {
  queued: '排队中',
  hashing: '计算哈希',
  checking: '校验秒传',
  uploading: '上传中',
  merging: '合并校验',
  paused: '已暂停',
  done: '已完成',
  instant: '秒传命中',
  error: '失败',
  canceled: '已取消',
}

const TONES = {
  queued: 'muted',
  hashing: 'violet',
  checking: 'cyan',
  uploading: 'brand',
  merging: 'cyan',
  paused: 'warn',
  done: 'ok',
  instant: 'ok',
  error: 'bad',
  canceled: 'muted',
}

const RING = 2 * Math.PI * 26

const progress = computed(() => {
  const t = props.task
  if (t.state === 'done' || t.state === 'instant') return 100
  if (t.state === 'hashing') return Math.max(0, Math.min(100, t.hashProgress * 100))
  return percent(t.completedBytes, t.size)
})

const dashOffset = computed(() => RING * (1 - progress.value / 100))
const tone = computed(() => TONES[props.task.state] || 'muted')
const stateLabel = computed(() => LABELS[props.task.state] || props.task.state)
const isActive = computed(() => ACTIVE.has(props.task.state))
const isUploading = computed(() => props.task.state === 'uploading')
const canPause = computed(() => ACTIVE.has(props.task.state) && props.task.state !== 'queued')
const canResume = computed(() => props.task.state === 'paused' || props.task.state === 'error')
const canCancel = computed(() => !['done', 'instant', 'canceled'].includes(props.task.state))
const canRemove = computed(() =>
  ['done', 'instant', 'canceled', 'error', 'paused'].includes(props.task.state),
)

const meta = computed(() => {
  const t = props.task
  return {
    completed: `${formatBytes(t.completedBytes)} / ${formatBytes(t.size)}`,
    speed: formatSpeed(t.speed),
    eta: formatDuration(t.eta),
    uploaded: t.uploadedChunks || 0,
    total: t.chunkTotal || 0,
    skipped: t.skippedChunks || 0,
    retries: t.retryTotal || 0,
    heals: t.healRounds || 0,
  }
})

function download() {
  downloadFile({
    fileId: props.task.fileId,
    fileName: props.task.name,
    fileSize: props.task.size,
    status: 1,
  })
}
</script>

<template>
  <article class="task-card" :class="[`tone-${tone}`, { active: isActive }]">
    <div
      class="ring"
      :class="{ spinning: isActive }"
      role="progressbar"
      :aria-label="`${task.relativePath || task.name} · ${stateLabel}`"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="Math.round(progress)"
    >
      <svg viewBox="0 0 64 64">
        <circle class="ring-track" cx="32" cy="32" r="26" />
        <circle
          class="ring-value"
          cx="32"
          cy="32"
          r="26"
          :stroke-dasharray="RING"
          :stroke-dashoffset="dashOffset"
        />
      </svg>
      <span class="ring-text"> {{ Math.floor(progress) }}<i>%</i> </span>
    </div>

    <div class="task-main">
      <header class="task-head">
        <div class="task-title">
          <h3 :title="task.name">{{ task.name }}</h3>
          <span class="state-badge">{{ stateLabel }}</span>
          <span v-if="task.instant" class="mini-badge ok">
            <BaseIcon name="bolt" :size="11" /> 秒传
          </span>
          <span v-if="task.skippedChunks" class="mini-badge cyan">
            <BaseIcon name="refresh" :size="11" /> 跳过 {{ task.skippedChunks }} 片
          </span>
          <span v-if="task.retryTotal" class="mini-badge warn">
            <BaseIcon name="alert" :size="11" /> 重试 {{ task.retryTotal }} 次
          </span>
          <span v-if="task.healRounds" class="mini-badge warn">
            <BaseIcon name="shield" :size="11" /> 自愈 {{ task.healRounds }} 轮
          </span>
          <span v-if="task.hashFromCache && isActive" class="mini-badge muted"> 哈希缓存 </span>
        </div>
        <span class="task-size">{{ formatBytes(task.size) }}</span>
      </header>

      <p v-if="task.relativePath" class="task-path" :title="task.relativePath">
        {{ task.relativePath }}
      </p>

      <p class="task-msg" :class="{ error: task.state === 'error' }">
        {{ task.message || '等待上传' }}
      </p>

      <ChunkGrid v-if="task.chunkTotal" :states="task.chunkStates" />

      <div class="task-stats">
        <span>
          <BaseIcon name="hash" :size="13" />
          {{ meta.completed }}
        </span>
        <span v-if="isUploading">
          <BaseIcon name="gauge" :size="13" />
          {{ meta.speed }}
        </span>
        <span v-if="isUploading && task.eta">
          <BaseIcon name="clock" :size="13" />
          剩余 {{ meta.eta }}
        </span>
        <span v-if="meta.total">
          <BaseIcon name="layers" :size="13" />
          {{ meta.uploaded }}/{{ meta.total }} 片
        </span>
        <span v-if="task.state === 'done'" class="stat-ok">
          <BaseIcon name="check" :size="13" />
          SHA-256 校验通过
        </span>
      </div>

      <div class="task-actions">
        <button v-if="canPause" class="btn btn-ghost btn-sm" type="button" @click="pauseTask(task)">
          <BaseIcon name="pause" :size="14" />
          暂停
        </button>
        <button
          v-if="canResume"
          class="btn btn-primary btn-sm"
          type="button"
          @click="resumeTask(task)"
        >
          <BaseIcon name="play" :size="14" />
          {{ task.state === 'error' ? '重试' : '继续' }}
        </button>
        <button
          v-if="task.state === 'done' || task.state === 'instant'"
          class="btn btn-ghost btn-sm"
          type="button"
          @click="download"
        >
          <BaseIcon name="download" :size="14" />
          下载
        </button>
        <button
          v-if="canCancel"
          class="btn btn-danger btn-sm"
          type="button"
          @click="cancelTask(task)"
        >
          <BaseIcon name="x" :size="14" />
          取消
        </button>
        <button
          v-if="canRemove"
          class="btn btn-ghost btn-sm"
          type="button"
          @click="removeTask(task)"
        >
          <BaseIcon name="trash" :size="14" />
          移除
        </button>
        <span v-if="task.hash" class="task-hash" :title="task.hash">
          {{ shortHash(task.hash) }}
        </span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.task-card {
  display: grid;
  grid-template-columns: 82px 1fr;
  gap: 18px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(var(--blur));
  transition:
    border-color 0.2s ease,
    transform 0.2s ease;
}

.task-card.active {
  border-color: rgba(109, 93, 252, 0.32);
}

.task-card.active:hover {
  transform: translateY(-1px);
}

.ring {
  position: relative;
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
}

.ring svg {
  width: 76px;
  height: 76px;
  transform: rotate(-90deg);
}

.ring-track {
  fill: none;
  stroke: var(--ring-track);
  stroke-width: 5;
}

.ring-value {
  fill: none;
  stroke: var(--ring-color, var(--brand));
  stroke-width: 5;
  stroke-linecap: round;
  transition:
    stroke-dashoffset 0.4s ease,
    stroke 0.3s ease;
  filter: drop-shadow(0 0 5px var(--ring-glow, rgba(109, 93, 252, 0.45)));
}

.ring.spinning .ring-value {
  animation: ring-glow 1.8s ease-in-out infinite;
}

@keyframes ring-glow {
  50% {
    filter: drop-shadow(0 0 9px var(--ring-glow, rgba(109, 93, 252, 0.7)));
  }
}

.ring-text {
  position: absolute;
  font-size: 16px;
  font-weight: 680;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.ring-text i {
  font-style: normal;
  font-size: 10px;
  color: var(--muted);
  margin-left: 1px;
}

.task-main {
  min-width: 0;
}

.task-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.task-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  min-width: 0;
}

.task-title h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 640;
  max-width: min(46vw, 520px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-size {
  flex: none;
  color: var(--muted);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  padding-top: 2px;
}

.state-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 560;
  color: var(--state-color);
  background: color-mix(in srgb, var(--state-color) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--state-color) 34%, transparent);
  white-space: nowrap;
}

.mini-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 7px;
  border-radius: 999px;
  font-size: 10.5px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--muted);
  white-space: nowrap;
}

.mini-badge.ok {
  color: var(--ok);
  border-color: color-mix(in srgb, var(--ok) 35%, transparent);
}
.mini-badge.cyan {
  color: var(--brand-2);
  border-color: color-mix(in srgb, var(--brand-2) 35%, transparent);
}
.mini-badge.warn {
  color: var(--warn);
  border-color: color-mix(in srgb, var(--warn) 35%, transparent);
}
.mini-badge.muted {
  color: var(--muted-2);
}

.task-path {
  margin: 4px 0 0;
  color: var(--muted-2);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.task-msg {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.task-msg.error {
  color: var(--bad);
}

.task-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  color: var(--muted-2);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.task-stats span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.stat-ok {
  color: var(--ok);
}

.task-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 13px;
}

.task-hash {
  margin-left: auto;
  color: var(--muted-2);
  font-size: 11px;
  font-family: var(--mono);
  letter-spacing: 0.02em;
}

/* 状态色 */
.tone-muted {
  --state-color: var(--muted);
}
.tone-violet {
  --state-color: var(--violet);
}
.tone-cyan {
  --state-color: var(--brand-2);
}
.tone-brand {
  --state-color: var(--brand-light);
}
.tone-warn {
  --state-color: var(--warn);
}
.tone-ok {
  --state-color: var(--ok);
}
.tone-bad {
  --state-color: var(--bad);
}

.tone-violet {
  --ring-color: var(--violet);
  --ring-glow: rgba(168, 85, 247, 0.45);
}
.tone-cyan {
  --ring-color: var(--brand-2);
  --ring-glow: rgba(34, 211, 238, 0.45);
}
.tone-brand {
  --ring-color: var(--brand-light);
  --ring-glow: rgba(139, 127, 255, 0.45);
}
.tone-warn {
  --ring-color: var(--warn);
  --ring-glow: rgba(251, 191, 36, 0.4);
}
.tone-ok {
  --ring-color: var(--ok);
  --ring-glow: rgba(52, 211, 153, 0.45);
}
.tone-bad {
  --ring-color: var(--bad);
  --ring-glow: rgba(251, 113, 133, 0.45);
}

@media (max-width: 640px) {
  .task-card {
    grid-template-columns: 1fr;
    gap: 12px;
    padding: 16px;
  }

  .ring {
    width: 54px;
    height: 54px;
  }

  .ring svg {
    width: 54px;
    height: 54px;
  }

  .ring-text {
    font-size: 13px;
  }

  .task-title h3 {
    max-width: 100%;
  }
}
</style>
