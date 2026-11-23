<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseIcon from './components/BaseIcon.vue'
import AppIcon from './components/AppIcon.vue'
import UploadZone from './components/UploadZone.vue'
import StatsBar from './components/StatsBar.vue'
import TaskCard from './components/TaskCard.vue'
import FileTable from './components/FileTable.vue'
import ToastHost from './components/ToastHost.vue'
import LoginView from './components/LoginView.vue'
import UserMenu from './components/UserMenu.vue'
import AdminIntegrityPanel from './components/AdminIntegrityPanel.vue'
import ShareView from './components/ShareView.vue'
import { applyTheme, bootstrap, clearFinished, state, toggleTheme } from './stores/uploader'
import { auth } from './lib/auth'
import { formatBytes } from './lib/format'

/** 分享链接是纯前端 hash 路由：#/s/<token>，不引入 vue-router */
const SHARE_HASH = /^#\/s\/([A-Za-z0-9_-]{16,64})$/
const SHARE_PREFIX = '#/s/'

function parseShareHash() {
  const hash = window.location.hash
  const match = SHARE_HASH.exec(hash)
  if (match) return match[1]
  // 链接被聊天软件截断、或手改坏了，也要留在分享页让后端给出「链接无效」，
  // 而不是把访客带到登录页——他可能根本没有账号。
  if (hash.startsWith(SHARE_PREFIX)) {
    return hash.slice(SHARE_PREFIX.length) || 'invalid-share-link'
  }
  return null
}

const shareToken = ref(parseShareHash())

function onHashChange() {
  const token = parseShareHash()
  shareToken.value = token
  // 从分享页回到工作台：之前跳过了 bootstrap，这里补上
  if (!token && !state.booted) bootstrap()
}

const flow = [
  { icon: 'hash', title: '增量哈希', text: '4MB 一段，内存恒定' },
  { icon: 'bolt', title: '秒传预检', text: '内容命中，0 字节上传' },
  { icon: 'layers', title: '并发分片', text: '每片独立校验 + 重试' },
  { icon: 'shield', title: '流式合并', text: '逐片 + 整文件复核' },
]

const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const showIntegrity = ref(false)

const resumableHint = computed(() =>
  state.pendingResumable.filter((p) => !state.tasks.some((t) => t.fingerprint === p.fingerprint)),
)

const activeCount = computed(
  () => state.tasks.filter((t) => !['done', 'instant', 'canceled'].includes(t.state)).length,
)
const finishedCount = computed(
  () => state.tasks.filter((t) => ['done', 'instant', 'canceled'].includes(t.state)).length,
)

onMounted(() => {
  applyTheme()
  window.addEventListener('hashchange', onHashChange)
  // 分享页可能来自未登录的访客：不要触发工作区 bootstrap
  if (!shareToken.value) bootstrap()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
})
</script>

