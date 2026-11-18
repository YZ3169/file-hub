<script setup>
import BaseIcon from './BaseIcon.vue'
import {
  deleteServerFile,
  downloadFile,
  refreshFiles,
  refreshStats,
  state,
} from '../stores/uploader'
import { formatBytes, formatDate, shortHash } from '../lib/format'

function extension(name) {
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || dot === name.length - 1) return 'FILE'
  return name.slice(dot + 1).slice(0, 4).toUpperCase()
}

async function refresh() {
  await Promise.all([refreshFiles(), refreshStats()])
}
</script>

<template>
  <section class="file-section">
    <div class="section-head">
      <div>
        <h2>云端文件</h2>
        <span>共 {{ state.files.length }} 条记录 · 秒传产生的多条记录共享同一份物理文件</span>
      </div>
      <button class="btn btn-ghost btn-sm" type="button" @click="refresh">
        <BaseIcon name="refresh" :size="14" />
        刷新
      </button>
    </div>

    <div v-if="!state.files.length" class="empty-files">
      <div class="empty-icon"><BaseIcon name="archive" :size="26" /></div>
      <strong>还没有文件</strong>
      <span>上传第一个文件，或者拖一份大文件试试断点续传</span>
    </div>

    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>文件</th>
            <th>大小</th>
            <th>状态</th>
            <th>时间</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="file in state.files" :key="file.fileId">
            <td>
              <div class="file-cell">
                <span class="file-badge">{{ extension(file.fileName) }}</span>
                <div class="file-name">
                  <strong :title="file.fileName">{{ file.fileName }}</strong>
                  <span :title="file.fileHash">{{ shortHash(file.fileHash) }}</span>
                </div>
              </div>
            </td>
            <td class="num">{{ formatBytes(file.fileSize) }}</td>
            <td>
              <span class="status" :class="file.status === 1 ? 'ok' : 'mid'">
                <i></i>
                {{ file.status === 1 ? '已完成' : '上传中' }}
              </span>
            </td>
            <td class="time">{{ formatDate(file.createTime) }}</td>
            <td class="col-actions">
              <div class="row-actions">
                <button
                  v-if="file.status === 1"
                  class="icon-link"
                  type="button"
                  title="下载（支持 Range 断点续传）"
                  @click="downloadFile(file)"
                >
                  <BaseIcon name="download" :size="15" />
                </button>
                <button
                  class="icon-link danger"
                  type="button"
                  title="删除"
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
  </section>
</template>

<style scoped>
.file-section {
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
  margin: 0 0 3px;
  font-size: 15.5px;
  font-weight: 650;
}

.section-head span {
  color: var(--muted);
  font-size: 12px;
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
  padding: 12px 20px;
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
  min-width: 220px;
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

.file-name strong {
  max-width: 380px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 560;
}

.file-name span {
  color: var(--muted-2);
  font-family: var(--mono);
  font-size: 10.5px;
  margin-top: 2px;
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
  width: 31px;
  height: 31px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
  transition: color 0.18s ease, border-color 0.18s ease, background 0.18s ease;
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
}
</style>
