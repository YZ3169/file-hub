import { reactive } from 'vue'

const TOKEN_KEY = 'filehub.token.v1'
const USER_KEY = 'filehub.user.v1'

function readToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || null
  } catch {
    return null
  }
}

function readUser() {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

/**
 * 认证状态。刻意不 import api.js，避免 api 与 store 循环依赖；
 * 401 的通知采用订阅模式，由 api.js 在收到 401 时触发。
 */
export const auth = reactive({
  token: readToken(),
  user: readUser(),
  ready: false,
})

const unauthorizedListeners = new Set()

export function getToken() {
  return auth.token
}

export function currentUserId() {
  return auth.user?.id ?? null
}

export function setSession({ token, user }) {
  auth.token = token
  auth.user = user
  try {
    localStorage.setItem(TOKEN_KEY, token)
    if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
  } catch {
    // 隐私模式：本次会话仍可用，刷新后需要重新登录
  }
}

/**
 * 清除本地会话。notify=false 用于用户主动退出，避免再弹「登录已过期」。
 */
export function clearSession({ notify = true } = {}) {
  const hadSession = Boolean(auth.token)
  auth.token = null
  auth.user = null
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  } catch {
    // ignore
  }
  if (hadSession && notify) {
    for (const fn of unauthorizedListeners) {
      try {
        fn()
      } catch {
        // 订阅方异常不能影响请求流程
      }
    }
  }
}

/** 401 订阅；返回取消订阅函数 */
export function onUnauthorized(fn) {
  unauthorizedListeners.add(fn)
  return () => unauthorizedListeners.delete(fn)
}
