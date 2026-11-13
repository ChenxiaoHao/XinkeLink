<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  items: Array<{ label: string; value: number | string; unit: string; note: string; icon: Component; danger?: boolean }>
}>()
</script>

<template>
  <section class="metrics-grid content-metrics" aria-label="内容管理统计">
    <article v-for="item in items" :key="item.label" class="metric-card">
      <div class="metric-top">
        <div><div class="metric-label">{{ item.label }}</div></div>
      </div>
      <div class="metric-value" :class="{ 'is-danger-text': item.danger }">{{ item.value }}<small>{{ item.unit }}</small></div>
      <div class="metric-foot" :class="{ 'is-alert': item.danger }">{{ item.note }}</div>
    </article>
  </section>
</template>

<style scoped>
.content-metrics { margin-bottom: 15px; }
.metric-foot { min-height: 14px; }
.metric-value.is-danger-text { color: #b42318; }
</style>
