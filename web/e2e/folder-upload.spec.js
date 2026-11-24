import { expect, test } from '@playwright/test'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { gotoWorkspace, injectSession, registerViaApi } from './helpers'

async function listFiles(request, headers) {
  const response = await request.get('/api/file/list?size=100', { headers })
  const body = await response.json()
  expect(body.code, JSON.stringify(body)).toBe(0)
  return body.data.items
}

async function waitForFiles(request, headers, count) {
  let files
  await expect
    .poll(
      async () => {
        files = await listFiles(request, headers)
        return files.filter((file) => file.status === 1).length
      },
      { timeout: 30_000 },
    )
    .toBe(count)
  expect(files).toHaveLength(count)
  return files
}

async function removeFiles(request, headers) {
  for (const file of await listFiles(request, headers)) {
    const response = await request.delete(`/api/file/${file.fileId}`, { headers })
    expect((await response.json()).code).toBe(0)
  }
}

async function removeTempDirectory(tempRoot) {
  // 只删除本次 mkdtemp 创建、位于系统临时目录中的测试目录。
  const relative = path.relative(path.resolve(os.tmpdir()), path.resolve(tempRoot))
  if (path.dirname(relative) !== '.' || !relative.startsWith('filehub-folder-e2e-')) {
    throw new Error(`Unexpected temporary directory: ${tempRoot}`)
  }
  await fs.rm(tempRoot, { recursive: true, force: true })
}

test('选择文件夹保留嵌套路径、同名文件和空文件，刷新后仍可下载', async ({ page, request }) => {
  const { session } = await registerViaApi(request)
  const headers = { Authorization: `Bearer ${session.token}` }
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'filehub-folder-e2e-'))
  const folderName = '项目资料'
  const folder = path.join(tempRoot, folderName)
  const sameContent = `same content in two folders ${Date.now()}\n`
  const entries = [
    ['文档/报告.txt', sameContent],
    ['备份/报告.txt', sameContent],
    ['空文件.txt', ''],
    ['说明.txt', '保留相对路径\n'],
  ]

  try {
    for (const [relativePath, content] of entries) {
      const filePath = path.join(folder, relativePath)
      await fs.mkdir(path.dirname(filePath), { recursive: true })
      await fs.writeFile(filePath, content)
      // 相同名称、大小和修改时间仍必须按目录分成两个任务。
      await fs.utimes(filePath, 1700000000, 1700000000)
    }
    await injectSession(page, session)
    await gotoWorkspace(page)
    const chooserPromise = page.waitForEvent('filechooser')
    await page.getByRole('button', { name: '选择文件夹', exact: true }).click()
    await (await chooserPromise).setFiles(folder)

    const files = await waitForFiles(request, headers, entries.length)
    const expectedPaths = entries.map(([name]) => `${folderName}/${name}`).sort()
    expect(files.map((file) => file.relativePath).sort()).toEqual(expectedPaths)
    expect(new Set(files.map((file) => file.fileId)).size).toBe(entries.length)
    const duplicateNames = files.filter((file) => file.fileName === '报告.txt')
    expect(duplicateNames).toHaveLength(2)
    expect(duplicateNames[0].fileHash).toBe(duplicateNames[1].fileHash)
    expect(files.find((file) => file.fileName === '空文件.txt').fileSize).toBe(0)
    for (const relativePath of expectedPaths) {
      await expect(page.locator('.task-card').filter({ hasText: relativePath })).toBeVisible()
    }

    await page.reload()
    await expect(page.getByRole('heading', { name: '传输队列' })).toBeVisible()
    for (const relativePath of expectedPaths) {
      const row = page.locator('tbody tr').filter({ hasText: relativePath })
      await expect(row).toBeVisible()
      await expect(row).toContainText('已完成')
    }

    const row = page.locator('tbody tr').filter({ hasText: `${folderName}/文档/报告.txt` })
    const downloadPromise = page.waitForEvent('download')
    await row.getByRole('button', { name: `下载 ${folderName}/文档/报告.txt`, exact: true }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('报告.txt')
    expect(await fs.readFile(await download.path(), 'utf8')).toBe(sameContent)
  } finally {
    await page.goto('about:blank')
    try {
      await removeFiles(request, headers)
    } finally {
      await removeTempDirectory(tempRoot)
    }
  }
})

test('混合拖入文件夹和普通文件，递归上传子目录并保留各自路径', async ({ page, request }) => {
  const { session } = await registerViaApi(request)
  const headers = { Authorization: `Bearer ${session.token}` }
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'filehub-folder-e2e-'))
  const folder = path.join(tempRoot, '拖入资料')
  const standalone = path.join(tempRoot, '单独.txt')
  let cdp
  try {
    await fs.mkdir(path.join(folder, '子目录'), { recursive: true })
    await fs.writeFile(path.join(folder, '顶层.txt'), 'root file')
    await fs.writeFile(path.join(folder, '子目录', '嵌套.txt'), 'nested file')
    await fs.writeFile(standalone, 'standalone file')
    await injectSession(page, session)
    await gotoWorkspace(page)
    const dropzone = page.locator('.dropzone')
    await dropzone.scrollIntoViewIfNeeded()
    const bounds = await dropzone.boundingBox()
    expect(bounds).not.toBeNull()
    cdp = await page.context().newCDPSession(page)
    // 当前 E2E 项目使用 Chromium/Edge。由浏览器创建真实文件系统条目，
    // 直接验证目录访问、子目录遍历和 drop 回调结束后的句柄有效性。
    for (const type of ['dragEnter', 'dragOver', 'drop']) {
      await cdp.send('Input.dispatchDragEvent', {
        type,
        x: bounds.x + bounds.width / 2,
        y: bounds.y + bounds.height / 2,
        data: { items: [], files: [folder, standalone], dragOperationsMask: 1 },
      })
    }

    const files = await waitForFiles(request, headers, 3)
    expect(files.map((file) => file.relativePath || file.fileName).sort()).toEqual(
      ['拖入资料/顶层.txt', '拖入资料/子目录/嵌套.txt', '单独.txt'].sort(),
    )
    expect(files.find((file) => file.fileName === '单独.txt').relativePath || '').toBe('')
    await expect(
      page.locator('tbody tr').filter({ hasText: '拖入资料/子目录/嵌套.txt' }),
    ).toBeVisible()
  } finally {
    await page.goto('about:blank')
    await cdp?.detach()
    try {
      await removeFiles(request, headers)
    } finally {
      await removeTempDirectory(tempRoot)
    }
  }
})
