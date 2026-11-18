<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 18 },
  strokeWidth: { type: [Number, String], default: 1.8 },
})

const paths = {
  upload: ['M12 16V4', 'm7 9 5-5 5 5', 'M5 20h14'],
  download: ['M12 4v11', 'm7 10 5 5 5-5', 'M5 20h14'],
  cloud: [
    'M17.5 19H7a4 4 0 0 1-.55-7.96A6 6 0 0 1 18 9.5a4.75 4.75 0 0 1-.5 9.5Z',
    'M12 12v7',
    'm9 16 3 3 3-3',
  ],
  bolt: ['m13 2-9 12h7l-1 8 9-12h-7l1-8Z'],
  check: ['m5 12 4.5 4.5L19 7'],
  pause: ['M9 5v14', 'M15 5v14'],
  play: ['m8 5 11 7-11 7V5Z'],
  x: ['M6 6l12 12', 'M18 6 6 18'],
  refresh: ['M21 12a9 9 0 1 1-2.64-6.36', 'M21 3v6h-6'],
  trash: ['M4 7h16', 'M9 7V4h6v3', 'm6 7 1 13h10l1-13', 'M10 11v5', 'M14 11v5'],
  sun: [
    'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z',
    'M12 2v2M12 20v2M4 12H2M22 12h-2',
    'M5.6 5.6 4.2 4.2M19.8 19.8l-1.4-1.4M18.4 5.6l1.4-1.4M4.2 19.8l1.4-1.4',
  ],
  moon: ['M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z'],
  file: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z', 'M14 2v6h6'],
  shield: ['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z', 'm9 12 2 2 4-4'],
  link: [
    'M10 13a5 5 0 0 0 7.07 0l2.12-2.12a5 5 0 0 0-7.07-7.07L11 5',
    'M14 11a5 5 0 0 0-7.07 0L4.8 13.1a5 5 0 0 0 7.07 7.07L13 19',
  ],
  layers: ['m12 2 9 5-9 5-9-5 9-5Z', 'm3 12 9 5 9-5', 'm3 17 9 5 9-5'],
  spark: ['m12 3 1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3Z'],
  alert: [
    'M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z',
    'M12 9v4',
    'M12 17h.01',
  ],
  server: ['M4 4h16v6H4z', 'M4 14h16v6H4z', 'M8 7h.01', 'M8 17h.01'],
  clock: ['M12 8v4l3 2', 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Z'],
  layers2: ['M12 2 2 7l10 5 10-5-10-5Z', 'm2 12 10 5 10-5', 'm2 17 10 5 10-5'],
  flow: ['M6 4v16', 'M18 4v16', 'M6 8h12', 'M6 16h12'],
  hash: ['M4 9h16', 'M4 15h16', 'M10 3 8 21', 'M16 3l-2 18'],
  gauge: ['M12 14l4-4', 'M3.5 18a9 9 0 1 1 17 0'],
  archive: ['M3 4h18v4H3z', 'M5 8v12h14V8', 'M10 12h4'],
}

const iconPaths = computed(() => paths[props.name] || paths.file)
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, i) in iconPaths" :key="i" :d="d" />
  </svg>
</template>
