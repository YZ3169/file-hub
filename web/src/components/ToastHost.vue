<script setup>
import BaseIcon from './BaseIcon.vue'
import { state } from '../stores/uploader'

const icons = {
  success: 'check',
  error: 'alert',
  warn: 'alert',
  info: 'spark',
}
</script>

<template>
  <div class="toasts" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in state.toasts" :key="t.id" class="toast" :class="`is-${t.type}`">
        <span class="toast-icon">
          <BaseIcon :name="icons[t.type] || 'spark'" :size="15" />
        </span>
        <span class="toast-text">{{ t.text }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toasts {
  position: fixed;
  right: 22px;
  bottom: 22px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 9px;
  max-width: min(420px, calc(100vw - 36px));
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 11px 14px;
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--toast-bg);
  box-shadow: 0 18px 44px rgba(0, 0, 0, 0.32);
  backdrop-filter: blur(16px);
  color: var(--text);
  font-size: 12.5px;
  line-height: 1.5;
  pointer-events: auto;
}

.toast-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: var(--panel-2);
}

.is-success .toast-icon { color: var(--ok); }
.is-error .toast-icon { color: var(--bad); }
.is-warn .toast-icon { color: var(--warn); }
.is-info .toast-icon { color: var(--brand-2); }

.toast-text {
  padding-top: 1px;
  overflow-wrap: anywhere;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px) scale(0.98);
}
</style>
