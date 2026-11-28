<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { createDownloadTicket, fetchTextPreview } from '../lib/api'
import { copyText } from '../lib/clipboard'
import { formatBytes } from '../lib/format'
import { parseMarkdown } from '../lib/markdown'
import { toast } from '../stores/uploader'

const props = defineProps({
  file: { type: Object, required: true },
  directUrl: { type: String, default: '' },
  items: { type: Array, default: () => [] },
})

const emit = defineEmits(['close', 'download', 'select'])

const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'ico', 'svg'])
const VIDEO_EXTS = new Set(['mp4', 'm4v', 'webm', 'ogv', 'mov'])
const AUDIO_EXTS = new Set(['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac'])
const TEXT_EXTS = new Set([
  'txt',
  'md',
  'markdown',
  'log',
  'csv',
  'tsv',
  'json',
  'yml',
  'yaml',
  'toml',
  'ini',
  'conf',
  'properties',
  'env',
  'xml',
  'html',
  'htm',
  'css',
  'scss',
  'less',
  'js',
  'mjs',
  'cjs',
  'ts',
  'tsx',
  'jsx',
  'vue',
  'java',
  'kt',
  'kts',
  'py',
  'go',
  'rs',
  'c',
  'h',
  'cpp',
  'hpp',
  'cs',
  'sh',
  'bash',
  'zsh',
  'bat',
  'cmd',
  'ps1',
  'sql',
  'gradle',
  'dockerfile',
])

const loading = ref(true)
const error = ref('')
const inlineUrl = ref('')
const textContent = ref('')
const textTruncated = ref(false)
const zoomed = ref(false)
const markdownRendered = ref(true)
const closeButton = ref(null)
let disposed = false
let previousFocus = null

const ext = computed(() => {
  const name = props.file?.fileName || ''
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || dot === name.length - 1) return ''
  return name.slice(dot + 1).toLowerCase()
})

const isMarkdown = computed(() => ext.value === 'md' || ext.value === 'markdown')
const markdownBlocks = computed(() =>
  isMarkdown.value && textContent.value ? parseMarkdown(textContent.value) : [],
)

const currentIndex = computed(() =>
  props.items.findIndex((item) => item?.fileId === props.file?.fileId),
)
const hasPrev = computed(() => props.items.length > 1 && currentIndex.value > 0)
const hasNext = computed(
  () =>
    props.items.length > 1 &&
    currentIndex.value >= 0 &&
    currentIndex.value < props.items.length - 1,
)

function goPrev() {
  if (hasPrev.value) emit('select', props.items[currentIndex.value - 1])
}

function goNext() {
  if (hasNext.value) emit('select', props.items[currentIndex.value + 1])
}

function onWindowKeyDown(event) {
  if (event.ctrlKey || event.metaKey || event.altKey) return
  const target = event.target
  if (
    target &&
    (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || target.isContentEditable)
  ) {
    return
  }
  if (event.key === 'ArrowLeft') {
    if (hasPrev.value) {
      event.preventDefault()
      goPrev()
    }
  } else if (event.key === 'ArrowRight') {
    if (hasNext.value) {
      event.preventDefault()
      goNext()
    }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
  }
}

const category = computed(() => {
  const e = ext.value
  const lower = (props.file?.fileName || '').toLowerCase()
  if (IMAGE_EXTS.has(e)) return 'image'
  if (VIDEO_EXTS.has(e)) return 'video'
  if (AUDIO_EXTS.has(e)) return 'audio'
  if (e === 'pdf') return 'pdf'
  if (TEXT_EXTS.has(e) || ['readme', 'license', 'dockerfile', 'makefile'].includes(lower)) {
    return 'text'
  }
  return 'unsupported'
})

const lines = computed(() => (textContent.value ? textContent.value.split(/\r?\n/) : []))

function toInlineUrl(url) {
  if (!url) return ''
  return url.includes('?') ? `${url}&inline=true` : `${url}?inline=true`
}

