<script setup>
import { computed, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { getConfig, login, register } from '../lib/api'
import { setSession } from '../lib/auth'
import { startWorkspace } from '../stores/uploader'

const mode = ref('login')
const username = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const desktop = ref(false)

onMounted(async () => {
  try {
    // config 是公开接口；登录前就能判断是不是桌面版，用来提示首个账号为管理员
    desktop.value = Boolean((await getConfig()).desktop)
  } catch {
    // 连不上时由提交动作给出错误，这里不重复提示
  }
})

const isRegister = computed(() => mode.value === 'register')
const canSubmit = computed(
  () =>
    !loading.value &&
    username.value.trim().length >= 3 &&
    password.value.length >= (isRegister.value ? 8 : 1),
)

function switchMode(next) {
  if (loading.value || mode.value === next) return
  mode.value = next
  error.value = ''
  notice.value = ''
}

async function submit() {
  error.value = ''
  notice.value = ''
  const name = username.value.trim()
  if (name.length < 3) {
    error.value = '用户名至少 3 个字符'
    return
  }
  if (isRegister.value && password.value.length < 8) {
    error.value = '密码至少 8 个字符'
    return
  }
  loading.value = true
  try {
    const res = isRegister.value
      ? await register(name, password.value)
      : await login(name, password.value)
    setSession({ token: res.token, user: res.user })
    password.value = ''
    await startWorkspace()
  } catch (e) {
    if (e.status === 429) {
      error.value = '尝试过于频繁，请稍后再试'
    } else if (e.status === 0) {
      error.value = '连不上服务端：请确认 file-hub 已启动'
    } else if (e.status === 401) {
      error.value = '账号或密码错误'
    } else {
      error.value = e.message || '操作失败，请稍后再试'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="auth-shell">
    <div class="auth-card">
      <div class="auth-head">
        <span class="auth-icon"><BaseIcon name="shield" :size="22" /></span>
        <div>
          <h1>{{ isRegister ? '创建账号' : '登录 file-hub' }}</h1>
          <p>文件按账号隔离；登录后即可继续未完成的上传。</p>
        </div>
      </div>

      <p v-if="desktop" class="desktop-note" data-testid="desktop-note">
        桌面版：第一个注册的账号会成为管理员；关闭浏览器不会退出程序，请使用系统托盘图标退出。
      </p>

      <div class="auth-tabs" role="tablist" aria-label="登录或注册">
        <button
          type="button"
          role="tab"
          :aria-selected="!isRegister"
          :class="{ active: !isRegister }"
          data-testid="auth-login-tab"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="isRegister"
          :class="{ active: isRegister }"
          data-testid="auth-register-tab"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <form class="auth-form" novalidate @submit.prevent="submit">
        <label class="auth-field">
          <span>用户名</span>
          <div class="auth-input">
            <BaseIcon name="user" :size="16" />
            <input
              v-model="username"
              name="username"
              type="text"
              autocomplete="username"
              placeholder="3-32 位字母、数字、_ . -"
              data-testid="auth-username"
              :disabled="loading"
              @input="error = ''"
            />
          </div>
        </label>

        <label class="auth-field">
          <span>密码</span>
          <div class="auth-input">
            <BaseIcon name="lock" :size="16" />
            <input
              v-model="password"
              name="password"
              :type="showPassword ? 'text' : 'password'"
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              :placeholder="isRegister ? '至少 8 个字符' : '输入密码'"
              data-testid="auth-password"
              :disabled="loading"
              @input="error = ''"
            />
            <button
              class="eye-btn"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <BaseIcon :name="showPassword ? 'eye-off' : 'eye'" :size="16" />
            </button>
          </div>
        </label>

        <p v-if="isRegister" class="auth-policy">
          用户名 3-32 位；密码 8-64 个字符。管理员账号由服务端环境变量初始化。
        </p>

        <div v-if="error" class="auth-alert" role="alert" data-testid="auth-error">
          <BaseIcon name="alert" :size="15" />
          <span>{{ error }}</span>
        </div>
        <div v-else-if="notice" class="auth-notice">{{ notice }}</div>

        <button
          class="btn btn-primary auth-submit"
          type="submit"
          :disabled="!canSubmit"
          data-testid="auth-submit"
        >
          <BaseIcon :name="loading ? 'refresh' : 'play'" :size="15" />
          {{ loading ? '处理中…' : isRegister ? '注册并登录' : '登录' }}
        </button>
      </form>

      <div class="auth-foot">
        <span><BaseIcon name="lock" :size="13" /> Token 只保存在本机</span>
        <span>7 天有效 · 支持主动退出</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.auth-shell {
  display: grid;
  place-items: center;
  min-height: min(62vh, 560px);
  padding: 20px 0 40px;
}

.auth-card {
  width: min(460px, 100%);
  padding: 26px 26px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: color-mix(in srgb, var(--panel) 92%, transparent);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(var(--blur));
}

.auth-head {
  display: flex;
  gap: 13px;
  align-items: flex-start;
}

.auth-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 13px;
  border: 1px solid rgba(139, 127, 255, 0.35);
  background: rgba(109, 93, 252, 0.12);
  color: var(--brand-light);
}

.auth-head h1 {
  margin: 1px 0 4px;
  font-size: 20px;
  letter-spacing: -0.02em;
}

.auth-head p {
  margin: 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.55;
}

.desktop-note {
  margin: 14px 0 0;
  padding: 9px 11px;
  border: 1px solid rgba(34, 211, 238, 0.28);
  border-radius: 9px;
  background: rgba(34, 211, 238, 0.08);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.auth-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin: 20px 0 16px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: 11px;
  background: var(--panel-2);
}

.auth-tabs button {
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}

.auth-tabs button.active {
  background: color-mix(in srgb, var(--brand) 20%, var(--panel));
  color: var(--text);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--brand) 35%, transparent);
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.auth-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--muted);
  font-size: 11.5px;
}

.auth-input {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--muted-2);
  transition: border-color 0.18s ease;
}

.auth-input:focus-within {
  border-color: color-mix(in srgb, var(--brand-2) 45%, transparent);
}

.auth-input input {
  flex: 1;
  min-width: 0;
  padding: 11px 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 13px;
  font-family: inherit;
}

.auth-input input::placeholder {
  color: var(--muted-2);
}

.eye-btn {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--muted-2);
  cursor: pointer;
}

.eye-btn:hover {
  background: var(--hover);
  color: var(--text);
}

.auth-policy {
  margin: -2px 0 0;
  color: var(--muted-2);
  font-size: 11px;
  line-height: 1.55;
}

.auth-alert {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 11px;
  border: 1px solid color-mix(in srgb, var(--bad) 40%, transparent);
  border-radius: 9px;
  background: color-mix(in srgb, var(--bad) 9%, transparent);
  color: var(--bad);
  font-size: 12px;
}

.auth-notice {
  color: var(--ok);
  font-size: 12px;
}

.auth-submit {
  width: 100%;
  justify-content: center;
  margin-top: 2px;
  padding: 10px 14px;
}

.auth-submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.auth-foot {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px 12px;
  margin-top: 18px;
  padding-top: 13px;
  border-top: 1px solid var(--border);
  color: var(--muted-2);
  font-size: 10.5px;
}

.auth-foot span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

@media (max-width: 520px) {
  .auth-card {
    padding: 20px 18px 15px;
  }
}
</style>
