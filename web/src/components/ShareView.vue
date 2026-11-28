<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import FilePreviewDialog from './FilePreviewDialog.vue'
import {
  ApiError,
  getPublicConfig,
  getPublicShareMeta,
  saveShareToMySpace,
  verifyShareCode,
} from '../lib/api'
import { auth } from '../lib/auth'
import { formatBytes, formatDate } from '../lib/format'

const props = defineProps({
  token: { type: String, required: true },
})

const loading = ref(true)
const meta = ref(null)
const loadError = ref(null)
const desktop = ref(false)
const code = ref('')
const busy = ref(false)
const verifyError = ref('')
const result = ref(null)
const previewOpen = ref(false)
const saving = ref(false)
const savedMessage = ref('')
const saveError = ref('')
const now = ref(Date.now())
const ticketDeadline = ref(0)
const downloadRequested = ref(false)
const codeInput = ref(null)
const resultHeading = ref(null)

let clockTimer = null
let disposed = false

const ticketExpired = computed(() => Boolean(result.value) && now.value >= ticketDeadline.value)
const needCode = computed(() => meta.value?.requireCode !== false)

const isLocalHost = ['localhost', '127.0.0.1', '::1', '[::1]'].includes(window.location.hostname)
const localOnlyHint = computed(() => desktop.value || isLocalHost)

const errorTitle = computed(() => {
  const titleByCode = {
    42001: '链接无效',
    42002: '分享已过期',
    42004: '提取已锁定',
    42009: '次数已达上限',
  }
  return titleByCode[loadError.value?.code] || '无法打开分享'
})

const lockedUntil = computed(() => {
  const value = meta.value?.lockedUntil
  if (!value) return null
  const at = new Date(value).getTime()
  return Number.isFinite(at) && at > now.value ? value : null
})
const locked = computed(() =>
  Boolean(lockedUntil.value || (meta.value?.locked && !meta.value?.lockedUntil)),
)
const canRetry = computed(() => ![42001, 42002].includes(loadError.value?.code))

async function loadMeta() {
  loading.value = true
  loadError.value = null
  try {
    meta.value = await getPublicShareMeta(props.token)
    now.value = Date.now()
  } catch (e) {
    loadError.value = {
      code: e instanceof ApiError ? e.code : 0,
      message: e.message || '加载分享失败',
    }
  } finally {
    loading.value = false
  }
}

function onCodeInput(event) {
  const cleaned = event.target.value
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, '')
    .slice(0, 4)
  code.value = cleaned
  event.target.value = cleaned
  verifyError.value = ''
}

async function submit() {
  now.value = Date.now()
  if (busy.value || locked.value) return
  if (needCode.value && code.value.length !== 4) return
  busy.value = true
  verifyError.value = ''
  try {
    const verified = await verifyShareCode(props.token, needCode.value ? code.value : '')
    if (disposed) return
    now.value = Date.now()
    const deadline = new Date(verified.ticketExpiresAt || '').getTime()
    ticketDeadline.value = Number.isFinite(deadline) ? deadline : now.value + 5 * 60_000
    result.value = verified
    downloadRequested.value = false
    savedMessage.value = ''
    saveError.value = ''
    await nextTick()
    resultHeading.value?.focus()
  } catch (e) {
    verifyError.value = e.message
    if (e instanceof ApiError && e.code === 42004) {
      // 锁定状态可能正是这次请求触发的，刷新一下 meta 才能显示锁到几点
      await loadMeta().catch(() => {})
    }
  } finally {
    busy.value = false
  }
}

async function reset() {
  result.value = null
  previewOpen.value = false
  savedMessage.value = ''
  saveError.value = ''
  code.value = ''
  verifyError.value = ''
  downloadRequested.value = false
  await nextTick()
  codeInput.value?.focus()
}

function download() {
  // Background tabs may suspend timers; check the actual time again on every click.
  now.value = Date.now()
  if (!result.value || ticketExpired.value) return
  const a = document.createElement('a')
  a.href = result.value.downloadUrl
  a.download = result.value.fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  downloadRequested.value = true
}

