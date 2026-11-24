import { expect, test } from '@playwright/test'
import fs from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { gotoWorkspace, injectSession, registerViaApi } from './helpers'

test.describe('上传工作台', () => {
  test('上传小文件 → 列表出现 → 搜索 → 下载 → 删除', async ({ page, request }) => {
    const { session } = await registerViaApi(request, undefined)
    await injectSession(page, session)
    await gotoWorkspace(page)

    const fileName = `pw-upload-${Date.now()}.txt`
    const content = `file-hub playwright upload ${Date.now()}\n`
    await page.locator('input[type="file"]:not([webkitdirectory])').setInputFiles({
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

    // 调整每页数量仍保留搜索；详情中的完整摘要应与实际上传内容一致。
    await page.getByLabel('每页显示数量').selectOption('20')
    await expect(page.getByLabel('每页显示数量')).toHaveValue('20')
    await expect(page.getByLabel('搜索文件')).toHaveValue(fileName)
    await page.getByRole('button', { name: `查看 ${fileName} 的详情` }).click()
    const details = page.getByRole('dialog', { name: '文件详情' })
    await expect(details).toBeVisible()
    await expect(details.getByLabel('完整 SHA-256')).toHaveValue(
      createHash('sha256').update(content).digest('hex'),
    )
    await expect(details.getByTestId('file-details-bytes')).toHaveText(
      `${Buffer.byteLength(content)} 字节`,
    )
    const detailsDownloadPromise = page.waitForEvent('download')
    await details.getByRole('button', { name: '下载文件' }).click()
    const detailsDownload = await detailsDownloadPromise
    expect(detailsDownload.suggestedFilename()).toBe(fileName)
    expect(await fs.readFile(await detailsDownload.path(), 'utf8')).toBe(content)
    await page.keyboard.press('Escape')
    await expect(details).toHaveCount(0)
    await expect(page.getByRole('button', { name: `查看 ${fileName} 的详情` })).toBeFocused()

    // 下载走短期票据；浏览器应拿到同名文件
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: `下载 ${fileName}` }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe(fileName)
    expect(await fs.readFile(await download.path(), 'utf8')).toBe(content)
    await expect(page.getByRole('region', { name: '最近下载' })).toContainText('已发起下载')

    // 队列里的完成任务也能下载，两个入口都经过下载票据。
    const taskDownloadPromise = page.waitForEvent('download')
    await page
      .locator('.task-card')
      .filter({ hasText: fileName })
      .getByRole('button', { name: '下载', exact: true })
      .click()
    const taskDownload = await taskDownloadPromise
    expect(await fs.readFile(await taskDownload.path(), 'utf8')).toBe(content)

    // 删除需要二次确认
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: `删除 ${fileName}` }).click()
    await expect(page.locator('tbody tr').filter({ hasText: fileName })).toHaveCount(0)
  })
})
