<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  items: Array<{ label: string; value: number | string; unit: string; note: string; icon: Component; danger?: boolean }>
}>()
</script>

<template>
  <section class="metrics-grid content-metrics" :style="{ '--metric-count': items.length }" aria-label="内容管理统计">
    <article v-for="item in items" :key="item.label" class="metric-card content-metric-card">
      <div class="content-metric-copy">
        <div class="metric-label">{{ item.label }}</div>
        <div class="metric-value" :class="{ 'is-danger-text': item.danger }">{{ item.value }}<small>{{ item.unit }}</small></div>
      </div>
      <span class="metric-icon" :class="{ 'is-danger': item.danger }"><component :is="item.icon" aria-hidden="true" /></span>
    </article>
  </section>
</template>

<style scoped>
.content-metrics { margin-bottom: 12px; gap: 12px; }
.content-metric-card {
  min-height: 64px;
  padding: 12px 18px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.content-metric-copy { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.content-metric-card .metric-label { font-size: 12px; line-height: 1.2; }
.content-metric-card .metric-value { margin-top: 0; font-size: 24px; line-height: 1.1; }
.content-metric-card .metric-icon { width: 34px; height: 34px; border-radius: 10px; }
.content-metric-card .metric-icon svg { width: 17px; height: 17px; }
.metric-value.is-danger-text { color: var(--xlk-accent-rose); }
</style>
