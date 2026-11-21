<script setup>
import { computed } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { clearDownloadHistory, downloadFile, state } from '../stores/uploader'
import { formatBytes } from '../lib/format'

const canClear = computed(() => state.downloads.some((item) => item.status !== 'preparing'))
const labels = { preparing: '正在准备下载…', submitted: '已发起下载', error: '下载未能发起' }
</script>

<template>
  <section v-if="state.downloads.length" class="download-panel" aria-label="最近下载">
    <div class="download-head">
      <div>
        <h3><BaseIcon name="download" :size="17" /> 最近下载</h3>
        <p>保存进度请查看浏览器或桌面窗口的下载列表；此处仅记录本次会话的下载请求。</p>
      </div>
      <button
        v-if="canClear"
        class="btn btn-ghost btn-sm"
        type="button"
        @click="clearDownloadHistory"
      >
        清除记录
      </button>
    </div>
    <ul aria-live="polite" aria-relevant="additions text">
      <li v-for="item in state.downloads" :key="item.id" :class="item.status">
        <span class="download-icon"
          ><BaseIcon :name="item.status === 'error' ? 'alert' : 'file'" :size="19"
        /></span>
        <div class="download-copy">
          <strong :title="item.fileName">{{ item.fileName }}</strong>
          <span>{{ formatBytes(item.fileSize) }} · {{ labels[item.status] }}</span>
          <span v-if="item.error" class="download-error">{{ item.error }}</span>
        </div>
        <button
          v-if="item.status === 'error'"
          class="btn btn-sm"
          type="button"
          :aria-label="`重试下载 ${item.fileName}`"
          @click="downloadFile({ ...item, status: 1 })"
        >
          <BaseIcon name="refresh" :size="14" /> 重试
        </button>
        <BaseIcon
          v-else-if="item.status === 'submitted'"
          class="download-check"
          name="check"
          :size="18"
        />
      </li>
    </ul>
  </section>
</template>

<style scoped>
.download-panel {
  padding: 20px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
}
.download-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
}
p {
  margin: 6px 0 16px;
  font-size: 13px;
  color: var(--muted);
}
ul {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 0;
  padding: 0;
}
li {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--panel);
}
.download-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  color: var(--brand-light);
  background: color-mix(in srgb, var(--brand) 12%, transparent);
}
.download-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}
.download-copy strong {
  font-size: 14px;
  font-weight: 550;
  overflow-wrap: anywhere;
}
.download-copy span {
  font-size: 13px;
  color: var(--muted);
}
.download-copy .download-error,
.error .download-icon {
  color: var(--bad);
}
.download-check {
  flex: none;
  color: var(--ok);
}
@media (max-width: 600px) {
  .download-panel {
    padding: 16px;
  }
  .download-head {
    flex-wrap: wrap;
    gap: 0;
  }
  .download-head > button {
    margin-bottom: 12px;
  }
}
</style>
