<script setup>
import { computed, ref, watch } from 'vue'
import BaseIcon from './BaseIcon.vue'
import ShareDialog from './ShareDialog.vue'
import SharesPanel from './SharesPanel.vue'
import DownloadPanel from './DownloadPanel.vue'
import FileDetailsDialog from './FileDetailsDialog.vue'
import FilePreviewDialog from './FilePreviewDialog.vue'
import TrashPanel from './TrashPanel.vue'
import MoveDialog from './MoveDialog.vue'
import { batchMove, batchTrash, createFolder, moveFile, trashFolder } from '../lib/api'
import {
  deleteServerFile,
  downloadFile,
  downloadZip,
  enterDir,
  goFilePage,
  refreshActiveView,
  refreshFiles,
  refreshStats,
  refreshTree,
  setViewMode,
  state,
  toast,
  updateFileQuery,
} from '../stores/uploader'
import { formatBytes, formatDate, shortHash } from '../lib/format'

const keyword = ref(state.filePage.keyword)
const sort = ref(state.filePage.sort)
const statusFilter = ref(state.filePage.status === null ? '' : String(state.filePage.status))

const shareFile = ref(null)
const showShares = ref(false)
const detailsFile = ref(null)
const previewFile = ref(null)
const showTrash = ref(false)
const moveTarget = ref(null)
const creatingFolder = ref(false)
const newFolderName = ref('')
const folderBusy = ref(false)
const selectedIds = ref([])
const draggingIds = ref([])
const dropDir = ref(null)

const CATEGORY_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'image', label: '图片' },
  { value: 'video', label: '视频' },
  { value: 'audio', label: '音频' },
  { value: 'document', label: '文档' },
  { value: 'archive', label: '压缩包' },
  { value: 'code', label: '代码' },
]

const activeCategory = computed(() => state.filePage.category || '')
const previewableFiles = computed(() => activeFiles.value.filter((f) => f.status === 1))

const viewMode = computed(() => state.viewMode || 'list')
const tree = computed(
  () =>
    state.tree || {
      currentDir: '',
      breadcrumbs: [{ name: '全部文件', path: '' }],
      folders: [],
      files: [],
    },
)
const activeFiles = computed(() => (viewMode.value === 'tree' ? tree.value.files : state.files))

watch(
  () => state.files,
  (files) => {
    const ids = new Set(files.map((f) => f.fileId))
    selectedIds.value = selectedIds.value.filter((id) => ids.has(id))
    if (!detailsFile.value) return
    detailsFile.value = files.find((file) => file.fileId === detailsFile.value.fileId) || null
  },
)

watch(viewMode, () => {
  selectedIds.value = []
})

const totalPages = computed(() =>
  Math.max(1, Math.ceil(state.filePage.total / state.filePage.size)),
)
const pageStart = computed(() =>
  state.filePage.total === 0 ? 0 : (state.filePage.page - 1) * state.filePage.size + 1,
)
const pageEnd = computed(() =>
  Math.min(state.filePage.total, state.filePage.page * state.filePage.size),
)
const hasFilters = computed(
  () =>
    Boolean(state.filePage.keyword) || statusFilter.value !== '' || Boolean(activeCategory.value),
)

function extension(name) {
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || dot === name.length - 1) return 'FILE'
  return name
    .slice(dot + 1)
    .slice(0, 4)
    .toUpperCase()
}

async function refresh() {
  const loader =
    viewMode.value === 'tree' && refreshTree ? refreshTree(state.currentDir || '') : refreshFiles()
  await Promise.all([loader, refreshStats()])
}

function isSelected(fileId) {
  return selectedIds.value.includes(fileId)
}

function toggleSelect(fileId) {
  if (isSelected(fileId)) {
    selectedIds.value = selectedIds.value.filter((id) => id !== fileId)
  } else {
    selectedIds.value = [...selectedIds.value, fileId]
  }
}

const selectableIds = computed(() =>
  activeFiles.value.filter((f) => f.status === 1).map((f) => f.fileId),
)

const allSelected = computed(
  () => selectableIds.value.length > 0 && selectableIds.value.every((id) => isSelected(id)),
)

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = []
  } else {
    selectedIds.value = [...selectableIds.value]
  }
}

function openMoveFile(file) {
  moveTarget.value = {
    mode: 'file',
    file,
    initialDir: state.currentDir || '',
  }
}

