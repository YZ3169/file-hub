<script setup>
import { computed, ref, watch } from 'vue'
import BaseIcon from './BaseIcon.vue'
import ShareDialog from './ShareDialog.vue'
import SharesPanel from './SharesPanel.vue'
import DownloadPanel from './DownloadPanel.vue'
import FileDetailsDialog from './FileDetailsDialog.vue'
import {
  deleteServerFile,
  downloadFile,
  goFilePage,
  refreshFiles,
  refreshStats,
  state,
  updateFileQuery,
} from '../stores/uploader'
import { formatBytes, formatDate, shortHash } from '../lib/format'

const keyword = ref(state.filePage.keyword)
const sort = ref(state.filePage.sort)
const statusFilter = ref(state.filePage.status === null ? '' : String(state.filePage.status))

const shareFile = ref(null)
const showShares = ref(false)
const detailsFile = ref(null)

watch(
  () => state.files,
  (files) => {
    if (!detailsFile.value) return
    detailsFile.value = files.find((file) => file.fileId === detailsFile.value.fileId) || null
  },
)

const totalPages = computed(() =>
  Math.max(1, Math.ceil(state.filePage.total / state.filePage.size)),
)
const pageStart = computed(() =>
  state.filePage.total === 0 ? 0 : (state.filePage.page - 1) * state.filePage.size + 1,
)
const pageEnd = computed(() =>
  Math.min(state.filePage.total, state.filePage.page * state.filePage.size),
)
const hasFilters = computed(() => Boolean(state.filePage.keyword) || statusFilter.value !== '')

function extension(name) {
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || dot === name.length - 1) return 'FILE'
  return name
    .slice(dot + 1)
    .slice(0, 4)
    .toUpperCase()
}

async function refresh() {
  await Promise.all([refreshFiles(), refreshStats()])
}

function applySearch() {
  updateFileQuery({ keyword: keyword.value.trim() })
}

function clearSearch() {
  keyword.value = ''
  updateFileQuery({ keyword: '' })
}

function applyStatus() {
  updateFileQuery({ status: statusFilter.value === '' ? null : Number(statusFilter.value) })
}

function applySort() {
  updateFileQuery({ sort: sort.value })
}

function resetFilters() {
  keyword.value = ''
  sort.value = 'time_desc'
  statusFilter.value = ''
  updateFileQuery({ keyword: '', status: null, sort: 'time_desc' })
}

function preparingDownload(fileId) {
  return state.downloads.some((item) => item.fileId === fileId && item.status === 'preparing')
}
</script>

