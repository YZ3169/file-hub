<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { copyText } from '../lib/clipboard'
import { formatBytes, formatDate } from '../lib/format'

const props = defineProps({
  file: { type: Object, required: true },
  downloading: { type: Boolean, default: false },
})

const emit = defineEmits(['close', 'download'])
const dialog = ref(null)
const copying = ref(false)
const copyMessage = ref('')
const copyFailed = ref(false)
const completed = computed(() => props.file.status === 1)
const hash = computed(() =>
  typeof props.file.fileHash === 'string' ? props.file.fileHash.trim() : '',
)
const statusLabel = computed(() => {
  if (completed.value) return '已完成'
  return props.file.status === 0 ? '上传中' : '未知状态'
})
const exactBytes = computed(() => {
  const value = props.file.fileSize
  if (value === null || value === undefined || value === '') return '—'
  const size = Number(value)
  return Number.isSafeInteger(size) && size >= 0
    ? `${new Intl.NumberFormat('zh-CN').format(size)} 字节`
    : '—'
})

let previousFocus = null
let copyRequest = 0
let disposed = false

watch(
  () => [props.file.fileId, hash.value],
  () => {
    copyRequest += 1
    copying.value = false
    copyMessage.value = ''
    copyFailed.value = false
  },
)

async function copyHash() {
  if (!hash.value || copying.value) return
  const request = ++copyRequest
  copying.value = true
  copyMessage.value = ''
  let copied = false
  try {
    copied = await copyText(hash.value)
  } catch {
    // 剪贴板权限可能在弹窗打开期间发生变化，保留手动复制入口。
  }
  if (disposed || request !== copyRequest) return
  copying.value = false
  copyFailed.value = !copied
  copyMessage.value = copied
    ? 'SHA-256 已复制，可与本地文件的校验值对比。'
    : '复制失败，请在上方文本框中手动选中并复制 SHA-256。'
}

function download() {
  if (completed.value && !props.downloading) emit('download', props.file)
}

function onBackdropClick(event) {
  if (event.target !== dialog.value) return
  const bounds = dialog.value.getBoundingClientRect()
  if (
    event.clientX < bounds.left ||
    event.clientX > bounds.right ||
    event.clientY < bounds.top ||
    event.clientY > bounds.bottom
  ) {
    emit('close')
  }
}

onMounted(() => {
  previousFocus = document.activeElement
  dialog.value.showModal()
})

onBeforeUnmount(() => {
  disposed = true
  copyRequest += 1
  if (dialog.value?.open) dialog.value.close()
  if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
})
</script>