<template>
  <div class="app-shell">
    <div class="backdrop" aria-hidden="true">
      <div class="aurora aurora-a"></div>
      <div class="aurora aurora-b"></div>
      <div class="aurora aurora-c"></div>
      <div class="grid-overlay"></div>
    </div>

    <header class="topbar">
      <div class="brand">
        <span class="logo">
          <AppIcon />
        </span>
        <div class="brand-copy">
          <strong>file-hub</strong>
          <span>大文件传输工作台</span>
        </div>
      </div>

      <nav class="top-actions" aria-label="应用导航">
        <a
          class="btn btn-ghost btn-sm"
          href="https://github.com/YZ3169/file-hub"
          target="_blank"
          rel="noreferrer"
        >
          GitHub <span class="ext">↗</span>
        </a>
        <a class="btn btn-ghost btn-sm" href="/download.html" target="_blank" rel="noreferrer">
          <BaseIcon name="download" :size="14" /> 下载桌面版
        </a>
        <button
          class="icon-btn"
          type="button"
          :title="state.theme === 'dark' ? '切换浅色' : '切换深色'"
          :aria-label="state.theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
          :aria-pressed="state.theme === 'light'"
          @click="toggleTheme"
        >
          <BaseIcon :name="state.theme === 'dark' ? 'sun' : 'moon'" :size="17" />
        </button>
        <button
          v-if="isAdmin"
          class="btn btn-ghost btn-sm"
          type="button"
          data-testid="integrity-button"
          @click="showIntegrity = true"
        >
          <BaseIcon name="shield" :size="14" />
          完整性
        </button>
        <UserMenu v-if="auth.token" />
      </nav>
    </header>

    <div v-if="auth.token && !state.online" class="offline-banner">
      <BaseIcon name="alert" :size="16" />
      <span>服务端未连接：请确认 file-hub 正在运行，再重试。</span>
      <button class="btn btn-ghost btn-sm" type="button" @click="bootstrap">
        <BaseIcon name="refresh" :size="14" />
        重试连接
      </button>
    </div>

    <main v-if="shareToken" class="page page-share">
      <ShareView :key="shareToken" :token="shareToken" />
    </main>

    <main v-else-if="!auth.ready" class="page boot-page">
      <div class="boot-screen" data-testid="boot-screen">
        <span class="boot-ring"></span>
        <span>正在恢复会话…</span>
      </div>
    </main>

    <main v-else-if="!auth.token" class="page page-auth">
      <LoginView />
      <footer class="footer">
        <div class="footer-meta">
          <span><BaseIcon name="user" :size="13" /> 账号级文件隔离</span>
          <span><BaseIcon name="gauge" :size="13" /> 登录/上传限流</span>
          <span><BaseIcon name="link" :size="13" /> 短期下载票据</span>
        </div>
        <span class="footer-note">file-hub v2 · 登录后开始传输</span>
      </footer>
    </main>

    <main v-else class="page">
      <section class="hero">
        <div class="kicker">
          <i></i>
          文件传输与管理
        </div>
        <h1>大文件，<span class="gradient-text">轻松存取</span></h1>
        <p>上传、下载、分享，在一个地方完成。传输中断后重新选择原文件，即可接着上传。</p>
        <a class="btn btn-primary files-shortcut" href="#files"
          ><BaseIcon name="download" :size="17" /> 查看文件与下载</a
        >
      </section>

      <StatsBar />

      <UploadZone />

      <div v-if="resumableHint.length" class="resume-hint">
        <span class="resume-icon"><BaseIcon name="refresh" :size="17" /></span>
        <div>
          <strong>有 {{ resumableHint.length }} 个未完成的传输任务</strong>
          <span>
            重新选择同名、同大小、同修改时间的文件即可接着传：
            {{
              resumableHint
                .map((p) => p.fileName)
                .slice(0, 3)
                .join('、')
            }}{{ resumableHint.length > 3 ? ' 等' : '' }}
          </span>
        </div>
      </div>

      <div class="flow-strip">
        <div v-for="(step, i) in flow" :key="step.title" class="flow-item">
          <span class="flow-icon"><BaseIcon :name="step.icon" :size="15" /></span>
          <div>
            <strong>{{ step.title }}</strong>
            <span>{{ step.text }}</span>
          </div>
          <span v-if="i < flow.length - 1" class="flow-arrow">→</span>
        </div>
      </div>

      <section class="queue-section">
        <div class="section-head">
          <div>
            <h2>传输队列</h2>
            <span>
              {{ state.tasks.length ? `${activeCount} 个进行中` : '还没有任务' }}
              <template v-if="finishedCount"> · {{ finishedCount }} 个已结束</template>
            </span>
          </div>
          <button
            v-if="finishedCount"
            class="btn btn-ghost btn-sm"
            type="button"
            @click="clearFinished"
          >
            <BaseIcon name="x" :size="14" />
            清除已结束
          </button>
        </div>

        <div v-if="!state.tasks.length" class="empty-queue">
          <div class="empty-icon"><BaseIcon name="upload" :size="26" /></div>
          <strong>队列是空的</strong>
          <span>选一个大文件，试试中途刷新页面再继续</span>
        </div>

        <TransitionGroup v-else name="task" tag="div" class="task-list">
          <TaskCard v-for="task in state.tasks" :key="task.id" :task="task" />
        </TransitionGroup>
      </section>

      <FileTable />

      <footer class="footer">
        <div class="footer-meta">
          <span
            ><BaseIcon name="layers" :size="13" /> 分片
            {{ formatBytes(state.config.chunkSize) }}</span
          >
          <span
            ><BaseIcon name="server" :size="13" /> {{ state.config.backend || '本地磁盘' }}</span
          >
          <span><BaseIcon name="shield" :size="13" /> SHA-256 内容寻址</span>
          <span v-if="state.config.desktop" data-testid="desktop-badge">
            <BaseIcon name="server" :size="13" /> 桌面版 · 关闭窗口可最小化到托盘
          </span>
        </div>
        <span class="footer-note">
          file-hub v2 · 分片级校验 / 自动重传 / 断点续传 / 多用户隔离
        </span>
      </footer>
    </main>

    <AdminIntegrityPanel v-if="showIntegrity" @close="showIntegrity = false" />

    <ToastHost />
  </div>
</template>

<style scoped>
.app-shell {
  position: relative;
  min-height: 100vh;
  isolation: isolate;
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: -1;
  overflow: hidden;
  background: var(--bg);
}

.aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: var(--aurora-opacity);
  will-change: transform;
}