async function initPreview() {
  loading.value = true
  error.value = ''
  inlineUrl.value = ''
  textContent.value = ''
  textTruncated.value = false
  zoomed.value = false
  markdownRendered.value = true

  if (category.value === 'unsupported') {
    loading.value = false
    return
  }

  try {
    let baseUrl = props.directUrl
    if (!baseUrl) {
      const ticket = await createDownloadTicket(props.file.fileId)
      if (disposed) return
      baseUrl = ticket.url
    }
    const url = toInlineUrl(baseUrl)
    inlineUrl.value = url

    if (category.value === 'text') {
      const res = await fetchTextPreview(url)
      if (disposed) return
      textContent.value = res.text
      textTruncated.value = res.truncated
    }
  } catch (e) {
    if (disposed) return
    error.value = e.message || '加载预览失败'
  } finally {
    if (!disposed) loading.value = false
  }
}

async function copyTextContent() {
  const ok = await copyText(textContent.value)
  toast(ok ? '内容已复制到剪贴板' : '复制失败，请手动选中复制', ok ? 'success' : 'warn')
}

watch(
  () => [props.file?.fileId, props.directUrl],
  () => initPreview(),
)

onMounted(() => {
  previousFocus = document.activeElement
  closeButton.value?.focus()
  window.addEventListener('keydown', onWindowKeyDown)
  initPreview()
})

onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('keydown', onWindowKeyDown)
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<template>
  <div class="preview-backdrop" @click.self="emit('close')">
    <section
      class="preview-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="preview-title"
      data-testid="file-preview-dialog"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="preview-head">
        <div class="title-wrap">
          <BaseIcon name="eye" :size="18" />
          <div>
            <h2 id="preview-title">{{ file.fileName }}</h2>
            <span>{{ formatBytes(file.fileSize) }} · {{ ext ? ext.toUpperCase() : 'FILE' }}</span>
          </div>
        </div>
        <div class="head-actions">
          <div v-if="items.length > 1 && currentIndex >= 0" class="nav-group">
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="!hasPrev"
              title="上一个（←）"
              data-testid="preview-prev"
              @click="goPrev"
            >
              上一项
            </button>
            <span class="nav-pos">{{ currentIndex + 1 }} / {{ items.length }}</span>
            <button
              class="btn btn-ghost btn-sm"
              type="button"
              :disabled="!hasNext"
              title="下一个（→）"
              data-testid="preview-next"
              @click="goNext"
            >
              下一项
            </button>
          </div>
          <button
            v-if="category === 'image' && !loading && !error"
            class="btn btn-ghost btn-sm"
            type="button"
            @click="zoomed = !zoomed"
          >
            {{ zoomed ? '适应窗口' : '原始尺寸' }}
          </button>
          <button
            v-if="isMarkdown && textContent && !loading && !error"
            class="btn btn-ghost btn-sm"
            type="button"
            data-testid="toggle-markdown-mode"
            @click="markdownRendered = !markdownRendered"
          >
            {{ markdownRendered ? '源码' : '渲染视图' }}
          </button>
          <button
            v-if="category === 'text' && textContent"
            class="btn btn-ghost btn-sm"
            type="button"
            @click="copyTextContent"
          >
            <BaseIcon name="copy" :size="14" /> 复制文本
          </button>
          <button class="btn btn-primary btn-sm" type="button" @click="emit('download')">
            <BaseIcon name="download" :size="14" /> 下载
          </button>
          <button
            ref="closeButton"
            class="icon-btn"
            type="button"
            aria-label="关闭预览"
            @click="emit('close')"
          >
            <BaseIcon name="x" :size="16" />
          </button>
        </div>
      </header>

      <div class="preview-body">
        <div v-if="loading" class="center-state">
          <span class="spinner" aria-hidden="true"></span>
          <span>正在加载预览…</span>
        </div>

        <div v-else-if="error" class="center-state error" role="alert">
          <BaseIcon name="alert" :size="24" />
          <strong>无法预览该文件</strong>
          <span>{{ error }}</span>
        </div>

        <div v-else-if="category === 'image'" class="image-stage" :class="{ zoomed }">
          <img :src="inlineUrl" :alt="file.fileName" @click="zoomed = !zoomed" />
        </div>

        <div v-else-if="category === 'video'" class="media-stage">
          <video controls preload="metadata" :src="inlineUrl" class="video-player"></video>
        </div>

        <div v-else-if="category === 'audio'" class="audio-stage">
          <BaseIcon name="play" :size="36" />
          <strong>{{ file.fileName }}</strong>
          <audio controls preload="metadata" :src="inlineUrl" class="audio-player"></audio>
        </div>

        <iframe
          v-else-if="category === 'pdf'"
          :src="inlineUrl"
          :title="file.fileName"
          class="pdf-frame"
        ></iframe>

        <div v-else-if="category === 'text'" class="text-stage">
          <p v-if="textTruncated" class="truncated-banner">
            文件较大，当前仅预览前 2 MB 内容；完整内容请点击右上角「下载」。
          </p>
          <div
            v-if="isMarkdown && markdownRendered"
            class="markdown-body"
            data-testid="markdown-preview"
          >
            <template v-for="(block, bIdx) in markdownBlocks" :key="bIdx">
              <component :is="`h${block.level}`" v-if="block.type === 'heading'" class="md-h">
                <template v-for="(run, rIdx) in block.runs" :key="rIdx">
                  <code v-if="run.type === 'code'" class="md-inline-code">{{ run.text }}</code>
                  <a
                    v-else-if="run.type === 'link'"
                    :href="run.href"
                    target="_blank"
                    rel="noopener noreferrer"
                    >{{ run.text }}</a
                  >
                  <strong v-else-if="run.type === 'strong'">{{ run.text }}</strong>
                  <em v-else-if="run.type === 'em'">{{ run.text }}</em>
                  <template v-else>{{ run.text }}</template>
                </template>
              </component>
              <pre
                v-else-if="block.type === 'code'"
                class="md-code-block"
              ><code>{{ block.text }}</code></pre>
              <hr v-else-if="block.type === 'hr'" class="md-hr" />
              <blockquote v-else-if="block.type === 'blockquote'" class="md-quote">
                <template v-for="(run, rIdx) in block.runs" :key="rIdx">
                  <code v-if="run.type === 'code'" class="md-inline-code">{{ run.text }}</code>
                  <a
                    v-else-if="run.type === 'link'"
                    :href="run.href"
                    target="_blank"
                    rel="noopener noreferrer"
                    >{{ run.text }}</a
                  >
                  <strong v-else-if="run.type === 'strong'">{{ run.text }}</strong>
                  <em v-else-if="run.type === 'em'">{{ run.text }}</em>
                  <template v-else>{{ run.text }}</template>
                </template>
              </blockquote>
              <ul v-else-if="block.type === 'ul'" class="md-list">
                <li v-for="(item, lIdx) in block.items" :key="lIdx">
                  <template v-for="(run, rIdx) in item" :key="rIdx">
                    <code v-if="run.type === 'code'" class="md-inline-code">{{ run.text }}</code>
                    <a
                      v-else-if="run.type === 'link'"
                      :href="run.href"
                      target="_blank"
                      rel="noopener noreferrer"
                      >{{ run.text }}</a
                    >
                    <strong v-else-if="run.type === 'strong'">{{ run.text }}</strong>
                    <em v-else-if="run.type === 'em'">{{ run.text }}</em>
                    <template v-else>{{ run.text }}</template>
                  </template>
                </li>
              </ul>
              <ol v-else-if="block.type === 'ol'" class="md-list">
                <li v-for="(item, lIdx) in block.items" :key="lIdx">
                  <template v-for="(run, rIdx) in item" :key="rIdx">
                    <code v-if="run.type === 'code'" class="md-inline-code">{{ run.text }}</code>
                    <a
                      v-else-if="run.type === 'link'"
                      :href="run.href"
                      target="_blank"
                      rel="noopener noreferrer"
                      >{{ run.text }}</a
                    >
                    <strong v-else-if="run.type === 'strong'">{{ run.text }}</strong>
                    <em v-else-if="run.type === 'em'">{{ run.text }}</em>
                    <template v-else>{{ run.text }}</template>
                  </template>
                </li>
              </ol>
              <p v-else class="md-p">
                <template v-for="(run, rIdx) in block.runs" :key="rIdx">
                  <code v-if="run.type === 'code'" class="md-inline-code">{{ run.text }}</code>
                  <a
                    v-else-if="run.type === 'link'"
                    :href="run.href"
                    target="_blank"
                    rel="noopener noreferrer"
                    >{{ run.text }}</a
                  >
                  <strong v-else-if="run.type === 'strong'">{{ run.text }}</strong>
                  <em v-else-if="run.type === 'em'">{{ run.text }}</em>
                  <template v-else>{{ run.text }}</template>
                </template>
              </p>
            </template>
          </div>
          <pre
            v-else
            class="code-view"
          ><code><span v-for="(line, idx) in lines" :key="idx" class="code-line"><i class="ln">{{ idx + 1 }}</i><span>{{ line }}</span></span></code></pre>
        </div>

        <div v-else class="center-state">
          <BaseIcon name="file" :size="32" />
          <strong>暂不支持在线预览此格式</strong>
          <span>请直接下载到本地后打开查看</span>
          <button class="btn btn-primary" type="button" @click="emit('download')">
            <BaseIcon name="download" :size="15" /> 立即下载
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.preview-backdrop {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(5, 8, 18, 0.76);
  backdrop-filter: blur(8px);
}