<template>
  <section id="files" class="file-section" aria-labelledby="files-heading">
    <div class="section-head">
      <div>
        <h2 id="files-heading"><BaseIcon name="archive" :size="20" /> 文件与下载</h2>
        <span>
          共 {{ state.filePage.total }} 条记录
          <template v-if="state.filePage.total"> · 第 {{ pageStart }}–{{ pageEnd }} 条</template>
          · 找到文件，一键下载或分享
        </span>
      </div>
      <div class="head-actions">
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          data-testid="my-shares-button"
          @click="showShares = true"
        >
          <BaseIcon name="link" :size="14" />
          我的分享
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="state.filesLoading"
          @click="refresh"
        >
          <BaseIcon name="refresh" :size="14" />
          {{ state.filesLoading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </div>

    <div class="file-toolbar">
      <form class="search-box" role="search" @submit.prevent="applySearch">
        <BaseIcon name="search" :size="15" />
        <input
          v-model="keyword"
          type="search"
          placeholder="搜索文件名 / SHA-256"
          aria-label="搜索文件"
        />
        <button
          v-if="keyword"
          class="clear-btn"
          type="button"
          aria-label="清空搜索"
          @click="clearSearch"
        >
          ×
        </button>
        <button class="btn btn-ghost btn-sm" type="submit">搜索</button>
      </form>

      <div class="filters">
        <label class="field">
          <span>状态</span>
          <select v-model="statusFilter" aria-label="按状态筛选" @change="applyStatus">
            <option value="">全部</option>
            <option value="1">已完成</option>
            <option value="0">上传中</option>
          </select>
        </label>
        <label class="field">
          <span>排序</span>
          <select v-model="sort" aria-label="排序方式" @change="applySort">
            <option value="time_desc">最新优先</option>
            <option value="time_asc">最早优先</option>
            <option value="size_desc">大文件优先</option>
            <option value="size_asc">小文件优先</option>
            <option value="name_asc">文件名升序</option>
          </select>
        </label>
      </div>
    </div>

    <div v-if="state.filesError" class="list-error" role="alert">
      <BaseIcon name="alert" :size="19" />
      <div>
        <strong>文件列表加载失败</strong><span>{{ state.filesError }}</span>
      </div>
      <button class="btn btn-sm" type="button" :disabled="state.filesLoading" @click="refresh">
        重试
      </button>
    </div>

    <div v-if="state.filesLoading" class="list-loading" aria-live="polite">
      <span class="loading-ring" aria-hidden="true"></span> 正在加载文件…
    </div>

    <div v-else-if="!state.files.length && !state.filesError" class="empty-files">
      <div class="empty-icon"><BaseIcon name="archive" :size="26" /></div>
      <strong>{{ hasFilters ? '没有匹配的文件' : '还没有文件' }}</strong>
      <span v-if="hasFilters">换个关键字，或者清除筛选条件</span>
      <span v-else>上传文件后，即可在这里下载和分享</span>
      <button v-if="hasFilters" class="btn btn-ghost btn-sm" type="button" @click="resetFilters">
        清除筛选
      </button>
    </div>

    <div v-if="state.files.length && !state.filesLoading && !state.filesError" class="table-wrap">
      <table>
        <caption class="sr-only">
          文件列表：文件名、大小、状态、上传时间和下载操作
        </caption>
        <thead>
          <tr>
            <th scope="col">文件</th>
            <th scope="col">大小</th>
            <th scope="col">状态</th>
            <th scope="col">上传时间</th>
            <th scope="col" class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="file in state.files" :key="file.fileId">
            <td class="file-column">
              <div class="file-cell">
                <span class="file-badge">{{ extension(file.fileName) }}</span>
                <div class="file-name">
                  <button
                    class="file-title"
                    type="button"
                    :title="file.relativePath || file.fileName"
                    :aria-label="`查看 ${file.relativePath || file.fileName} 的详情`"
                    @click="detailsFile = file"
                  >
                    {{ file.fileName }}
                  </button>
                  <span v-if="file.relativePath" class="file-path" :title="file.relativePath">
                    {{ file.relativePath }}
                  </span>
                  <span :title="file.fileHash">{{ shortHash(file.fileHash) }}</span>
                </div>
              </div>
            </td>
            <td class="num" data-label="大小">{{ formatBytes(file.fileSize) }}</td>
            <td class="status-column" data-label="状态">
              <span class="status" :class="file.status === 1 ? 'ok' : 'mid'">
                <i></i>
                {{ file.status === 1 ? '已完成' : '上传中' }}
              </span>
            </td>
            <td class="time" data-label="上传时间">{{ formatDate(file.createTime) }}</td>
            <td class="col-actions">
              <div class="row-actions">
                <button
                  v-if="file.status === 1"
                  class="btn btn-primary btn-sm download-button"
                  type="button"
                  :disabled="preparingDownload(file.fileId)"
                  :aria-label="`下载 ${file.relativePath || file.fileName}`"
                  @click="downloadFile(file)"
                >
                  <BaseIcon name="download" :size="16" />
                  {{ preparingDownload(file.fileId) ? '准备中…' : '下载' }}
                </button>
                <span v-else class="pending-label">上传完成后可下载</span>
                <button
                  v-if="file.status === 1"
                  class="icon-link"
                  type="button"
                  title="分享（链接 + 提取码）"
                  :aria-label="`分享 ${file.relativePath || file.fileName}`"
                  data-testid="share-file-button"
                  @click="shareFile = file"
                >
                  <BaseIcon name="link" :size="15" />
                </button>
                <button
                  class="icon-link danger"
                  type="button"
                  title="删除"
                  :aria-label="`删除 ${file.relativePath || file.fileName}`"
                  @click="deleteServerFile(file)"
                >
                  <BaseIcon name="trash" :size="15" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="state.filePage.total > 0 && !state.filesError" class="pager">
      <span>第 {{ pageStart }}–{{ pageEnd }} 条，共 {{ state.filePage.total }} 条</span>
      <label class="field page-size">
        <span>每页显示</span>
        <select
          :value="state.filePage.size"
          aria-label="每页显示数量"
          :disabled="state.filesLoading"
          @change="updateFileQuery({ size: Number($event.target.value) })"
        >
          <option v-for="size in [10, 20, 50, 100]" :key="size" :value="size">{{ size }} 条</option>
        </select>
      </label>
      <div v-if="totalPages > 1" class="pager-controls">
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="state.filesLoading || state.filePage.page <= 1"
          aria-label="上一页"
          @click="goFilePage(state.filePage.page - 1)"
        >
          上一页
        </button>
        <span class="page-indicator">{{ state.filePage.page }} / {{ totalPages }}</span>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          :disabled="state.filesLoading || state.filePage.page >= totalPages"
          aria-label="下一页"
          @click="goFilePage(state.filePage.page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
    <DownloadPanel />
  </section>

  <ShareDialog v-if="shareFile" :file="shareFile" @close="shareFile = null" />
  <SharesPanel v-if="showShares" @close="showShares = false" />
  <FileDetailsDialog
    v-if="detailsFile"
    :file="detailsFile"
    :downloading="preparingDownload(detailsFile.fileId)"
    @close="detailsFile = null"
    @download="downloadFile(detailsFile)"
  />
</template>

<style scoped>
.file-section {
  scroll-margin-top: 100px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--panel);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(var(--blur));
  overflow: hidden;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 14px;
}

.section-head h2 {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 3px;
  font-size: 19px;
  font-weight: 650;
}

.section-head span {
  color: var(--muted);
  font-size: 12px;
}

.head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.file-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 20px 14px;
}

