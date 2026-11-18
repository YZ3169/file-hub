<script setup>
import { computed, onMounted } from 'vue'
import BaseIcon from './components/BaseIcon.vue'
import UploadZone from './components/UploadZone.vue'
import StatsBar from './components/StatsBar.vue'
import TaskCard from './components/TaskCard.vue'
import FileTable from './components/FileTable.vue'
import ToastHost from './components/ToastHost.vue'
import {
  bootstrap,
  clearFinished,
  state,
  toggleTheme,
} from './stores/uploader'
import { formatBytes } from './lib/format'

const flow = [
  { icon: 'hash', title: '增量哈希', text: '4MB 一段，内存恒定' },
  { icon: 'bolt', title: '秒传预检', text: '内容命中，0 字节上传' },
  { icon: 'layers', title: '并发分片', text: '每片独立校验 + 重试' },
  { icon: 'shield', title: '流式合并', text: '逐片 + 整文件复核' },
]

const resumableHint = computed(() =>
  state.pendingResumable.filter(
    (p) => !state.tasks.some((t) => t.fingerprint === p.fingerprint),
  ),
)

const activeCount = computed(
  () => state.tasks.filter((t) => !['done', 'instant', 'canceled'].includes(t.state)).length,
)
const finishedCount = computed(
  () => state.tasks.filter((t) => ['done', 'instant', 'canceled'].includes(t.state)).length,
)

onMounted(() => {
  bootstrap()
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
          <svg viewBox="0 0 32 32" width="30" height="30" aria-hidden="true">
            <defs>
              <linearGradient id="logoGrad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#8b7fff" />
                <stop offset="100%" stop-color="#22d3ee" />
              </linearGradient>
            </defs>
            <path
              d="M16 3 28 9.5v13L16 29 4 22.5v-13L16 3Z"
              fill="url(#logoGrad)"
              opacity=".18"
            />
            <path
              d="M16 3 28 9.5v13L16 29 4 22.5v-13L16 3Z"
              fill="none"
              stroke="url(#logoGrad)"
              stroke-width="1.6"
              stroke-linejoin="round"
            />
            <path
              d="M4 9.5 16 16l12-6.5M16 16v13"
              fill="none"
              stroke="url(#logoGrad)"
              stroke-width="1.6"
              stroke-linejoin="round"
            />
          </svg>
        </span>
        <div class="brand-copy">
          <strong>file-hub</strong>
          <span>大文件传输工作台</span>
        </div>
      </div>

      <nav class="top-actions">
        <a
          class="btn btn-ghost btn-sm"
          href="https://github.com/YZ3169/file-hub"
          target="_blank"
          rel="noreferrer"
        >
          GitHub <span class="ext">↗</span>
        </a>
        <a class="btn btn-ghost btn-sm" href="/classic.html" target="_blank" rel="noreferrer">
          经典版
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
      </nav>
    </header>

    <div v-if="!state.online" class="offline-banner">
      <BaseIcon name="alert" :size="16" />
      <span>服务端未连接：请先在 8081 端口启动 file-hub，再重试。</span>
      <button class="btn btn-ghost btn-sm" type="button" @click="bootstrap">
        <BaseIcon name="refresh" :size="14" />
        重试连接
      </button>
    </div>

    <main class="page">
      <section class="hero">
        <div class="kicker">
          <i></i>
          Spring Boot · 分片存储 · 内容寻址
        </div>
        <h1>
          大文件传输，
          <span class="gradient-text">断了也不用从头再来</span>
        </h1>
        <p>
          分片上传、秒传、断点续传，加上分片级完整性校验与自动重传。
          传一个 2GB 的文件，网络抖一下只补那一片，而不是重传整个文件。
        </p>
      </section>

      <StatsBar />

      <UploadZone />

      <div v-if="resumableHint.length" class="resume-hint">
        <span class="resume-icon"><BaseIcon name="refresh" :size="17" /></span>
        <div>
          <strong>有 {{ resumableHint.length }} 个未完成的传输任务</strong>
          <span>
            重新选择同名、同大小、同修改时间的文件即可接着传：
            {{ resumableHint.map((p) => p.fileName).slice(0, 3).join('、')
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
          <div class="empty-icon"><BaseIcon name="cloud" :size="26" /></div>
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
          <span><BaseIcon name="layers" :size="13" /> 分片 {{ formatBytes(state.config.chunkSize) }}</span>
          <span><BaseIcon name="server" :size="13" /> {{ state.config.backend || '本地磁盘' }}</span>
          <span><BaseIcon name="shield" :size="13" /> SHA-256 内容寻址</span>
        </div>
        <span class="footer-note">
          file-hub v2 · 分片级校验 / 自动重传 / 断点续传 / Range 下载
        </span>
      </footer>
    </main>

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
  to { transform: translate3d(70px, 50px, 0) scale(1.06); }
}
@keyframes float-b {
  to { transform: translate3d(-60px, 80px, 0) scale(1.1); }
}
@keyframes float-c {
  to { transform: translate3d(-40px, -70px, 0) scale(0.94); }
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
  border-radius: 13px;
  border: 1px solid rgba(139, 127, 255, 0.35);
  background: rgba(109, 93, 252, 0.1);
  box-shadow: 0 10px 26px rgba(109, 93, 252, 0.22);
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
  transition: color 0.18s ease, border-color 0.18s ease;
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
  background: linear-gradient(100deg, var(--brand-light) 0%, var(--brand-2) 55%, var(--violet) 100%);
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
  transition: opacity 0.25s ease, transform 0.25s ease;
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
