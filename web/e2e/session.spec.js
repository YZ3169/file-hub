import { expect, test } from '@playwright/test'
import { gotoWorkspace, injectSession, PASSWORD, registerViaApi } from './helpers'

test('初始化上传挂起时退出并换账号，新账号可以独立上传', async ({ page, request }) => {
  const a = await registerViaApi(request)
  const b = await registerViaApi(request)
  await injectSession(page, a.session)
  await gotoWorkspace(page)

  let release, signalStarted
  const blocked = new Promise((resolve) => {
    release = resolve
  })
  const started = new Promise((resolve) => {
    signalStarted = resolve
  })
  let intercepted = false
  await page.route('**/api/file/init', async (route) => {
    if (!intercepted) {
      intercepted = true
      signalStarted()
      await blocked
    }
    // 第一条请求可能已经被退出动作中断。
    await route.continue().catch(() => {})
  })

  const oldName = `old-user-${Date.now()}.txt`
  const newName = `new-user-${Date.now()}.txt`
  try {
    await page
      .locator('input[type="file"]:not([webkitdirectory])')
      .setInputFiles({ name: oldName, mimeType: 'text/plain', buffer: Buffer.from('account A') })
    await started
    await page.getByTestId('logout-button').click()
    await expect(page.getByRole('heading', { name: '登录 file-hub' })).toBeVisible()
    await page.getByTestId('auth-username').fill(b.username)
    await page.getByTestId('auth-password').fill(PASSWORD)
    await page.getByTestId('auth-submit').click()
    await expect(page.getByTestId('user-menu')).toContainText(b.username)
    await page
      .locator('input[type="file"]:not([webkitdirectory])')
      .setInputFiles({ name: newName, mimeType: 'text/plain', buffer: Buffer.from('account B') })
    await expect(page.locator('tbody tr').filter({ hasText: newName })).toBeVisible()
    release()
    await expect(page.locator('.task-card').filter({ hasText: oldName })).toHaveCount(0)
    const response = await request.get('/api/file/list', {
      headers: { Authorization: `Bearer ${b.session.token}` },
    })
    const files = (await response.json()).data.items
    expect(files.map((file) => file.fileName)).toEqual([newName])
    const localState = await page.evaluate(
      (id) => ({
        pending: localStorage.getItem(`filehub.${id}.pendingTasks.v3`),
        hashes: localStorage.getItem(`filehub.${id}.hashCache.v2`),
      }),
      b.session.user.id,
    )
    expect(JSON.stringify(localState)).not.toContain(oldName)
    await request.delete(`/api/file/${files[0].fileId}`, {
      headers: { Authorization: `Bearer ${b.session.token}` },
    })
  } finally {
    release()
  }
})
