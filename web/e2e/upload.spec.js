import { expect, test } from '@playwright/test'
import { gotoWorkspace, injectSession, registerViaApi } from './helpers'

test.describe('上传工作台', () => {
  test('上传小文件 → 列表出现 → 搜索 → 下载 → 删除', async ({ page, request }) => {
    const { session } = await registerViaApi(request, undefined)
    await injectSession(page, session)
    await gotoWorkspace(page)

    const fileName = `pw-upload-${Date.now()}.txt`
    const content = `file-hub playwright upload ${Date.now()}\n`
    await page.locator('input[type="file"]').setInputFiles({
      name: fileName,
      mimeType: 'text/plain',
      buffer: Buffer.from(content),
    })

    // 任务卡先出现，完成后文件列表刷新
    await expect(page.locator('.task-card').filter({ hasText: fileName })).toBeVisible()
    await expect(page.getByRole('status')).toContainText('上传完成', { timeout: 30_000 })

    const row = page.locator('tbody tr').filter({ hasText: fileName })
    await expect(row).toBeVisible()
    await expect(row).toContainText('已完成')

    // 搜索：关键字命中后仍在列表里
    await page.getByLabel('搜索文件').fill(fileName)
    await page.getByRole('button', { name: '搜索', exact: true }).click()
    await expect(page.locator('tbody tr').filter({ hasText: fileName })).toBeVisible()

    // 下载走短期票据；浏览器应拿到同名文件
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: `下载 ${fileName}` }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe(fileName)

    // 删除需要二次确认
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: `删除 ${fileName}` }).click()
    await expect(page.locator('tbody tr').filter({ hasText: fileName })).toHaveCount(0)
  })
})
