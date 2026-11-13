<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps<{ range: '7天' | '30天' | '本学期'; points: number[]; labels: string[] }>()
const frame = ref<HTMLElement>()
const width = ref(700)
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(entries => { width.value = Math.max(300, entries[0]?.contentRect.width ?? 700) })
  if (frame.value) observer.observe(frame.value)
})
onBeforeUnmount(() => observer?.disconnect())
const maximum = computed(() => Math.max(4, Math.ceil(Math.max(0, ...props.points) / 4) * 4))
const coords = computed(() => props.points.map((value, index) => ({ x: 36 + index * ((width.value - 52) / Math.max(1, props.points.length - 1)), y: 194 - value / maximum.value * 168, value, label: props.labels[index] ?? '' })))
const linePath = computed(() => coords.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const total = computed(() => props.points.reduce((sum, value) => sum + value, 0))
</script>

<template>
  <figure ref="frame" class="trend-chart-frame">
    <svg class="trend-chart" :viewBox="`0 0 ${width} 226`" role="img" :aria-label="`${range}每日发帖量，共${total}篇`">
      <g v-for="index in 5" :key="index">
        <line x1="36" :x2="width - 16" :y1="26 + (index - 1) * 42" :y2="26 + (index - 1) * 42" stroke="#e8e8e8" />
        <text x="26" :y="30 + (index - 1) * 42" text-anchor="end" fill="#666666" font-size="12">{{ maximum * (5 - index) / 4 }}</text>
      </g>
      <path v-if="coords.length" :d="linePath" fill="none" stroke="#171717" stroke-width="2" />
      <g v-for="(point, index) in coords" :key="index">
        <circle :cx="point.x" :cy="point.y" r="10" fill="transparent"><title>{{ point.label }}：{{ point.value }} 篇帖子</title></circle>
        <circle :cx="point.x" :cy="point.y" r="2.5" fill="#171717" pointer-events="none" />
        <text v-if="index % Math.max(1, Math.ceil(coords.length / 7)) === 0" :x="point.x" y="219" text-anchor="middle" fill="#666666" font-size="12">{{ point.label }}</text>
      </g>
    </svg>
    <figcaption>本期共 <strong>{{ total }}</strong> 篇帖子<span v-if="!coords.length"> · 暂无发帖记录</span></figcaption>
  </figure>
</template>

<style scoped>
.trend-chart-frame { margin: 0; width: 100%; min-width: 0; }
.trend-chart { display: block; width: 100%; height: 226px; }
figcaption { margin-top: 8px; color: #666666; font-size: 13px; }
figcaption strong { color: #202020; font-variant-numeric: tabular-nums; }
</style>