.preview-dialog {
  display: flex;
  flex-direction: column;
  width: min(980px, 100%);
  height: min(86vh, 780px);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--bg);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.55);
  overflow: hidden;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.title-wrap h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title-wrap span {
  font-size: 12px;
  color: var(--muted);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
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

.preview-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
}

.center-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px;
  text-align: center;
  color: var(--muted);
}

.center-state strong {
  color: var(--text);
  font-size: 15px;
}

.center-state.error strong {
  color: var(--bad);
}

.spinner {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2.5px solid var(--border-strong);
  border-top-color: var(--brand-2);
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.image-stage {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 20px;
  overflow: auto;
}

.image-stage img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 8px;
  cursor: zoom-in;
}

.image-stage.zoomed {
  display: block;
}

.image-stage.zoomed img {
  max-width: none;
  max-height: none;
  cursor: zoom-out;
}

.media-stage {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 16px;
  background: #000;
}

.video-player {
  max-width: 100%;
  max-height: 100%;
  outline: none;
}

.audio-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 32px;
}

.audio-player {
  width: min(480px, 100%);
}

.pdf-frame {
  flex: 1;
  width: 100%;
  border: 0;
  background: #fff;
}

.text-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.truncated-banner {
  margin: 0;
  padding: 8px 16px;
  background: color-mix(in srgb, var(--warn) 14%, transparent);
  color: var(--warn);
  font-size: 12.5px;
  border-bottom: 1px solid var(--border);
}

.code-view {
  flex: 1;
  margin: 0;
  padding: 14px 0;
  overflow: auto;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.55;
  tab-size: 4;
}

.code-line {
  display: grid;
  grid-template-columns: 52px 1fr;
  padding-right: 16px;
  white-space: pre-wrap;
  word-break: break-word;
}

.code-line:hover {
  background: var(--hover);
}

.ln {
  user-select: none;
  text-align: right;
  padding-right: 14px;
  color: var(--muted-2);
  font-style: normal;
}

.nav-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.nav-pos {
  font-size: 12px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.markdown-body {
  flex: 1;
  overflow: auto;
  padding: 20px 26px;
  line-height: 1.7;
  font-size: 14px;
  color: var(--text);
}

.md-h {
  margin: 18px 0 8px;
  line-height: 1.35;
}
.markdown-body > .md-h:first-child {
  margin-top: 0;
}
.md-p {
  margin: 0 0 12px;
}
.md-list {
  margin: 0 0 12px;
  padding-left: 22px;
}
.md-quote {
  margin: 0 0 12px;
  padding: 8px 14px;
  border-left: 3px solid var(--brand);
  background: var(--panel);
  color: var(--muted);
  border-radius: 0 8px 8px 0;
}
.md-code-block {
  margin: 0 0 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel);
  font-family: var(--mono);
  font-size: 12.5px;
  overflow-x: auto;
}
.md-inline-code {
  padding: 1px 5px;
  border-radius: 5px;
  background: var(--panel-2);
  font-family: var(--mono);
  font-size: 0.9em;
}
.md-hr {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 16px 0;
}
.markdown-body a {
  color: var(--brand-2);
}
</style>