.aurora-a {
  width: 620px;
  height: 620px;
  left: -180px;
  top: -220px;
  background: radial-gradient(circle, #5b4be8 0%, transparent 66%);
  animation: float-a 18s ease-in-out infinite alternate;
}

.aurora-b {
  width: 540px;
  height: 540px;
  right: -160px;
  top: 60px;
  background: radial-gradient(circle, #0891b2 0%, transparent 66%);
  animation: float-b 22s ease-in-out infinite alternate;
}

.aurora-c {
  width: 660px;
  height: 660px;
  left: 38%;
  top: 55%;
  background: radial-gradient(circle, #7c3aed 0%, transparent 68%);
  animation: float-c 26s ease-in-out infinite alternate;
}

@keyframes float-a {
  to {
    transform: translate3d(70px, 50px, 0) scale(1.06);
  }
}
@keyframes float-b {
  to {
    transform: translate3d(-60px, 80px, 0) scale(1.1);
  }
}
@keyframes float-c {
  to {
    transform: translate3d(-40px, -70px, 0) scale(0.94);
  }
}

.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px),
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 90% 60% at 50% 0%, #000 30%, transparent 78%);
  opacity: 0.55;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  max-width: 1180px;
  margin: 0 auto;
  padding: 18px 24px 14px;
  backdrop-filter: blur(14px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
}

.logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  filter: drop-shadow(0 4px 8px rgba(8, 15, 30, 0.16));
}

.brand-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-copy strong {
  font-size: 16.5px;
  letter-spacing: -0.02em;
}

.brand-copy span {
  color: var(--muted);
  font-size: 11.5px;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}

.ext {
  color: var(--muted-2);
  font-size: 11px;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
  transition:
    color 0.18s ease,
    border-color 0.18s ease;
}

.icon-btn:hover {
  color: var(--text);
  border-color: var(--border-strong);
}

.offline-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 1132px;
  margin: 0 auto 12px;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--bad) 40%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, var(--bad) 10%, var(--panel));
  color: var(--bad);
  font-size: 12.5px;
}

.offline-banner span {
  flex: 1;
}

.page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 12px 24px 48px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.page-auth {
  min-height: calc(100vh - 180px);
  justify-content: center;
}

.boot-page {
  min-height: calc(100vh - 160px);
  justify-content: center;
}

.boot-screen {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 11px;
  color: var(--muted);
  font-size: 12.5px;
}

.boot-ring {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--brand-light);
  border-radius: 50%;
  animation: boot-spin 0.8s linear infinite;
}

@keyframes boot-spin {
  to {
    transform: rotate(360deg);
  }
}

.hero {
  padding: 26px 4px 8px;
  max-width: 780px;
}

.kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 11px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--panel-2);
  color: var(--muted);
  font-size: 11.5px;
  letter-spacing: 0.02em;
}

.kicker i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ok);
  box-shadow: 0 0 8px var(--ok);
}

.hero h1 {
  margin: 16px 0 12px;
  font-size: clamp(30px, 4.4vw, 46px);
  line-height: 1.12;
  letter-spacing: -0.035em;
  font-weight: 720;
}

.gradient-text {
  background: linear-gradient(
    100deg,
    var(--brand-light) 0%,
    var(--brand-2) 55%,
    var(--violet) 100%
  );
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero p {
  margin: 0;
  max-width: 660px;
  color: var(--muted);
  font-size: 14.5px;
  line-height: 1.75;
}

.files-shortcut {
  margin-top: 20px;
}

.resume-hint {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 13px 16px;
  border: 1px solid color-mix(in srgb, var(--brand-2) 30%, transparent);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--brand-2) 7%, var(--panel));
  color: var(--muted);
  font-size: 12.5px;
}

.resume-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 11px;
  background: color-mix(in srgb, var(--brand-2) 14%, transparent);
  color: var(--brand-2);
}

.resume-hint div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.resume-hint strong {
  color: var(--text);
  font-weight: 600;
  font-size: 13px;
}

.flow-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--panel);
  box-shadow: var(--shadow-soft);
}

.flow-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  min-width: 0;
}

.flow-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--brand-light);
}

.flow-item div {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.flow-item strong {
  font-size: 12.5px;
  font-weight: 600;
}

.flow-item span:not(.flow-icon):not(.flow-arrow) {
  color: var(--muted-2);
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-arrow {
  position: absolute;
  right: -9px;
  color: var(--border-strong);
  font-size: 13px;
}

.queue-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 2px;
}

.section-head h2 {
  margin: 0 0 2px;
  font-size: 15.5px;
  font-weight: 650;
}

.section-head span {
  color: var(--muted);
  font-size: 12px;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.empty-queue {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 42px 20px 46px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--panel);
  color: var(--muted);
  text-align: center;
}

.empty-queue strong {
  color: var(--text);
  font-size: 14px;
  font-weight: 600;
}

.empty-queue span {
  color: var(--muted-2);
  font-size: 12px;
}

.empty-icon {
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  margin-bottom: 4px;
  border-radius: 16px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--muted);
}

.task-enter-active,
.task-leave-active {
  transition:
    opacity 0.25s ease,
    transform 0.25s ease;
}

.task-enter-from,
.task-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 4px 0;
  color: var(--muted-2);
  font-size: 11.5px;
}

.footer-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.footer-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.footer-note {
  font-family: var(--mono);
  font-size: 10.5px;
}

@media (max-width: 900px) {
  .flow-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .flow-arrow {
    display: none;
  }
}

@media (max-width: 640px) {
  .topbar {
    padding: 14px 16px 10px;
    flex-wrap: wrap;
    gap: 10px;
  }

  .top-actions {
    margin-left: auto;
  }

  .page {
    padding: 8px 16px 40px;
  }

  .top-actions .btn:first-child {
    display: none;
  }

  .hero {
    padding-top: 16px;
  }

  .footer {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