function openMoveBatch() {
  if (!selectedIds.value.length) return
  moveTarget.value = {
    mode: 'batch',
    fileIds: [...selectedIds.value],
    initialDir: state.currentDir || '',
  }
}

function openMoveFolder(folderPath) {
  moveTarget.value = {
    mode: 'folder',
    folderPath,
    initialDir: state.currentDir || '',
  }
}

async function submitNewFolder() {
  const raw = newFolderName.value
    .trim()
    .replace(/\\/g, '/')
    .replace(/^\/+|\/+$/g, '')
  if (!raw || folderBusy.value) return
  const base = state.currentDir ? `${state.currentDir}/` : ''
  const target = viewMode.value === 'tree' && !raw.includes('/') && base ? `${base}${raw}` : raw
  folderBusy.value = true
  try {
    await createFolder(target)
    toast(`已创建文件夹 ${target}`, 'success')
    newFolderName.value = ''
    creatingFolder.value = false
    if (viewMode.value === 'tree') {
      await refreshTree(state.currentDir || '')
    } else {
      await setViewMode('tree')
    }
  } catch (e) {
    toast(`创建文件夹失败：${e.message}`, 'error')
  } finally {
    folderBusy.value = false
  }
}

async function removeFolder(folder) {
  if (!window.confirm(`确认将文件夹「${folder.path}」及其中文件移入回收站？`)) return
  try {
    await trashFolder(folder.path)
    toast(`已将文件夹 ${folder.path} 移入回收站`, 'success')
    await Promise.all([refreshTree(state.currentDir || ''), refreshStats()])
  } catch (e) {
    toast(`操作失败：${e.message}`, 'error')
  }
}

async function trashSelected() {
  if (!selectedIds.value.length) return
  if (!window.confirm(`确认将选中的 ${selectedIds.value.length} 个文件移入回收站？`)) return
  try {
    const res = await batchTrash(selectedIds.value)
    toast(`已将 ${res.succeeded} 个文件移入回收站`, 'success')
    selectedIds.value = []
    await Promise.all([refreshActiveView ? refreshActiveView() : refreshFiles(), refreshStats()])
  } catch (e) {
    toast(`批量删除失败：${e.message}`, 'error')
  }
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

function selectCategory(category) {
  const next = activeCategory.value === category ? '' : category
  updateFileQuery({ category: next })
}

function resetFilters() {
  keyword.value = ''
  sort.value = 'time_desc'
  statusFilter.value = ''
  updateFileQuery({
    keyword: '',
    status: null,
    sort: 'time_desc',
    ...(activeCategory.value ? { category: '' } : {}),
  })
}

function onFileDragStart(file, event) {
  if (file.status !== 1) return
  const ids = selectedIds.value.includes(file.fileId) ? [...selectedIds.value] : [file.fileId]
  draggingIds.value = ids
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', ids.join(','))
  }
}

function onFileDragEnd() {
  draggingIds.value = []
  dropDir.value = null
}

function canDropOnDir(targetDir) {
  return (
    typeof targetDir === 'string' &&
    draggingIds.value.length > 0 &&
    targetDir !== (tree.value.currentDir || '')
  )
}

function onDirDragOver(targetDir, event) {
  if (!canDropOnDir(targetDir)) return
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
  dropDir.value = targetDir
}

function onDirDragLeave(targetDir) {
  if (dropDir.value === targetDir) {
    dropDir.value = null
  }
}

