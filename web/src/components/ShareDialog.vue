<script setup>
import { computed, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { createShare, resetShareCode } from '../lib/api'
import { buildShareUrl, copyText } from '../lib/clipboard'
import { state, toast } from '../stores/uploader'

const props = defineProps({
  file: { type: Object, required: true },
})

const emit = defineEmits(['close'])

const EXPIRY_OPTIONS = [
  { value: 1, label: '1 天' },
  { value: 7, label: '7 天' },
  { value: 30, label: '30 天' },
  { value: 0, label: '永久有效' },
]

const expiry = ref(7)
const share = ref(null)
const busy = ref(false)
const error = ref('')

const isDesktop = computed(() => state.config?.desktop === true)
const link = computed(() => (share.value ? buildShareUrl(share.value.token) : ''))

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    share.value = await createShare(props.file.fileId, expiry.value)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function regenerate() {
  if (busy.value || !share.value) return
  busy.value = true
  error.value = ''
  try {
    const resp = await resetShareCode(share.value.token)
    share.value = { ...resp, token: resp.token || share.value.token }
    toast('已更换提取码，旧提取码立即失效', 'success')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function copy(text, label) {
  const copied = await copyText(text)
  toast(copied ? `${label}已复制` : '复制失败，请手动选中文本复制', copied ? 'success' : 'warn')
}

function copyAll() {
  copy(`${props.file.fileName}\n链接：${link.value}\n提取码：${share.value.code}`, '分享信息')
}
</script>

<template>
  <div class="share-backdrop" @click.self="emit('close')">
    <section
      class="share-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="share-title"
      data-testid="share-dialog"
    >
      <header class="dialog-head">
        <div>
          <h2 id="share-title">分享文件</h2>
          <span>{{ file.fileName }}</span>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭分享弹窗" @click="emit('close')">
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <p v-if="isDesktop" class="desktop-warning">
        <BaseIcon name="alert" :size="15" />
        <span>
          桌面版的链接是
          <code>127.0.0.1</code>，只能在本机打开。要让别人也能提取，请部署服务器版（Docker）。
        </span>
      </p>

      <template v-if="!share">
        <div class="expiry-block">
          <span class="block-label">有效期</span>
          <div class="expiry-options">
            <button
              v-for="option in EXPIRY_OPTIONS"
              :key="option.value"
              class="choice"
              type="button"
              :class="{ active: expiry === option.value }"
              @click="expiry = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <p class="hint">
          生成后把「链接 + 提取码」发给对方，对方无需注册登录即可下载；提取码只显示这一次。
        </p>

        <p v-if="error" class="error" role="alert">{{ error }}</p>

        <footer class="dialog-foot">
          <button class="btn btn-ghost btn-sm" type="button" @click="emit('close')">取消</button>
          <button
            class="btn btn-primary btn-sm"
            type="button"
            data-testid="share-create"
            :disabled="busy"
            @click="submit"
          >
            <BaseIcon name="link" :size="14" />
            {{ busy ? '生成中…' : '生成分享链接' }}
          </button>
        </footer>
      </template>

      <template v-else>
        <div class="result">
          <label class="result-row">
            <span>链接</span>
            <input
              :value="link"
              readonly
              data-testid="share-link"
              @focus="$event.target.select()"
            />
            <button class="btn btn-ghost btn-sm" type="button" @click="copy(link, '链接')">
              复制
            </button>
          </label>

          <div class="result-row">
            <span>提取码</span>
            <strong class="code" data-testid="share-code">{{ share.code }}</strong>
            <button class="btn btn-ghost btn-sm" type="button" @click="copy(share.code, '提取码')">
              复制
            </button>
          </div>

          <p class="hint">
            <BaseIcon name="lock" :size="13" />
            提取码只显示这一次，请立即保存；忘了只能「换一个提取码」。
          </p>
          <p class="hint">
            有效期：{{
              share.expiresAt ? share.expiresAt.replace('T', ' ').slice(0, 16) : '永久有效'
            }}
          </p>
        </div>

        <p v-if="error" class="error" role="alert">{{ error }}</p>

        <footer class="dialog-foot">
          <button class="btn btn-ghost btn-sm" type="button" :disabled="busy" @click="regenerate">
            <BaseIcon name="refresh" :size="14" />
            换一个提取码
          </button>
          <span class="spacer"></span>
          <button class="btn btn-ghost btn-sm" type="button" @click="copyAll">复制全部</button>
          <button class="btn btn-primary btn-sm" type="button" @click="emit('close')">完成</button>
        </footer>
      </template>
    </section>
  </div>
</template>

<style scoped>
.share-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 10vh 18px 18px;
  background: rgba(5, 6, 12, 0.62);
  backdrop-filter: blur(6px);
  overflow-y: auto;
}

.share-dialog {
  width: min(520px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px 22px 18px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.45);
}

.dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.dialog-head h2 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 650;
}

.dialog-head span {
  display: block;
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.desktop-warning {
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

.desktop-warning code {
  font-family: var(--mono);
}

.expiry-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.block-label {
  color: var(--muted);
  font-size: 12px;
}

.expiry-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.choice {
  padding: 7px 13px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  color: var(--muted);
  font-size: 12.5px;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    color 0.18s ease,
    background 0.18s ease;
}

.choice:hover {
  color: var(--text);
}

.choice.active {
  border-color: color-mix(in srgb, var(--brand-2) 55%, transparent);
  background: color-mix(in srgb, var(--brand-2) 14%, var(--panel-2));
  color: var(--text);
}

.hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
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

.result {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.result-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result-row > span {
  width: 48px;
  flex: none;
  color: var(--muted);
  font-size: 12px;
}

.result-row input {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  color: var(--text);
  font-family: var(--mono);
  font-size: 12px;
}

.code {
  flex: 1;
  padding: 8px 12px;
  border: 1px dashed color-mix(in srgb, var(--brand-2) 50%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, var(--brand-2) 10%, var(--panel-2));
  font-family: var(--mono);
  font-size: 20px;
  letter-spacing: 0.32em;
  text-align: center;
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
