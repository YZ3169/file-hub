import { expect, test } from '@playwright/test'
import { gotoWorkspace, injectSession, registerViaApi } from './helpers'

test.describe('v8 增强工作台端到端', () => {
  test('在线预览、目录管理、回收站还原、免密分享转存与审计日志', async ({
    page,
    request,
    browser,
  }) => {
    const { session: alice } = await registerViaApi(request)
    const { session: bob } = await registerViaApi(request)
    await injectSession(page, alice)
    await gotoWorkspace(page)

    const fileName = `pw-v8-${Date.now()}.txt`
    const content = `line-alpha\nline-beta\n${Date.now()}\n`
    await page.locator('input[type="file"]:not([webkitdirectory])').setInputFiles({
      name: fileName,
      mimeType: 'text/plain',
      buffer: Buffer.from(content),
    })
    await expect(page.getByRole('status')).toContainText('上传完成', { timeout: 30_000 })

    const row = page.locator('tbody tr').filter({ hasText: fileName })
    await expect(row).toBeVisible()

    // 1. 在线文本预览
    await row.getByTestId('preview-file-button').click()
    const preview = page.getByTestId('file-preview-dialog')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('line-alpha')
    await expect(preview).toContainText('line-beta')
    await page.keyboard.press('Escape')
    await expect(preview).toHaveCount(0)

    // 2. 新建目录与目录视图切换
    const dirName = `dir-${Date.now()}`
    await page.getByTestId('new-folder-button').click()
    const folderBar = page.getByTestId('new-folder-bar')
    await folderBar.getByLabel('新文件夹路径').fill(dirName)
    await folderBar.getByRole('button', { name: '创建' }).click()
    await expect(page.getByTestId('breadcrumbs')).toBeVisible()
    await expect(page.locator('.folder-row').filter({ hasText: dirName })).toBeVisible()

    // 切回平铺列表
    await page.getByTestId('view-mode-list').click()
    await expect(page.locator('tbody tr').filter({ hasText: fileName })).toBeVisible()

    // 3. 移入回收站 -> 打开回收站还原
    page.once('dialog', (dialog) => dialog.accept())
    await page.getByRole('button', { name: `删除 ${fileName}` }).click()
    await expect(page.locator('tbody tr').filter({ hasText: fileName })).toHaveCount(0)

    await page.getByTestId('trash-button').click()
    const trashPanel = page.getByTestId('trash-panel')
    await expect(trashPanel).toBeVisible()
    await trashPanel
      .locator('.trash-row')
      .filter({ hasText: fileName })
      .getByRole('button', { name: '还原' })
      .click()
    await trashPanel.getByLabel('关闭回收站').click()
    await expect(page.locator('tbody tr').filter({ hasText: fileName })).toBeVisible()

    // 4. 创建免提取码分享 -> 另一个登录用户一键转存到自己网盘
    await page
      .locator('tbody tr')
      .filter({ hasText: fileName })
      .getByTestId('share-file-button')
      .click()
    const shareDialog = page.getByTestId('share-dialog')
    await shareDialog.getByTestId('share-require-code').uncheck()
    await shareDialog.getByTestId('share-create').click()
    await expect(shareDialog.getByTestId('share-no-code')).toBeVisible()
    const shareUrl = await shareDialog.getByTestId('share-link').inputValue()
    await shareDialog.getByRole('button', { name: '完成' }).click()

    const bobContext = await browser.newContext()
    const bobPage = await bobContext.newPage()
    await injectSession(bobPage, bob)
    await bobPage.goto(shareUrl)
    await expect(bobPage.getByTestId('share-open-hint')).toBeVisible()
    await bobPage.getByTestId('share-submit').click()
    await expect(bobPage.getByTestId('share-result')).toBeVisible()
    await bobPage.getByTestId('share-save').click()
    await expect(bobPage.getByTestId('share-saved')).toContainText(fileName)
    await bobContext.close()

    // 5. 审计日志与账号设置（WebDAV 地址展示）
    await page.getByTestId('audit-button').click()
    const auditPanel = page.getByTestId('audit-panel')
    await expect(auditPanel).toBeVisible()
    await expect(auditPanel).toContainText('RESTORE')
    await auditPanel.getByLabel('关闭审计日志').click()

    await page.getByTestId('account-button').click()
    const accountDialog = page.getByTestId('account-dialog')
    await expect(accountDialog).toBeVisible()
    await expect(accountDialog.locator('.dav-row input')).toHaveValue(/\/dav\/$/)
    await accountDialog.getByLabel('关闭账号设置').click()
  })
})