async function onDirDrop(targetDir, event) {
  event.preventDefault()
  const raw = event.dataTransfer?.getData('text/plain') || ''
  const ids =
    draggingIds.value.length > 0
      ? [...draggingIds.value]
      : raw
          .split(',')
          .map((item) => item.trim())
          .filter(Boolean)
  draggingIds.value = []
  dropDir.value = null
  if (typeof targetDir !== 'string' || !ids.length || targetDir === (tree.value.currentDir || '')) {
    return
  }
  folderBusy.value = true
  try {
    if (ids.length === 1) {
      await moveFile(ids[0], targetDir)
    } else {
      await batchMove(ids, targetDir)
    }
    selectedIds.value = []
    await refreshActiveView()
    toast(`已移动 ${ids.length} 个文件到${targetDir ? `「${targetDir}」` : '根目录'}`, 'success')
  } catch (err) {
    toast(err.message || '拖拽移动失败', 'error')
  } finally {
    folderBusy.value = false
  }
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
        <div class="view-switch" role="group" aria-label="视图切换">
          <button
            class="btn btn-sm"
            :class="viewMode === 'list' ? 'btn-primary' : 'btn-ghost'"
            type="button"
            data-testid="view-mode-list"
            @click="setViewMode('list')"
          >
            <BaseIcon name="list" :size="14" />
            列表
          </button>
          <button
            class="btn btn-sm"
            :class="viewMode === 'tree' ? 'btn-primary' : 'btn-ghost'"
            type="button"
            data-testid="view-mode-tree"
            @click="setViewMode('tree')"
          >
            <BaseIcon name="folder" :size="14" />
            目录
          </button>
        </div>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          data-testid="new-folder-button"
          @click="creatingFolder = !creatingFolder"
        >
          <BaseIcon name="folder-plus" :size="14" />
          新建目录
        </button>
        <button
          class="btn btn-ghost btn-sm"
          type="button"
          data-testid="trash-button"
          @click="showTrash = true"
        >
          <BaseIcon name="trash" :size="14" />
          回收站
        </button>
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
          :disabled="state.filesLoading || state.treeLoading"
          @click="refresh"
        >
          <BaseIcon name="refresh" :size="14" />
          {{ state.filesLoading || state.treeLoading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </div>

    <div v-if="creatingFolder" class="new-folder-bar" data-testid="new-folder-bar">
      <BaseIcon name="folder-plus" :size="15" />
      <input
        v-model="newFolderName"
        type="text"
        placeholder="输入目录名（如 docs 或 docs/2026）"
        aria-label="新文件夹路径"
        @keydown.enter.prevent="submitNewFolder"
      />
      <button
        class="btn btn-primary btn-sm"
        type="button"
        :disabled="folderBusy || !newFolderName.trim()"
        @click="submitNewFolder"
      >
        创建
      </button>
      <button class="btn btn-ghost btn-sm" type="button" @click="creatingFolder = false">
        取消
      </button>
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
        <label v-show="viewMode === 'list'" class="field">
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

    <div class="category-bar" data-testid="category-bar" role="group" aria-label="按文件类型筛选">
      <button
        v-for="item in CATEGORY_OPTIONS"
        :key="item.value || 'all'"
        type="button"
        class="category-chip"
        :class="{ active: activeCategory === item.value }"
        :data-testid="`category-chip-${item.value || 'all'}`"
        @click="selectCategory(item.value)"
      >
        {{ item.label }}
      </button>
    </div>

    <div v-if="selectedIds.length" class="batch-bar" data-testid="batch-bar">
      <span>已选 {{ selectedIds.length }} 个文件</span>
      <div class="batch-actions">
        <button
          class="btn btn-primary btn-sm"
          type="button"
          @click="downloadZip({ fileIds: selectedIds })"
        >
          <BaseIcon name="archive" :size="14" />
          打包下载 (.zip)
        </button>
        <button class="btn btn-ghost btn-sm" type="button" @click="openMoveBatch">
          <BaseIcon name="move" :size="14" />
          批量移动
        </button>
        <button class="btn btn-danger btn-sm" type="button" @click="trashSelected">
          <BaseIcon name="trash" :size="14" />
          移入回收站
        </button>
        <button class="btn btn-ghost btn-sm" type="button" @click="selectedIds = []">
          取消选择
        </button>
      </div>
    </div>

    <template v-if="viewMode === 'list'">
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
              <th scope="col" class="col-check">
                <input
                  type="checkbox"
                  aria-label="全选当前页"
                  :checked="allSelected"
                  :disabled="!selectableIds.length"
                  @change="toggleSelectAll"
                />
              </th>
              <th scope="col">文件</th>
              <th scope="col">大小</th>
              <th scope="col">状态</th>
              <th scope="col">上传时间</th>
              <th scope="col" class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="file in state.files" :key="file.fileId">
              <td class="col-check">
                <input
                  type="checkbox"
                  :aria-label="`选择 ${file.fileName}`"
                  :checked="isSelected(file.fileId)"
                  :disabled="file.status !== 1"
                  @change="toggleSelect(file.fileId)"
                />
              </td>
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
                    title="在线预览"
                    :aria-label="`预览 ${file.relativePath || file.fileName}`"
                    data-testid="preview-file-button"
                    @click="previewFile = file"
                  >
                    <BaseIcon name="eye" :size="15" />
                  </button>
                  <button
                    v-if="file.status === 1"
                    class="icon-link"
                    type="button"
                    title="移动 / 重命名"
                    :aria-label="`移动 ${file.relativePath || file.fileName}`"
                    data-testid="move-file-button"
                    @click="openMoveFile(file)"
                  >
                    <BaseIcon name="move" :size="15" />
                  </button>
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
            <option v-for="size in [10, 20, 50, 100]" :key="size" :value="size">
              {{ size }} 条
            </option>
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
    </template>

    <template v-else>
      <nav class="breadcrumbs" aria-label="目录路径" data-testid="breadcrumbs">
        <template v-for="(crumb, idx) in tree.breadcrumbs" :key="crumb.path">
          <span v-if="idx > 0" class="crumb-sep">
            <BaseIcon name="chevron-right" :size="13" />
          </span>
          <button
            class="crumb-btn"
            type="button"
            :class="{
              active: idx === tree.breadcrumbs.length - 1,
              'drop-target': dropDir === crumb.path,
            }"
            @click="enterDir(crumb.path)"
            @dragover="onDirDragOver(crumb.path, $event)"
            @dragleave="onDirDragLeave(crumb.path)"
            @drop="onDirDrop(crumb.path, $event)"
          >
            {{ crumb.name }}
          </button>
        </template>
      </nav>

      <div v-if="state.treeError" class="list-error" role="alert">
        <BaseIcon name="alert" :size="19" />
        <div>
          <strong>目录加载失败</strong><span>{{ state.treeError }}</span>
        </div>
        <button class="btn btn-sm" type="button" :disabled="state.treeLoading" @click="refresh">
          重试
        </button>
      </div>

      <div v-if="state.treeLoading" class="list-loading" aria-live="polite">
        <span class="loading-ring" aria-hidden="true"></span> 正在加载目录…
      </div>

      <div
        v-else-if="!tree.folders.length && !tree.files.length && !state.treeError"
        class="empty-files"
      >
        <div class="empty-icon"><BaseIcon name="folder" :size="26" /></div>
        <strong>当前目录为空</strong>
        <span>点击右上角「新建目录」，或上传带目录的文件</span>
      </div>

      <div v-else-if="!state.treeError" class="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col" class="col-check">
                <input
                  type="checkbox"
                  aria-label="全选当前目录"
                  :checked="allSelected"
                  :disabled="!selectableIds.length"
                  @change="toggleSelectAll"
                />
              </th>
              <th scope="col">名称</th>
              <th scope="col">大小</th>
              <th scope="col">状态</th>
              <th scope="col">修改时间</th>
              <th scope="col" class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="folder in tree.folders"
              :key="`dir:${folder.path}`"
              class="folder-row"
              :class="{ 'drop-target': dropDir === folder.path }"
              :data-drop-dir="folder.path"
              @dragover="onDirDragOver(folder.path, $event)"
              @dragleave="onDirDragLeave(folder.path)"
              @drop="onDirDrop(folder.path, $event)"
            >
              <td class="col-check"></td>
              <td class="file-column">
                <div class="file-cell">
                  <span class="file-badge folder-badge">
                    <BaseIcon name="folder" :size="14" />
                  </span>
                  <div class="file-name">
                    <button class="file-title" type="button" @click="enterDir(folder.path)">
                      {{ folder.name }}
                    </button>
                    <span>{{ folder.fileCount }} 个文件</span>
                  </div>
                </div>
              </td>
              <td class="num">{{ formatBytes(folder.totalBytes) }}</td>
              <td>
                <span class="status ok"><i></i>目录</span>
              </td>
              <td class="time">—</td>
              <td class="col-actions">
                <div class="row-actions">
                  <button
                    class="btn btn-ghost btn-sm"
                    type="button"
                    @click="downloadZip({ folderPath: folder.path })"
                  >
                    <BaseIcon name="archive" :size="14" />
                    打包下载
                  </button>
                  <button
                    class="icon-link"
                    type="button"
                    title="重命名 / 移动目录"
                    @click="openMoveFolder(folder.path)"
                  >
                    <BaseIcon name="move" :size="15" />
                  </button>
                  <button
                    class="icon-link danger"
                    type="button"
                    title="移入回收站"
                    @click="removeFolder(folder)"
                  >
                    <BaseIcon name="trash" :size="15" />
                  </button>
                </div>
              </td>
            </tr>
            <tr
              v-for="file in tree.files"
              :key="file.fileId"
              :draggable="file.status === 1"
              :class="{ dragging: draggingIds.includes(file.fileId) }"
              @dragstart="onFileDragStart(file, $event)"
              @dragend="onFileDragEnd"
            >
              <td class="col-check">
                <input
                  type="checkbox"
                  :aria-label="`选择 ${file.fileName}`"
                  :checked="isSelected(file.fileId)"
                  :disabled="file.status !== 1"
                  @change="toggleSelect(file.fileId)"
                />
              </td>
              <td class="file-column">
                <div class="file-cell">
                  <span class="file-badge">{{ extension(file.fileName) }}</span>
                  <div class="file-name">
                    <button class="file-title" type="button" @click="detailsFile = file">
                      {{ file.fileName }}
                    </button>
                    <span :title="file.fileHash">{{ shortHash(file.fileHash) }}</span>
                  </div>
                </div>
              </td>
              <td class="num">{{ formatBytes(file.fileSize) }}</td>
              <td class="status-column">
                <span class="status" :class="file.status === 1 ? 'ok' : 'mid'">
                  <i></i>{{ file.status === 1 ? '已完成' : '上传中' }}
                </span>
              </td>
              <td class="time">{{ formatDate(file.createTime) }}</td>
              <td class="col-actions">
                <div class="row-actions">
                  <button
                    v-if="file.status === 1"
                    class="btn btn-primary btn-sm download-button"
                    type="button"
                    :disabled="preparingDownload(file.fileId)"
                    @click="downloadFile(file)"
                  >
                    <BaseIcon name="download" :size="16" />
                    下载
                  </button>
                  <button
                    v-if="file.status === 1"
                    class="icon-link"
                    type="button"
                    title="在线预览"
                    @click="previewFile = file"
                  >
                    <BaseIcon name="eye" :size="15" />
                  </button>
                  <button
                    v-if="file.status === 1"
                    class="icon-link"
                    type="button"
                    title="移动 / 重命名"
                    @click="openMoveFile(file)"
                  >
                    <BaseIcon name="move" :size="15" />
                  </button>
                  <button
                    v-if="file.status === 1"
                    class="icon-link"
                    type="button"
                    title="分享"
                    @click="shareFile = file"
                  >
                    <BaseIcon name="link" :size="15" />
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
    </template>

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
  <FilePreviewDialog
    v-if="previewFile"
    :file="previewFile"
    :items="previewableFiles"
    @select="previewFile = $event"
    @close="previewFile = null"
    @download="downloadFile(previewFile)"
  />
  <TrashPanel v-if="showTrash" @close="showTrash = false" />
  <MoveDialog v-if="moveTarget" v-bind="moveTarget" @close="moveTarget = null" />
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

.view-switch {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
}

.new-folder-bar,
.batch-bar,
.breadcrumbs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
}

.new-folder-bar input {
  flex: 1 1 220px;
  min-width: 180px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  color: var(--text);
  font-size: 13px;
}

.batch-bar {
  justify-content: space-between;
  background: color-mix(in srgb, var(--brand) 10%, var(--panel));
}

.batch-bar span {
  font-size: 13px;
  font-weight: 600;
}

.batch-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.breadcrumbs {
  gap: 4px;
  font-size: 13px;
}

.crumb-sep {
  display: inline-flex;
  color: var(--muted-2);
}

.crumb-btn {
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
  padding: 3px 6px;
  border-radius: 6px;
  cursor: pointer;
}

.crumb-btn:hover {
  background: var(--hover);
  color: var(--text);
}

.crumb-btn.active {
  color: var(--text);
  font-weight: 600;
}

.col-check {
  width: 34px;
  text-align: center;
}

.folder-badge {
  color: var(--brand-light);
}

.category-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.category-chip {
  appearance: none;
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text-muted);
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.16s ease;
}

.category-chip:hover {
  color: var(--text);
  border-color: var(--brand-border);
}

.category-chip.active {
  background: var(--brand-dim);
  color: var(--brand-light);
  border-color: var(--brand-border);
}

.crumb-btn.drop-target {
  background: var(--brand-dim);
  color: var(--brand-light);
  outline: 1px dashed var(--brand-light);
}

.folder-row.drop-target td {
  background: var(--brand-dim);
}

tr.dragging {
  opacity: 0.55;
}
</style>
