<script setup>
import { computed, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { changePassword } from '../lib/api'
import { auth } from '../lib/auth'
import { copyText } from '../lib/clipboard'
import { formatBytes } from '../lib/format'
import { state, toast } from '../stores/uploader'

const emit = defineEmits(['close'])

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const busy = ref(false)
const error = ref('')

const webdavUrl = computed(() => `${window.location.origin}/dav/`)
const quotaText = computed(() => {
  const q = state.stats.quotaBytes
  if (q === undefined || q === null || q < 0) return '不限制'
  return `${formatBytes(state.stats.quotaUsedBytes)} / ${formatBytes(q)}`
})

async function submitPassword() {
  if (busy.value) return
  if (newPassword.value.length < 8) {
    error.value = '新密码至少需要 8 位字符'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await changePassword(currentPassword.value, newPassword.value)
    toast('密码修改成功', 'success')
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
  } catch (e) {
    error.value = e.message || '修改密码失败'
  } finally {
    busy.value = false
  }
}

async function copyDav() {
  const ok = await copyText(webdavUrl.value)
  toast(ok ? 'WebDAV 地址已复制' : '复制失败，请手动复制', ok ? 'success' : 'warn')
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <section
      class="modal-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="account-title"
      data-testid="account-dialog"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="modal-head">
        <div>
          <h2 id="account-title"><BaseIcon name="key" :size="17" /> 账号安全与 WebDAV 挂载</h2>
          <span>{{ auth.user?.username }} · 配额：{{ quotaText }}</span>
        </div>
        <button class="icon-btn" type="button" aria-label="关闭账号设置" @click="emit('close')">
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <div class="modal-body">
        <section class="card">
          <h3><BaseIcon name="hard-drive" :size="15" /> WebDAV 网络驱动器</h3>
          <p class="hint">
            可在 Windows 资源管理器（映射网络驱动器）、macOS Finder、RaiDrive 或 PotPlayer
            中挂载个人网盘，使用当前账号与密码登录。
          </p>
          <div class="dav-row">
            <input :value="webdavUrl" readonly @focus="$event.target.select()" />
            <button class="btn btn-ghost btn-sm" type="button" @click="copyDav">复制地址</button>
          </div>
          <span class="dav-sub"
            >用户名：<code>{{ auth.user?.username }}</code> · 密码：您的登录密码</span
          >
        </section>

        <form class="card" @submit.prevent="submitPassword">
          <h3><BaseIcon name="lock" :size="15" /> 修改登录密码</h3>
          <label class="field">
            <span>当前密码</span>
            <input
              v-model="currentPassword"
              type="password"
              required
              autocomplete="current-password"
            />
          </label>
          <label class="field">
            <span>新密码（8–72 字符）</span>
            <input
              v-model="newPassword"
              type="password"
              required
              minlength="8"
              maxlength="72"
              autocomplete="new-password"
            />
          </label>
          <label class="field">
            <span>确认新密码</span>
            <input v-model="confirmPassword" type="password" required autocomplete="new-password" />
          </label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div class="actions">
            <button class="btn btn-primary btn-sm" type="submit" :disabled="busy">
              {{ busy ? '提交中…' : '更新密码' }}
            </button>
          </div>
        </form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(5, 8, 18, 0.72);
  backdrop-filter: blur(8px);
}

.modal-dialog {
  width: min(520px, 100%);
  max-height: 88vh;
  overflow: auto;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--bg);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.5);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.modal-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 2px;
  font-size: 16px;
}

.modal-head span {
  font-size: 12px;
  color: var(--muted);
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
}

.modal-body {
  display: grid;
  gap: 14px;
  padding: 18px;
}

.card {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
}

.card h3 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 14px;
}

.hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.5;
}

.dav-row {
  display: flex;
  gap: 8px;
}

.dav-row input {
  flex: 1;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  font-family: var(--mono);
  font-size: 12.5px;
}

.dav-sub {
  font-size: 12px;
  color: var(--muted);
}

.field {
  display: grid;
  gap: 5px;
}

.field span {
  font-size: 12px;
  color: var(--muted);
}

.field input {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
}

.error {
  margin: 0;
  color: var(--bad);
  font-size: 12.5px;
}

.actions {
  display: flex;
  justify-content: flex-end;
}
</style>
