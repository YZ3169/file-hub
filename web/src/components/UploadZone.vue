<script setup>
import { ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { addFiles, state } from '../stores/uploader'
import { formatBytes } from '../lib/format'

const input = ref(null)
const dragging = ref(false)
let dragDepth = 0

function pick() {
  input.value?.click()
}

function onChange(event) {
  if (event.target.files?.length) addFiles(event.target.files)
  event.target.value = ''
}

function onDragEnter() {
  dragDepth += 1
  dragging.value = true
}

function onDragLeave() {
  dragDepth -= 1
  if (dragDepth <= 0) {
    dragDepth = 0
    dragging.value = false
  }
}

function onDrop(event) {
  dragDepth = 0
  dragging.value = false
  if (event.dataTransfer?.files?.length) addFiles(event.dataTransfer.files)
}
</script>

<template>
  <section
    class="dropzone"
    :class="{ dragging }"
    @click="pick"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <input ref="input" type="file" multiple hidden @change="onChange" />

    <div class="drop-glow" aria-hidden="true"></div>
    <div class="drop-icon">
      <BaseIcon name="cloud" :size="34" :stroke-width="1.5" />
    </div>

    <div class="drop-copy">
      <h2>{{ dragging ? '松开即可加入队列' : '拖拽文件到这里，或点击选择' }}</h2>
      <p>
        大文件自动切片 · 断网/刷新后只补缺失分片 · 相同内容 0 字节秒传
      </p>
    </div>

    <div class="drop-meta">
      <span class="pill">
        <BaseIcon name="layers" :size="14" />
        分片 {{ formatBytes(state.config.chunkSize) }}
      </span>
      <span class="pill">
        <BaseIcon name="shield" :size="14" />
        逐片 + 整文件 SHA-256
      </span>
      <span class="pill">
        <BaseIcon name="server" :size="14" />
        {{ state.config.backend || '本地存储' }}
      </span>
    </div>

    <button class="btn btn-primary drop-btn" type="button" @click.stop="pick">
      <BaseIcon name="upload" :size="16" />
      选择文件
    </button>
  </section>
</template>

<style scoped>
.dropzone {
  position: relative;
  display: grid;
  grid-template-columns: auto 1fr auto;
  grid-template-areas:
    'icon copy button'
    'meta meta meta';
  align-items: center;
  gap: 18px 20px;
  padding: 26px 28px 22px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  background:
    linear-gradient(135deg, rgba(109, 93, 252, 0.09), rgba(34, 211, 238, 0.035) 45%, transparent 70%),
    var(--panel);
  box-shadow: var(--shadow-soft);
  cursor: pointer;
  transition: border-color 0.2s ease, transform 0.2s ease, background 0.2s ease;
  overflow: hidden;
}

.dropzone:hover,
.dropzone:focus-visible {
  border-color: rgba(109, 93, 252, 0.65);
  transform: translateY(-1px);
  outline: none;
}

.dropzone.dragging {
  border-color: var(--brand-2);
  background:
    linear-gradient(135deg, rgba(34, 211, 238, 0.16), rgba(109, 93, 252, 0.12) 60%, transparent),
    var(--panel);
  box-shadow: 0 0 0 4px rgba(34, 211, 238, 0.12), var(--shadow-soft);
}

.drop-glow {
  position: absolute;
  width: 280px;
  height: 280px;
  right: -90px;
  top: -150px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(109, 93, 252, 0.36), transparent 65%);
  filter: blur(10px);
  pointer-events: none;
}

.drop-icon {
  grid-area: icon;
  display: grid;
  place-items: center;
  width: 68px;
  height: 68px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(140deg, var(--brand) 10%, var(--brand-2) 110%);
  box-shadow: 0 16px 34px rgba(109, 93, 252, 0.35);
}

.drop-copy {
  grid-area: copy;
  min-width: 0;
}

.drop-copy h2 {
  margin: 0 0 6px;
  font-size: clamp(18px, 2vw, 22px);
  font-weight: 650;
  letter-spacing: -0.01em;
}

.drop-copy p {
  margin: 0;
  color: var(--muted);
  font-size: 13.5px;
  line-height: 1.6;
}

.drop-btn {
  grid-area: button;
  white-space: nowrap;
}

.drop-meta {
  grid-area: meta;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 4px;
}

.drop-meta .pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-2);
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 760px) {
  .dropzone {
    grid-template-columns: auto 1fr;
    grid-template-areas:
      'icon copy'
      'meta meta'
      'button button';
    padding: 22px;
  }

  .drop-btn {
    justify-content: center;
  }
}
</style>
