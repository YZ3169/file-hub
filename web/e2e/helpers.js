import { expect } from '@playwright/test'

export const PASSWORD = 'pw-test-pass-123'

export function uniqueName(prefix = 'pw') {
  return `${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

/** 通过 API 注册，返回 { token, user }；UI 用例只需注入会话即可跳过登录步骤 */
export async function registerViaApi(request, username = uniqueName()) {
  const res = await request.post('/api/auth/register', {
    data: { username, password: PASSWORD },
  })
  expect(res.ok(), `register ${username} -> HTTP ${res.status()}`).toBeTruthy()
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return { username, session: body.data }
}

export async function loginViaApi(request, username, password = PASSWORD) {
  const res = await request.post('/api/auth/login', { data: { username, password } })
  expect(res.ok(), `login ${username} -> HTTP ${res.status()}`).toBeTruthy()
  const body = await res.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data
}

/** 在页面加载前写入 localStorage，等价于用户上次登录后刷新页面 */
export async function injectSession(page, session) {
  await page.addInitScript(({ token, user }) => {
    localStorage.setItem('filehub.token.v1', token)
    localStorage.setItem('filehub.user.v1', JSON.stringify(user))
  }, session)
}

export async function gotoWorkspace(page) {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '传输队列' })).toBeVisible()
}
