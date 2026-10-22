<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElButton, ElTag } from 'element-plus'
import { readHealth } from './services/health'

const loading = ref(false)
const result = ref<'idle' | 'success' | 'error'>('idle')
const message = ref('')

async function checkConnection() {
  if (loading.value) return
  loading.value = true
  result.value = 'idle'
  try {
    await readHealth()
    result.value = 'success'
    message.value = '服务连接正常'
  } catch {
    result.value = 'error'
    message.value = '连接失败，请确认服务端已启动后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="bootstrap-page">
    <header class="bootstrap-heading">
      <h1>信科 Link 管理后台</h1>
      <el-tag effect="plain">开发环境</el-tag>
    </header>
    <section class="connection-card" aria-labelledby="connection-title">
      <h2 id="connection-title">服务连接</h2>
      <p>检查管理后台与服务端的连接状态。</p>
      <el-button type="primary" :loading="loading" @click="checkConnection">
        {{ loading ? '正在检查' : '检查服务连接' }}
      </el-button>
      <div class="connection-result" aria-live="polite">
        <el-alert v-if="result !== 'idle'" :title="message"
          :type="result === 'success' ? 'success' : 'error'" :closable="false" show-icon />
      </div>
    </section>
  </main>
</template>