function extractTicket(url) {
  if (!url) return null
  const idx = url.indexOf('?')
  if (idx < 0) return null
  return new URLSearchParams(url.slice(idx + 1)).get('ticket')
}

async function saveToDrive() {
  if (saving.value || !result.value || !auth.token) return
  saving.value = true
  saveError.value = ''
  savedMessage.value = ''
  try {
    const ticket = extractTicket(result.value.downloadUrl)
    const saved = await saveShareToMySpace(props.token, {
      code: needCode.value ? code.value : null,
      ticket,
    })
    if (disposed) return
    savedMessage.value = `已保存到你的网盘：${saved.relativePath || saved.fileName}`
  } catch (e) {
    if (!disposed) saveError.value = e.message || '保存失败'
  } finally {
    if (!disposed) saving.value = false
  }
}

onMounted(async () => {
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
  getPublicConfig()
    .then((cfg) => {
      desktop.value = cfg?.desktop === true
    })
    .catch(() => {
      // 拿不到配置不影响提取，只是少一个本机提示
    })
  await loadMeta()
})

onBeforeUnmount(() => {
  disposed = true
  clearInterval(clockTimer)
})
</script>

<template>
  <div class="share-view">
    <section class="share-card" data-testid="share-view" :aria-busy="loading || busy">
      <div class="card-brand">
        <span class="brand-dot"></span>
        <strong>file-hub</strong>
        <span>文件提取</span>
      </div>

      <div v-if="loading" class="state-box" role="status">
        <span class="spinner" aria-hidden="true"></span>
        正在读取分享…
      </div>

      <div v-else-if="loadError" class="state-box error" role="alert">
        <BaseIcon name="alert" :size="22" />
        <strong>{{ errorTitle }}</strong>
        <span>{{ loadError.message }}</span>
        <button v-if="canRetry" class="btn btn-ghost" type="button" @click="loadMeta">
          <BaseIcon name="refresh" :size="16" />
          重试加载
        </button>
      </div>

      <template v-else>
        <div class="share-heading">
          <span class="heading-icon"
            ><BaseIcon :name="result ? 'check' : 'lock'" :size="24"
          /></span>
          <h1 v-if="!result">提取你的文件</h1>
          <h1 v-else ref="resultHeading" tabindex="-1">文件已准备好</h1>
          <p class="lead">
            {{
              result
                ? '核对文件信息，然后预览、下载或保存到你的网盘。'
                : needCode
                  ? '输入分享者提供的 4 位提取码，即可查看并下载文件。'
                  : '该分享免提取码，点击下方按钮即可查看或下载。'
            }}
          </p>
        </div>

        <div v-if="locked" class="locked" role="alert">
          <BaseIcon name="lock" :size="15" />
          <div>
            <p>
              输错次数过多，暂时无法提取。<template v-if="lockedUntil"
                >请在 {{ formatDate(lockedUntil) }} 后重试。</template
              >
            </p>
            <button class="link-btn" type="button" @click="loadMeta">重新检查锁定状态</button>
          </div>
        </div>

        <form v-if="!result" class="code-form" @submit.prevent="submit">
          <template v-if="needCode">
            <label class="code-label" for="share-code-input">提取码</label>
            <input
              id="share-code-input"
              ref="codeInput"
              class="code-input"
              type="text"
              inputmode="text"
              autocomplete="one-time-code"
              autocapitalize="characters"
              spellcheck="false"
              maxlength="4"
              placeholder="4 位字母或数字"
              :value="code"
              :disabled="busy || locked"
              :aria-invalid="Boolean(verifyError)"
              :aria-describedby="verifyError ? 'share-code-error' : 'share-code-hint'"
              data-testid="share-code-input"
              @input="onCodeInput"
            />
            <p id="share-code-hint" class="hint">字母不区分大小写，无需登录。</p>
          </template>
          <p v-else class="hint" data-testid="share-open-hint">公开分享链接，无需提取码。</p>

          <p v-if="verifyError" id="share-code-error" class="error" role="alert">
            {{ verifyError }}
          </p>
          <button
            class="btn btn-primary"
            type="submit"
            :disabled="busy || (needCode && code.length !== 4) || locked"
            data-testid="share-submit"
          >
            <BaseIcon name="download" :size="15" />
            {{ busy ? '校验中…' : '提取文件' }}
          </button>
        </form>

        <div v-else class="file-result" data-testid="share-result">
          <div class="file-details">
            <div class="file-icon"><BaseIcon name="file" :size="28" /></div>
            <div class="file-copy">
              <strong>{{ result.fileName }}</strong>
              <span>{{ formatBytes(result.fileSize) }}</span>
            </div>
          </div>
          <div class="result-actions">
            <button
              class="btn btn-primary download-button"
              type="button"
              :disabled="ticketExpired"
              data-testid="share-download"
              @click="download"
            >
              <BaseIcon name="download" :size="15" />
              {{ ticketExpired ? '下载凭证已过期' : downloadRequested ? '再次下载' : '下载' }}
            </button>
            <button
              class="btn btn-ghost"
              type="button"
              :disabled="ticketExpired"
              data-testid="share-preview"
              @click="previewOpen = true"
            >
              <BaseIcon name="eye" :size="15" />
              预览
            </button>
            <button
              v-if="auth.token"
              class="btn btn-ghost"
              type="button"
              :disabled="saving"
              data-testid="share-save"
              @click="saveToDrive"
            >
              <BaseIcon name="folder-plus" :size="15" />
              {{ saving ? '保存中…' : '存到网盘' }}
            </button>
          </div>
          <p v-if="!auth.token" class="hint">
            已有账号？<a class="link-btn" href="#/">登录后可一键转存到自己的网盘</a>
          </p>
          <p v-if="savedMessage" class="download-feedback" role="status" data-testid="share-saved">
            <BaseIcon name="check" :size="16" />
            {{ savedMessage }}
          </p>
          <p v-if="saveError" class="error" role="alert">{{ saveError }}</p>
          <p v-if="downloadRequested" class="download-feedback" role="status">
            <BaseIcon name="check" :size="16" />
            已请求下载，请在浏览器或桌面窗口的下载列表中查看进度。
          </p>
          <p v-if="ticketExpired" class="hint warn" role="status">
            下载凭证已过期，请重新校验提取码后下载。
            <button class="link-btn" type="button" @click="reset">重新校验</button>
          </p>
          <p v-else class="hint">
            <template v-if="result.ticketExpiresAt"
              >下载凭证有效至 {{ formatDate(result.ticketExpiresAt) }}。</template
            >
            <template v-else>下载凭证 5 分钟内有效。</template>
            <button class="link-btn" type="button" @click="reset">重新校验</button>
          </p>
        </div>
        <p v-if="meta.expiresAt || meta.maxDownloads" class="hint share-expiry">
          <BaseIcon name="clock" :size="14" />
          <template v-if="meta.expiresAt">分享有效至 {{ formatDate(meta.expiresAt) }}</template>
          <template v-else>长期有效</template>
          <template v-if="meta.maxDownloads">
            · 剩余可提取 {{ Math.max(0, meta.maxDownloads - (meta.downloadCount || 0)) }} /
            {{ meta.maxDownloads }} 次
          </template>
        </p>
      </template>

      <p v-if="localOnlyHint" class="hint local">
        <BaseIcon name="server" :size="13" />
        这是本机（127.0.0.1）上的分享，换一台电脑打不开；跨机器使用请部署服务器版。
      </p>

      <footer class="card-foot">
        <a class="link-btn" href="#/">返回 file-hub 首页</a>
      </footer>
    </section>

    <FilePreviewDialog
      v-if="previewOpen && result"
      :file="result"
      :direct-url="result.downloadUrl"
      @close="previewOpen = false"
      @download="download"
    />
  </div>
