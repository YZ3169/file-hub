import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { PASSWORD, uniqueName } from './helpers'

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex')

/** 走真实 API 注册、上传一个小文件并创建分享，返回页面用例需要的全部信息 */
async function uploadAndShare(request) {
  const register = await request.post('/api/auth/register', {
    data: { username: uniqueName('share'), password: PASSWORD },
  })
  const regBody = await register.json()
  expect(regBody.code, JSON.stringify(regBody)).toBe(0)
  const auth = { Authorization: `Bearer ${regBody.data.token}` }

  const content = Buffer.from(`file-hub 分享用例 ${Date.now()}\n`, 'utf8')
  const fileName = `share-${Date.now()}.txt`
  const hash = sha256(content)

  const initRes = await request.post('/api/file/init', {
    headers: auth,
    data: { fileName, fileSize: content.length, fileHash: hash },
  })
  const init = await initRes.json()
  expect(init.code, JSON.stringify(init)).toBe(0)
  const fileId = init.data.fileId

  if (!init.data.instant && !(init.data.uploadedIndexes || []).includes(0)) {
    const upRes = await request.post(
      `/api/file/chunk?fileId=${encodeURIComponent(fileId)}&index=0&chunkHash=${hash}`,
      {
        headers: auth,
        multipart: {
          chunk: { name: fileName, mimeType: 'text/plain', buffer: content },
        },
      },
    )
    const up = await upRes.json()
    expect(up.code, JSON.stringify(up)).toBe(0)
  }

  const doneRes = await request.post(`/api/file/${encodeURIComponent(fileId)}/complete`, {
    headers: auth,
  })
  const done = await doneRes.json()
  expect(done.code, JSON.stringify(done)).toBe(0)

  const shareRes = await request.post('/api/share', {
    headers: auth,
    data: { fileId, expiresInDays: 7 },
  })
  const share = await shareRes.json()
  expect(share.code, JSON.stringify(share)).toBe(0)

  return { auth, fileName, content, shareToken: share.data.token, code: share.data.code }
}

test.describe('提取码分享页', () => {
  test('错码提示剩余次数，正确码可下载，取消后链接立即失效', async ({ page, request }) => {
    const s = await uploadAndShare(request)

    await page.goto(`/#/s/${s.shareToken}`)
    await expect(page.getByTestId('share-view')).toBeVisible()
    await expect(page.getByTestId('share-code-input')).toBeVisible()

    await page.getByTestId('share-code-input').fill('ZZZZ')
    await page.getByTestId('share-submit').click()
    await expect(page.getByRole('alert')).toContainText('提取码错误')

    await page.getByTestId('share-code-input').fill(s.code)
    await page.getByTestId('share-submit').click()
    await expect(page.getByTestId('share-result')).toContainText(s.fileName)

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: '下载' }).click(),
    ])
    const saved = await fs.readFile(await download.path())
    expect(saved.equals(s.content)).toBeTruthy()

    // 所有者取消分享后，访客刷新链接应当立刻看到「链接无效」
    const cancel = await request.delete(`/api/share/${s.shareToken}`, { headers: s.auth })
    expect((await cancel.json()).code).toBe(0)
    await page.reload()
    await expect(page.getByTestId('share-view')).toBeVisible()
    await expect(page.getByText('链接无效')).toBeVisible()
    await expect(page.getByTestId('share-code-input')).toHaveCount(0)
  })

  test('被截断的分享链接留在分享页提示无效，而不是跳去登录', async ({ page }) => {
    await page.goto('/#/s/abcd')

    await expect(page.getByTestId('share-view')).toBeVisible()
    await expect(page.getByText('链接无效')).toBeVisible()
    await expect(page.getByRole('heading', { name: '登录 file-hub' })).toHaveCount(0)
  })
})
