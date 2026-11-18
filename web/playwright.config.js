import { defineConfig, devices } from '@playwright/test'

// 不自动拉起后端：CI 里由 workflow 显式启动 MySQL + JAR，
// 本地也要求先运行 file-hub 再跑测试，避免测试进程和后端生命周期互相打架。
// PLAYWRIGHT_CHANNEL=msedge/chrome 可直接复用本机浏览器，省掉 Chromium 下载。
const channel = process.env.PLAYWRIGHT_CHANNEL || undefined

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:8081',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    acceptDownloads: true,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], channel },
    },
  ],
})