</template>

<style scoped>
.share-view {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 220px);
  padding: 12px 4px 40px;
}

.share-card {
  width: min(560px, 100%);
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 30px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(var(--blur));
}

.card-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.card-brand strong {
  font-size: 14.5px;
  letter-spacing: -0.01em;
}

.card-brand span:last-child {
  color: var(--muted);
  font-size: 13px;
}

.share-heading {
  text-align: center;
}

.heading-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  border: 1px solid color-mix(in srgb, var(--brand-light) 30%, transparent);
  border-radius: 18px;
  background: color-mix(in srgb, var(--brand) 12%, transparent);
  color: var(--brand-light);
}

.share-heading h1 {
  margin: 0 0 10px;
  font-size: clamp(24px, 4vw, 30px);
  letter-spacing: -0.03em;
}

.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-light), var(--brand-2));
  box-shadow: 0 0 10px color-mix(in srgb, var(--brand-2) 60%, transparent);
}

.lead {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}

.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 30px 12px;
  color: var(--muted);
  font-size: 14px;
  text-align: center;
}

.state-box.error {
  color: var(--bad);
}

.state-box.error span {
  color: var(--muted);
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--brand-light);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.locked {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--warn) 40%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--warn) 10%, var(--panel));
  color: var(--text);
  font-size: 13px;
  line-height: 1.6;
}