<template>
  <dialog
    ref="dialog"
    class="file-details"
    aria-labelledby="file-details-title"
    data-testid="file-details-dialog"
    @cancel.prevent="emit('close')"
    @click="onBackdropClick"
  >
    <header class="dialog-head">
      <div class="heading">
        <span class="file-icon"><BaseIcon name="file" :size="22" /></span>
        <div>
          <h2 id="file-details-title">文件详情</h2>
          <span>查看文件信息与完整校验值</span>
        </div>
      </div>
      <button
        class="close-button"
        type="button"
        aria-label="关闭文件详情"
        autofocus
        @click="emit('close')"
      >
        <BaseIcon name="x" :size="18" />
      </button>
    </header>

    <dl class="metadata">
      <div class="filename-field">
        <dt>完整文件名</dt>
        <dd class="filename" data-testid="file-details-name">{{ file.fileName || '—' }}</dd>
      </div>
      <div v-if="file.relativePath" class="filename-field">
        <dt>文件夹内路径</dt>
        <dd class="filename" data-testid="file-details-path">{{ file.relativePath }}</dd>
      </div>
      <div>
        <dt>文件大小</dt>
        <dd>{{ formatBytes(file.fileSize) }}</dd>
        <dd class="secondary" data-testid="file-details-bytes">{{ exactBytes }}</dd>
      </div>
      <div>
        <dt>上传时间</dt>
        <dd>
          <time :datetime="file.createTime || undefined">{{ formatDate(file.createTime) }}</time>
        </dd>
      </div>
      <div class="status-field">
        <dt>文件状态</dt>
        <dd class="file-status" :class="completed ? 'completed' : 'pending'">
          <BaseIcon :name="completed ? 'check' : 'clock'" :size="15" />
          {{ statusLabel }}
        </dd>
      </div>
    </dl>

    <section class="hash-section" aria-labelledby="file-hash-title">
      <div class="hash-heading">
        <h3 id="file-hash-title"><BaseIcon name="hash" :size="16" /> SHA-256</h3>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="!hash || copying"
          data-testid="copy-file-hash"
          @click="copyHash"
        >
          {{ copying ? '复制中…' : '复制 SHA-256' }}
        </button>
      </div>
      <textarea
        v-if="hash"
        class="hash-value"
        :value="hash"
        readonly
        rows="3"
        spellcheck="false"
        aria-label="完整 SHA-256"
        data-testid="file-details-hash"
      ></textarea>
      <p v-else class="hash-missing">暂无 SHA-256 记录。</p>
      <p v-if="completed" class="hint">
        这是服务端记录的校验值；下载后，可与本地文件计算出的 SHA-256 对比。
      </p>
      <p v-else class="hint pending-note">
        文件尚未完成上传与校验，暂不可下载。当前 SHA-256 为上传时提供的预期值。
      </p>
      <p v-if="copyMessage" class="copy-feedback" :class="{ failed: copyFailed }" role="status">
        <BaseIcon :name="copyFailed ? 'alert' : 'check'" :size="15" />
        {{ copyMessage }}
      </p>
    </section>

    <footer class="dialog-foot">
      <button class="btn btn-ghost" type="button" @click="emit('close')">关闭</button>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="!completed || downloading"
        :aria-busy="downloading"
        data-testid="file-details-download"
        @click="download"
      >
        <BaseIcon name="download" :size="16" />
        {{ downloading ? '准备中…' : '下载文件' }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.file-details {
  width: min(580px, calc(100% - 32px));
  max-width: none;
  max-height: calc(100dvh - 32px);
  margin: auto;
  padding: 24px;
  overflow: auto;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--bg);
  color: var(--text);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.45);
}

.file-details::backdrop {
  background: rgba(5, 6, 12, 0.65);
  backdrop-filter: blur(6px);
}

.dialog-head,
.heading,
.hash-heading,
.dialog-foot {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dialog-head,
.hash-heading {
  justify-content: space-between;
}

.heading {
  min-width: 0;
}

.heading h2 {
  margin: 0 0 3px;
  font-size: 19px;
}

.heading span:not(.file-icon) {
  color: var(--muted);
  font-size: 13px;
}

.file-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: none;
  border: 1px solid var(--border-strong);
  border-radius: 13px;
  background: var(--panel-2);
  color: var(--brand-light);
}

.close-button {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
}

.close-button:hover {
  color: var(--text);
  border-color: var(--border-strong);
}

.metadata {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin: 24px 0;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 13px;
  background: var(--panel);
}

.filename-field,
.status-field {
  grid-column: 1 / -1;
}

.metadata dt {
  color: var(--muted);
  font-size: 12px;
  margin-bottom: 6px;
}

.metadata dd {
  margin: 0;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.metadata .filename {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.6;
  user-select: text;
}

.metadata .secondary {
  margin-top: 3px;
  font-size: 12px;
  color: var(--muted);
}

.file-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.completed {
  color: var(--ok);
}

.pending {
  color: var(--warn);
}

.hash-heading {
  margin-bottom: 11px;
}

.hash-heading h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.hash-value {
  display: block;
  width: 100%;
  min-width: 0;
  padding: 12px;
  resize: vertical;
  min-height: 92px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--brand-light);
  font-family: var(--mono);
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  word-break: break-all;
}

.hash-missing,
.hint {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}

.pending-note {
  color: var(--warn);
}

.copy-feedback {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--ok);
}

.copy-feedback svg {
  flex: none;
  margin-top: 2px;
}

.copy-feedback.failed {
  color: var(--bad);
}

.dialog-foot {
  justify-content: flex-end;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

@media (max-width: 420px) {
  .file-details {
    width: calc(100% - 24px);
    max-height: calc(100dvh - 24px);
    padding: 16px;
  }

  .metadata {
    padding: 16px;
    grid-template-columns: minmax(0, 1fr);
    gap: 16px;
  }

  .file-icon {
    display: none;
  }

  .dialog-foot .btn {
    flex: 1;
  }
}
</style>
