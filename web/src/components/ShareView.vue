<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { ApiError, getPublicConfig, getPublicShareMeta, verifyShareCode } from '../lib/api'
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
const ticketExpired = ref(false)

let ticketTimer = null

const isLocalHost = ['localhost', '127.0.0.1', '::1', '[::1]'].includes(window.location.hostname)
const localOnlyHint = computed(() => desktop.value || isLocalHost)

const errorTitle = computed(() => {
  const titleByCode = {
    42001: '链接无效',
    42002: '分享已过期',
    42004: '提取已锁定',
  }
  return titleByCode[loadError.value?.code] || '无法打开分享'
})

const lockedUntil = computed(() => meta.value?.lockedUntil || null)

async function loadMeta() {
  loading.value = true
  loadError.value = null
  try {
    meta.value = await getPublicShareMeta(props.token)
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

function scheduleTicketExpiry() {
  if (ticketTimer) clearTimeout(ticketTimer)
  const at = new Date(result.value?.ticketExpiresAt || '')
  const delay = Number.isNaN(at.getTime()) ? 5 * 60_000 : at.getTime() - Date.now()
  ticketTimer = setTimeout(
    () => {
      ticketExpired.value = true
    },
    Math.max(0, Math.min(delay, 10 * 60_000)),
  )
}

async function submit() {
  if (busy.value || code.value.length !== 4 || lockedUntil.value) return
  busy.value = true
  verifyError.value = ''
  try {
    result.value = await verifyShareCode(props.token, code.value)
    ticketExpired.value = false
    scheduleTicketExpiry()
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

function reset() {
  result.value = null
  code.value = ''
  verifyError.value = ''
  if (ticketTimer) clearTimeout(ticketTimer)
}

function download() {
  if (!result.value) return
  const a = document.createElement('a')
  a.href = result.value.downloadUrl
  a.download = result.value.fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
}

onMounted(async () => {
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
  if (ticketTimer) clearTimeout(ticketTimer)
})
</script>

<template>
  <div class="share-view">
    <section class="share-card" data-testid="share-view">
      <div class="card-brand">
        <span class="brand-dot"></span>
        <strong>file-hub</strong>
        <span>文件提取</span>
      </div>

      <div v-if="loading" class="state-box">
        <span class="spinner"></span>
        正在读取分享…
      </div>

      <div v-else-if="loadError" class="state-box error" role="alert">
        <BaseIcon name="alert" :size="22" />
        <strong>{{ errorTitle }}</strong>
        <span>{{ loadError.message }}</span>
        <button v-if="loadError.code === 42004" class="btn btn-ghost btn-sm" @click="loadMeta">
          重新检查
        </button>
      </div>

      <template v-else>
        <p class="lead">分享者设置了提取码，输入后即可下载到本机。</p>

        <p v-if="lockedUntil" class="locked" role="alert">
          <BaseIcon name="lock" :size="15" />
          <span
            >输错次数过多，已锁定到
            {{ formatDate(lockedUntil) }}；请稍后再试或联系分享者重置提取码。</span
          >
        </p>

        <form v-if="!result" class="code-form" @submit.prevent="submit">
          <label class="code-label" for="share-code-input">提取码</label>
          <input
            id="share-code-input"
            class="code-input"
            type="text"
            inputmode="text"
            autocomplete="one-time-code"
            autocapitalize="characters"
            spellcheck="false"
            maxlength="4"
            placeholder="4 位字母或数字"
            :value="code"
            :disabled="busy || Boolean(lockedUntil)"
            data-testid="share-code-input"
            @input="onCodeInput"
          />
          <p v-if="verifyError" class="error" role="alert">{{ verifyError }}</p>
          <button
            class="btn btn-primary"
            type="submit"
            :disabled="busy || code.length !== 4 || Boolean(lockedUntil)"
            data-testid="share-submit"
          >
            <BaseIcon name="download" :size="15" />
            {{ busy ? '校验中…' : '提取文件' }}
          </button>
        </form>

        <div v-else class="file-result" data-testid="share-result">
          <div class="file-icon"><BaseIcon name="file" :size="22" /></div>
          <div class="file-copy">
            <strong :title="result.fileName">{{ result.fileName }}</strong>
            <span>{{ formatBytes(result.fileSize) }}</span>
          </div>
          <button class="btn btn-primary btn-sm" type="button" @click="download">
            <BaseIcon name="download" :size="15" />
            下载
          </button>
          <p v-if="ticketExpired" class="hint warn">
            下载凭证已过期，重新输入提取码即可再取一次。
            <button class="link-btn" type="button" @click="reset">重新校验</button>
          </p>
          <p v-else class="hint">
            下载凭证 5 分钟内有效；链接失效后重新输入提取码即可。
            <button class="link-btn" type="button" @click="reset">换一次</button>
          </p>
        </div>
      </template>

      <p v-if="localOnlyHint" class="hint local">
        <BaseIcon name="server" :size="13" />
        这是本机（127.0.0.1）上的分享，换一台电脑打不开；跨机器使用请部署服务器版。
      </p>

      <footer class="card-foot">
        <a class="link-btn" href="#/">返回 file-hub 首页</a>
      </footer>
    </section>
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
  width: min(460px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px 24px 18px;
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
  font-size: 11.5px;
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
  font-size: 12.5px;
  line-height: 1.7;
}

.state-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 30px 12px;
  color: var(--muted);
  font-size: 12.5px;
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
  font-size: 12px;
  line-height: 1.6;
}

.code-form {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.code-label {
  color: var(--muted);
  font-size: 11.5px;
}

.code-input {
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
  font-size: 12px;
}

.file-result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 11px;
  padding: 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel-2);
}

.file-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
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
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.file-copy span {
  color: var(--muted);
  font-size: 11.5px;
}

.hint {
  width: 100%;
  margin: 0;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
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
  font-size: 11.5px;
}
</style>