.search-box {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 1 1 260px;
  max-width: 430px;
  padding: 5px 7px 5px 11px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  color: var(--muted);
  transition: border-color 0.18s ease;
}

.search-box:focus-within {
  border-color: color-mix(in srgb, var(--brand-2) 45%, transparent);
}

.search-box input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  font-family: inherit;
}

.search-box input::placeholder {
  color: var(--muted-2);
}

.clear-btn {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.clear-btn:hover {
  background: var(--hover);
  color: var(--text);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.field {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 13px;
}

.field select {
  padding: 6px 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  color: var(--text);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
}

.field select:focus-visible,
.search-box input:focus-visible,
.icon-link:focus-visible,
.pager button:focus-visible {
  outline: 2px solid var(--brand-2);
  outline-offset: 1px;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th {
  padding: 10px 20px;
  text-align: left;
  color: var(--muted-2);
  font-size: 11.5px;
  font-weight: 520;
  letter-spacing: 0.04em;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
  white-space: nowrap;
}

td {
  padding: 16px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}

tbody tr:last-child td {
  border-bottom: none;
}

tbody tr {
  transition: background 0.18s ease;
}

tbody tr:hover {
  background: var(--hover);
}

.file-cell {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 160px;
}

.file-badge {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 10px;
  background: linear-gradient(145deg, rgba(109, 93, 252, 0.22), rgba(34, 211, 238, 0.1));
  border: 1px solid rgba(109, 93, 252, 0.24);
  color: var(--brand-light);
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.file-name {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.file-title {
  padding: 0;
  border: 0;
  background: none;
  color: var(--text);
  font: inherit;
  text-align: left;
  cursor: pointer;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 560;
}

.file-title:hover {
  color: var(--brand-light);
  text-decoration: underline;
  text-underline-offset: 3px;
}
.page-size {
  margin-left: auto;
}

.file-name span {
  color: var(--muted-2);
  font-family: var(--mono);
  font-size: 12px;
  margin-top: 2px;
}

.file-name .file-path {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: inherit;
}

.num,
.time {
  white-space: nowrap;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

.time {
  font-size: 12px;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  white-space: nowrap;
}

.status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.status.ok {
  color: var(--ok);
  background: color-mix(in srgb, var(--ok) 12%, transparent);
}

.status.mid {
  color: var(--warn);
  background: color-mix(in srgb, var(--warn) 12%, transparent);
}

.col-actions {
  text-align: right;
  width: 1%;
}

.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.icon-link {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
  transition:
    color 0.18s ease,
    border-color 0.18s ease,
    background 0.18s ease;
}

.icon-link:hover {
  color: var(--brand-2);
  border-color: color-mix(in srgb, var(--brand-2) 40%, transparent);
}

.icon-link.danger:hover {
  color: var(--bad);
  border-color: color-mix(in srgb, var(--bad) 40%, transparent);
}

.empty-files {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 48px 20px 52px;
  border-top: 1px solid var(--border);
  color: var(--muted);
  text-align: center;
}

.empty-files strong {
  color: var(--text);
  font-size: 14px;
  font-weight: 600;
}

.empty-files span {
  font-size: 12px;
  color: var(--muted-2);
}

.empty-files .btn {
  margin-top: 10px;
}

.pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid var(--border);
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.pager-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pager button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.page-indicator {
  min-width: 48px;
  text-align: center;
  color: var(--muted);
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

@media (max-width: 760px) {
  th:nth-child(4),
  td:nth-child(4) {
    display: none;
  }

  .file-toolbar {
    padding: 0 16px 12px;
  }

  .search-box {
    max-width: none;
  }

  .filters {
    width: 100%;
    justify-content: space-between;
  }

  .pager {
    padding: 12px 16px;
  }
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.list-error {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 20px 16px;
  padding: 16px;
  border: 1px solid color-mix(in srgb, var(--bad) 30%, transparent);
  border-radius: 12px;
  color: var(--bad);
  background: color-mix(in srgb, var(--bad) 6%, transparent);
}
.list-error > div {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 3px;
  overflow-wrap: anywhere;
}
.list-error strong {
  font-size: 14px;
}
.list-error span {
  color: var(--muted);
  font-size: 13px;
}
.list-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 180px;
  color: var(--muted);
}
.loading-ring {
  width: 18px;
  height: 18px;
  border: 2px solid var(--border-strong);
  border-top-color: var(--brand-light);
  border-radius: 50%;
  animation: loading-spin 0.8s linear infinite;
}
@keyframes loading-spin {
  to {
    transform: rotate(360deg);
  }
}
.download-button {
  min-width: 78px;
  min-height: 36px;
  box-shadow: none;
}
.pending-label {
  align-self: center;
  color: var(--muted);
  font-size: 12px;
  white-space: nowrap;
}

@media (max-width: 1000px) and (min-width: 761px) {
  th,
  td {
    padding-left: 12px;
    padding-right: 12px;
  }
  .file-title,
  .file-name .file-path {
    max-width: 200px;
  }
}

@media (max-width: 600px) {
  .section-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding: 18px 16px;
  }
  .section-head h2 {
    font-size: 18px;
  }
  .section-head span {
    line-height: 1.8;
  }
  .head-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .table-wrap {
    overflow: visible;
  }
  table,
  tbody {
    display: block;
    width: 100%;
  }
  thead {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
  }
  tbody {
    padding: 0 12px 12px;
  }
  tbody tr {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
    padding: 16px;
    margin-top: 10px;
    border: 1px solid var(--border);
    border-radius: 12px;
    background: var(--panel-2);
  }
  td {
    padding: 0;
    border: 0;
    min-width: 0;
  }
  td.file-column,
  td.col-actions {
    grid-column: 1 / -1;
    width: auto;
  }
  .file-cell {
    min-width: 0;
    align-items: flex-start;
  }
  .file-title {
    max-width: none;
    white-space: normal;
    overflow-wrap: anywhere;
    font-size: 14px;
  }
  .file-name span {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .file-name .file-path {
    max-width: none;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .num::before {
    content: attr(data-label);
    color: var(--muted);
    margin-right: 8px;
  }
  .status-column {
    text-align: right;
  }
  .row-actions {
    padding-top: 12px;
    border-top: 1px solid var(--border);
    gap: 8px;
  }
  .download-button {
    margin-right: auto;
    min-height: 40px;
    padding-inline: 20px;
  }
  .pending-label {
    margin-right: auto;
  }
  .icon-link {
    width: 40px;
    height: 40px;
  }
  .list-error {
    margin-inline: 16px;
  }
}
</style>
