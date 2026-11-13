<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Construction, MoveLeft } from '@lucide/vue'

const route = useRoute()
const router = useRouter()
const title = computed(() => String(route.meta.title ?? '管理页面'))
const description = computed(() => String(route.meta.description ?? ''))
</script>

<template>
  <div class="developing-view">
    <div class="page-heading-row">
      <div><div class="page-eyebrow">工作台 / 开发中</div><h1 class="page-title">{{ title }}</h1><div class="page-description">{{ description }}</div></div>
      <span class="demo-badge"><Construction :size="13" />开发中</span>
    </div>
    <section class="panel-card developing-card">
      <div>
        <div class="developing-icon"><Construction aria-hidden="true" /></div>
        <h2 class="panel-title" style="justify-content:center">{{ title }}正在建设中</h2>
        <p class="developing-note">当前阶段交付范围仅包含数据概览。此入口已保留供后续阶段使用，尚未接入业务数据或操作。</p>
        <el-button style="margin-top:20px" @click="router.push('/admin/dashboard')"><MoveLeft :size="15" />返回数据概览</el-button>
      </div>
    </section>
  </div>
</template>