.locked p {
  margin: 0 0 6px;
}

.locked > svg {
  flex: none;
  margin-top: 3px;
}

.code-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.code-form > .btn,
.download-button {
  min-height: 46px;
  font-size: 14px;
}

.code-label {
  color: var(--muted);
  font-size: 14px;
}

.code-input {
  width: 100%;
  min-width: 0;
  padding: 11px 14px;
  border: 1px solid var(--border);
  border-radius: 11px;
  background: var(--panel-2);
  color: var(--text);
  font-family: var(--mono);
  font-size: 21px;
  letter-spacing: 0.4em;
  text-align: center;
  text-transform: uppercase;
}

.code-input::placeholder {
  font-family: inherit;
  font-size: 16px;
  letter-spacing: 0.04em;
}

.code-input:focus {
  outline: 2px solid color-mix(in srgb, var(--brand-2) 55%, transparent);
  outline-offset: 1px;
}

.code-input:disabled {
  opacity: 0.6;
}

.error {
  margin: 0;
  padding: 9px 12px;
  border: 1px solid color-mix(in srgb, var(--bad) 40%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, var(--bad) 10%, var(--panel));
  color: var(--bad);
  font-size: 14px;
}

.file-result {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel-2);
}

.file-details {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.file-icon {
  display: grid;
  place-items: center;
  width: 54px;
  height: 60px;
  flex: none;
  border-radius: 11px;
  background: color-mix(in srgb, var(--brand-2) 12%, transparent);
  color: var(--brand-2);
}

.file-copy {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.file-copy strong {
  overflow-wrap: anywhere;
  font-size: 16px;
  line-height: 1.5;
}

.file-copy span {
  color: var(--muted);
  font-size: 14px;
}

.hint {
  width: 100%;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.download-feedback {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  color: var(--ok);
  font-size: 13px;
  line-height: 1.6;
}

.download-feedback svg,
.hint.local svg {
  flex: none;
  margin-top: 3px;
}

.share-expiry {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.hint.local {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding-top: 2px;
}

.hint.warn {
  color: var(--warn);
}

.link-btn {
  border: 0;
  background: none;
  color: var(--brand-2);
  font-size: inherit;
  font-family: inherit;
  text-decoration: none;
  cursor: pointer;
  padding: 0 2px;
}

.link-btn:hover {
  text-decoration: underline;
}

.card-foot {
  display: flex;
  justify-content: center;
  padding-top: 12px;
  border-top: 1px solid var(--border);
  color: var(--muted);
  font-size: 13px;
}

@media (max-width: 480px) {
  .share-view {
    padding: 8px 0 24px;
  }

  .share-card {
    padding: 22px 18px;
    gap: 20px;
  }

  .file-result {
    padding: 16px;
  }
}

.result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.result-actions .download-button {
  flex: 1 1 auto;
}
</style>
