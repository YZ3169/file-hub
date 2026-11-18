import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 开发时：Vite 跑 5173，/api 代理到 Spring Boot 8081
// 构建时：直接输出到 Spring Boot 的 static 目录，打成 jar 后打开根路径就是工作台
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: fileURLToPath(new URL('../src/main/resources/static', import.meta.url)),
    emptyOutDir: true,
    assetsDir: 'assets',
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    include: ['src/**/*.test.js'],
    restoreMocks: true,
  },
})
