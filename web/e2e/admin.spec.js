import { expect, test } from '@playwright/test'
import { gotoWorkspace, injectSession, loginViaApi, registerViaApi } from './helpers'

test.describe('管理员入口', () => {
  test('普通用户看不到完整性入口', async ({ page, request }) => {
    const { session } = await registerViaApi(request, undefined)
    await injectSession(page, session)
    await gotoWorkspace(page)

    await expect(page.getByTestId('user-menu')).toContainText('普通用户')
    await expect(page.getByRole('button', { name: '完整性' })).toHaveCount(0)
  })

  test('管理员登录后显示管理员身份', async ({ page, request }) => {
    const password = process.env.FILE_HUB_ADMIN_PASSWORD
    test.skip(!password, '未设置 FILE_HUB_ADMIN_PASSWORD，跳过管理员用例')

    const username = process.env.FILE_HUB_ADMIN_USER || 'admin'
    const session = await loginViaApi(request, username, password)
    await injectSession(page, session)
    await gotoWorkspace(page)

    await expect(page.getByTestId('user-menu')).toContainText('管理员')
  })

  test('管理员可打开完整性扫描面板', async ({ page, request }) => {
    const password = process.env.FILE_HUB_ADMIN_PASSWORD
    test.skip(!password, '未设置 FILE_HUB_ADMIN_PASSWORD，跳过管理员用例')

    const username = process.env.FILE_HUB_ADMIN_USER || 'admin'
    const session = await loginViaApi(request, username, password)
    await injectSession(page, session)
    await gotoWorkspace(page)

    await page.getByRole('button', { name: '完整性' }).click()
    await expect(page.getByRole('heading', { name: '完整性扫描' })).toBeVisible()
    // 面板打开即自动浅扫描；没有损坏文件时展示通过状态
    await expect(page.getByTestId('integrity-summary')).toBeVisible()
  })
})
