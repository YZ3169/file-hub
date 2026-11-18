<script setup>
import { computed } from 'vue'

const props = defineProps({
  states: { type: Array, default: () => [] },
})

const MAX_CELLS = 96

const info = computed(() => {
  const n = props.states.length
  if (!n) return { groups: [], per: 1 }
  const per = Math.max(1, Math.ceil(n / MAX_CELLS))
  const groups = []
  for (let start = 0; start < n; start += per) {
    const slice = props.states.slice(start, start + per)
    let status = 'pending'
    if (slice.some((s) => s === 'failed')) status = 'failed'
    else if (slice.some((s) => s === 'retrying')) status = 'retrying'
    else if (slice.some((s) => s === 'uploading')) status = 'uploading'
    else if (slice.every((s) => s === 'done')) status = 'done'
    else if (slice.some((s) => s === 'done')) status = 'partial'
    groups.push({
      key: start,
      status,
      start,
      end: Math.min(n, start + per) - 1,
      count: slice.length,
    })
  }
  return { groups, per }
})

const labels = {
  done: '已校验',
  partial: '部分完成',
  uploading: '上传中',
  retrying: '重试中',
  failed: '失败',
  pending: '待上传',
}
</script>

<template>
  <div class="chunk-wrap">
    <div class="chunk-grid" :style="{ '--chunk-cols': Math.min(48, info.groups.length) }">
      <span
        v-for="g in info.groups"
        :key="g.key"
        class="chunk"
        :class="`is-${g.status}`"
        :title="`分片 ${g.start + 1}–${g.end + 1} · ${labels[g.status]}`"
      ></span>
    </div>
    <span v-if="info.per > 1" class="chunk-note">每格 ≈ {{ info.per }} 片</span>
  </div>
</template>

<style scoped>
.chunk-wrap {
  margin: 10px 0 11px;
}

.chunk-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
  min-height: 6px;
}

.chunk {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: var(--chunk-pending);
  transition: background 0.25s ease, box-shadow 0.25s ease;
}

.chunk.is-done {
  background: var(--ok);
  opacity: 0.78;
}

.chunk.is-partial {
  background: color-mix(in srgb, var(--brand-2) 55%, var(--chunk-pending));
}

.chunk.is-uploading {
  background: var(--brand-2);
  box-shadow: 0 0 7px rgba(34, 211, 238, 0.65);
  animation: chunk-pulse 1.1s ease-in-out infinite;
}

.chunk.is-retrying {
  background: var(--warn);
  box-shadow: 0 0 7px rgba(251, 191, 36, 0.5);
  animation: chunk-pulse 0.8s ease-in-out infinite;
}

.chunk.is-failed {
  background: var(--bad);
}

@keyframes chunk-pulse {
  50% {
    opacity: 0.35;
  }
}

.chunk-note {
  display: inline-block;
  margin-top: 6px;
  color: var(--muted-2);
  font-size: 11px;
}
</style>
