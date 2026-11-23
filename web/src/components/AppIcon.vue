<script setup>
defineProps({
  size: { type: [Number, String], default: 42 },
})
</script>

<template>
  <img
    class="app-icon"
    src="/favicon.svg"
    :width="size"
    :height="size"
    alt=""
    aria-hidden="true"
    draggable="false"
  />
</template>

<style scoped>
.app-icon {
  display: block;
  flex: none;
  user-select: none;
}
</style>
