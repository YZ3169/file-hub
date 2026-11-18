import { expect, test } from '@playwright/test'
import { PASSWORD, uniqueName } from './helpers'

test.describe('登录与注册', () => {
  test('注册后刷新仍保持登录，退出后可重新登录', async ({ page }) => {
    const username = uniqueName('auth')

    await page.goto('/')
    await expect(page.getByRole('heading', { name: '登录 file-hub' })).toBeVisible()

    await page.getByTestId('auth-register-tab').click()
    await expect(page.getByRole('heading', { name: '创建账号' })).toBeVisible()
    await page.getByTestId('auth-username').fill(username)
    await page.getByTestId('auth-password').fill(PASSWORD)
    await page.getByTestId('auth-submit').click()

    await expect(page.getByRole('heading', { name: '传输队列' })).toBeVisible()
    await expect(page.getByTestId('user-menu')).toContainText(username)

    // 刷新：token 在 localStorage，会话在 MySQL，应当自动恢复
    await page.reload()
    await expect(page.getByRole('heading', { name: '传输队列' })).toBeVisible()
    await expect(page.getByTestId('user-menu')).toContainText(username)

    await page.getByTestId('logout-button').click()
    await expect(page.getByRole('heading', { name: '登录 file-hub' })).toBeVisible()

    // 退出后旧 token 已撤销；重新登录仍然正常
    await page.getByTestId('auth-username').fill(username)
    await page.getByTestId('auth-password').fill(PASSWORD)
    await page.getByTestId('auth-submit').click()
    await expect(page.getByRole('heading', { name: '传输队列' })).toBeVisible()
  })

  test('密码错误时提示账号或密码错误', async ({ page }) => {
    const username = uniqueName('badpw')
    await page.goto('/')

    await page.getByTestId('auth-register-tab').click()
    await page.getByTestId('auth-username').fill(username)
    await page.getByTestId('auth-password').fill(PASSWORD)
    await page.getByTestId('auth-submit').click()
    await expect(page.getByRole('heading', { name: '传输队列' })).toBeVisible()

    await page.getByTestId('logout-button').click()
    await expect(page.getByRole('heading', { name: '登录 file-hub' })).toBeVisible()

    await page.getByTestId('auth-username').fill(username)
    await page.getByTestId('auth-password').fill('wrong-password')
    await page.getByTestId('auth-submit').click()

    await expect(page.getByTestId('auth-error')).toContainText('账号或密码错误')
    await expect(page.getByRole('heading', { name: '登录 file-hub' })).toBeVisible()
  })
})
