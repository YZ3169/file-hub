<script setup>
import { computed } from 'vue'
import BaseIcon from './BaseIcon.vue'
import { auth } from '../lib/auth'
import { logout } from '../stores/uploader'

const initial = computed(() => (auth.user?.username || '?').slice(0, 1).toUpperCase())
const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const roleLabel = computed(() => (isAdmin.value ? '管理员' : '普通用户'))
</script>

<template>
  <div class="user-menu" data-testid="user-menu">
    <span class="avatar" aria-hidden="true">{{ initial }}</span>
    <div class="user-meta">
      <strong :title="auth.user?.username">{{ auth.user?.username }}</strong>
      <span class="role" :class="{ admin: isAdmin }">{{ roleLabel }}</span>
    </div>
    <button
      class="icon-btn"
      type="button"
      title="退出登录"
      aria-label="退出登录"
      data-testid="logout-button"
      @click="logout"
    >
      <BaseIcon name="logout" :size="16" />
    </button>
  </div>
</template>

<style scoped>
.user-menu {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 4px 3px 3px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--panel-2);
}

.avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: 9px;
  background: linear-gradient(145deg, rgba(109, 93, 252, 0.35), rgba(34, 211, 238, 0.18));
  color: var(--text);
  font-size: 12px;
  font-weight: 700;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
  min-width: 0;
}

.user-meta strong {
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 600;
}

.role {
  color: var(--muted-2);
  font-size: 9.5px;
  letter-spacing: 0.03em;
}

.role.admin {
  color: var(--brand-2);
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.icon-btn:hover {
  background: var(--hover);
  color: var(--bad);
}

@media (max-width: 640px) {
  .user-meta {
    display: none;
  }
}
</style>
