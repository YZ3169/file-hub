<script setup>
import { computed, onMounted, ref } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { batchMove, getFolderTree, moveFile, moveFolder } from '../lib/api'
import { refreshActiveView, toast } from '../stores/uploader'

const props = defineProps({
  /** 'file' | 'batch' | 'folder' */
  mode: { type: String, required: true },
  file: { type: Object, default: null },
  fileIds: { type: Array, default: () => [] },
  folderPath: { type: String, default: '' },
  initialDir: { type: String, default: '' },
})

const emit = defineEmits(['close'])

const parentOf = (rel) => {
  if (!rel || !rel.includes('/')) return ''
  return rel.slice(0, rel.lastIndexOf('/'))
}
const leafOf = (path) =>
  path && path.includes('/') ? path.slice(path.lastIndexOf('/') + 1) : path || ''

const targetDir = ref(
  props.mode === 'file'
    ? parentOf(props.file?.relativePath || '')
    : props.mode === 'folder'
      ? props.folderPath
      : props.initialDir,
)
const newName = ref(props.mode === 'file' ? props.file?.fileName || '' : '')
const busy = ref(false)
const error = ref('')

const pickerDir = ref(
  props.mode === 'folder'
    ? parentOf(props.folderPath || '')
    : props.mode === 'file'
      ? parentOf(props.file?.relativePath || '')
      : props.initialDir || '',
)
const pickerCrumbs = ref([{ name: '全部文件', path: '' }])
const pickerFolders = ref([])
const pickerLoading = ref(false)

async function loadPicker(dir = pickerDir.value) {
  pickerLoading.value = true
  try {
    const res = await getFolderTree({ dir })
    pickerDir.value = res.currentDir || ''
    pickerCrumbs.value = res.breadcrumbs?.length
      ? res.breadcrumbs
      : [{ name: '全部文件', path: '' }]
    const all = res.folders || []
    pickerFolders.value =
      props.mode === 'folder'
        ? all.filter(
            (f) => f.path !== props.folderPath && !f.path.startsWith(`${props.folderPath}/`),
          )
        : all
  } catch {
    pickerFolders.value = []
  } finally {
    pickerLoading.value = false
  }
}

async function selectPickerDir(dir) {
  const safeDir = dir ?? ''
  await loadPicker(safeDir)
  if (props.mode === 'folder') {
    const leaf = leafOf(props.folderPath)
    targetDir.value = safeDir ? `${safeDir}/${leaf}` : leaf
  } else {
    targetDir.value = safeDir
  }
}

onMounted(() => loadPicker(pickerDir.value))

const title = computed(() => {
  if (props.mode === 'folder') return '重命名 / 移动文件夹'
  if (props.mode === 'batch') return `批量移动 ${props.fileIds.length} 个文件`
  return '移动 / 重命名文件'
})

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (props.mode === 'file') {
      await moveFile(props.file.fileId, targetDir.value.trim(), newName.value.trim())
      toast('文件位置/名称已更新', 'success')
    } else if (props.mode === 'batch') {
      const res = await batchMove(props.fileIds, targetDir.value.trim())
      toast(`已移动 ${res.succeeded} 个文件`, 'success')
    } else if (props.mode === 'folder') {
      await moveFolder(props.folderPath, targetDir.value.trim())
      toast('文件夹已更新', 'success')
    }
    await refreshActiveView()
    emit('close')
  } catch (e) {
    error.value = e.message || '操作失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <section
      class="modal-dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="move-title"
      @keydown.esc.prevent="emit('close')"
    >
      <header class="modal-head">
        <h2 id="move-title"><BaseIcon name="move" :size="17" /> {{ title }}</h2>
        <button class="icon-btn" type="button" aria-label="关闭" @click="emit('close')">
          <BaseIcon name="x" :size="16" />
        </button>
      </header>

      <form class="modal-body" @submit.prevent="submit">
        <div class="picker-box" data-testid="folder-picker">
          <div class="picker-crumbs">
            <button
              v-for="(c, idx) in pickerCrumbs"
              :key="c.path"
              class="crumb"
              type="button"
              :class="{ active: idx === pickerCrumbs.length - 1 }"
              @click="selectPickerDir(c.path)"
            >
              {{ idx > 0 ? `/ ${c.name}` : '根目录' }}
            </button>
          </div>
          <div v-if="pickerLoading" class="picker-empty">加载目录…</div>
          <div v-else-if="!pickerFolders.length" class="picker-empty">当前目录下无子文件夹</div>
          <div v-else class="picker-list">
            <button
              v-for="f in pickerFolders"
              :key="f.path"
              class="picker-item"
              type="button"
              @click="selectPickerDir(f.path)"
            >
              <BaseIcon name="folder" :size="14" />
              <span>{{ f.name }}</span>
            </button>
          </div>
        </div>

        <label class="field">
          <span>{{
            mode === 'folder' ? '新文件夹路径（例如 docs/2026）' : '目标目录（留空表示根目录）'
          }}</span>
          <input
            v-model="targetDir"
            type="text"
            :placeholder="mode === 'folder' ? 'docs/archive' : '例如 docs/2026，留空放根目录'"
            autocomplete="off"
          />
        </label>

        <label v-if="mode === 'file'" class="field">
          <span>文件名</span>
          <input v-model="newName" type="text" required autocomplete="off" />
        </label>

        <p v-if="error" class="error" role="alert">{{ error }}</p>

        <footer class="modal-foot">
          <button class="btn btn-ghost btn-sm" type="button" @click="emit('close')">取消</button>
          <button class="btn btn-primary btn-sm" type="submit" :disabled="busy">
            {{ busy ? '保存中…' : '确认保存' }}
          </button>
        </footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 55;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(5, 8, 18, 0.72);
  backdrop-filter: blur(8px);
}

.modal-dialog {
  width: min(460px, 100%);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  background: var(--bg);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
  overflow: hidden;
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 18px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.modal-head h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 16px;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel-2);
  color: var(--muted);
  cursor: pointer;
}

.modal-body {
  display: grid;
  gap: 14px;
  padding: 18px;
}

.field {
  display: grid;
  gap: 6px;
}

.field span {
  font-size: 12.5px;
  color: var(--muted);
}

.field input {
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-2);
  font-size: 13.5px;
}

.error {
  margin: 0;
  color: var(--bad);
  font-size: 12.5px;
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}

.picker-box {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel-2);
  overflow: hidden;
}

.picker-crumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--panel);
}

.crumb {
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 5px;
  border-radius: 5px;
}

.crumb:hover,
.crumb.active {
  color: var(--text);
  background: var(--hover);
}

.picker-list {
  display: grid;
  max-height: 150px;
  overflow-y: auto;
  padding: 4px;
  gap: 2px;
}

.picker-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.picker-item:hover {
  background: var(--hover);
}

.picker-empty {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--muted);
}
</style>
