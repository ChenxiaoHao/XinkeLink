<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminAi, type AiEvent, type AiModel, type AiStage } from '../services/admin-ai'
import { adminHttp, errorMessage } from '../services/admin-http'

const labels: Record<AiStage, string> = { chat: '对话', embedding: '向量', rerank: '重排' }
const data = ref<Awaited<ReturnType<typeof adminAi.dashboard>>>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const editError = ref('')
const editing = ref<AiModel>()
const dialog = ref(false)
const draft = reactive({ model: '', baseUrl: '', apiKey: '' })
const clock = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23', month: '2-digit', day: '2-digit' })
const numbers = new Intl.NumberFormat('zh-CN')
let generation = 0

function resetEditor() {
  draft.apiKey = ''
  editing.value = undefined
  editError.value = ''
}
function closed() { if (!dialog.value) resetEditor() }
onMounted(() => { void refresh() })
onUnmounted(() => { generation++; resetEditor() })
watch(adminHttp.admin, (next, previous) => {
  if (next?.id === previous?.id) return
  generation++
  data.value = undefined
  dialog.value = false
  resetEditor()
  loading.value = saving.value = false
  if (next) void refresh()
}, { flush: 'sync' })

async function refresh() {
  if (loading.value || saving.value) return
  const request = generation
  loading.value = true
  error.value = ''
  try {
    const next = await adminAi.dashboard()
    if (request === generation) data.value = next
  } catch (failure) {
    if (request === generation) error.value = errorMessage(failure)
  } finally {
    if (request === generation) loading.value = false
  }
}

function edit(row: AiModel) {
  editing.value = row
  Object.assign(draft, { model: row.model, baseUrl: row.baseUrl, apiKey: '' })
  editError.value = ''
  dialog.value = true
}

async function save() {
  if (!data.value || !editing.value || saving.value || !draft.model.trim() || !draft.baseUrl.trim()) return
  const request = generation
  saving.value = true
  editError.value = ''
  const command = adminAi.edit(editing.value.stage, { ...draft, revision: data.value.settings.revision })
  draft.apiKey = ''
  try {
    const settings = await command
    if (request !== generation || !data.value) return
    data.value = { ...data.value, settings }
    dialog.value = false
    ElMessage.success('模型配置已保存')
  } catch (failure) {
    if (request === generation) editError.value = errorMessage(failure)
  } finally {
    if (request === generation) {
      saving.value = false
      if (!dialog.value) void refresh()
    }
  }
}

async function toggle() {
  if (!data.value || saving.value) return
  const request = generation
  const enabled = !data.value.settings.enabled
  saving.value = true
  error.value = ''
  try {
    const settings = await adminAi.toggle(enabled, data.value.settings.revision)
    if (request !== generation || !data.value) return
    data.value = { ...data.value, settings }
    ElMessage.success(enabled ? 'Link AI 已启动' : 'Link AI 已关闭')
  } catch (failure) {
    if (request === generation) error.value = errorMessage(failure)
  } finally {
    if (request === generation) {
      saving.value = false
      if (!error.value) void refresh()
    }
  }
}

function action(item: AiEvent) {
  if (item.action === 'enable') return '启动服务'
  if (item.action === 'disable') return '关闭服务'
  const stage = item.stage ? labels[item.stage] : '模型'
  if (item.action === 'model') return `修改${stage}配置`
  return `${stage}调用${item.success ? '成功' : '失败'}`
}
</script>

<template>
  <div class="ai-service" data-testid="ai-service-view">
    <header class="page-heading-row">
      <h1 class="page-title">Link AI 服务</h1>
      <el-button :loading="loading" :disabled="saving" @click="refresh">刷新</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-skeleton v-if="loading && !data" :rows="5" animated />
    <template v-if="data">
      <section class="ai-panel ai-overview" aria-label="服务状态和今日用量">
        <div>
          <span class="ai-muted">服务状态</span>
          <p class="ai-status" :class="{ 'is-open': data.settings.enabled }">
            {{ data.settings.enabled ? '已开启' : '已关闭' }}
          </p>
          <el-button :type="data.settings.enabled ? 'default' : 'primary'" :loading="saving && !dialog"
            :disabled="loading || saving" @click="toggle">{{ data.settings.enabled ? '关闭服务' : '启动服务' }}</el-button>
        </div>
        <div>
          <span class="ai-muted">今日 Token 总数</span>
          <p class="ai-token">{{ numbers.format(data.today.tokens) }}</p>
          <span class="ai-muted">{{ data.today.date }} · 北京时间 · {{ numbers.format(data.today.calls) }} 次模型调用</span>
          <p v-if="data.today.unreportedCalls" class="ai-muted ai-count-warning">{{ data.today.unreportedCalls }} 次调用未返回 Token 用量</p>
        </div>
      </section>
      <section class="ai-panel">
        <h2>模型配置</h2>
        <div v-for="row in data.settings.models" :key="row.stage" class="ai-model">
          <strong>{{ labels[row.stage] }}</strong>
          <div class="ai-model-address"><div>{{ row.model || '未配置' }}</div><small class="ai-muted">{{ row.baseUrl || '未配置 Base URL' }}</small></div>
          <span class="ai-muted">API Key {{ row.keyConfigured ? '已配置' : '未配置' }}</span>
          <el-button size="small" :aria-label="`修改${labels[row.stage]}模型`" :disabled="loading || saving" @click="edit(row)">修改</el-button>
        </div>
      </section>
      <section class="ai-panel">
        <h2>最近记录</h2>
        <p v-if="!data.records.length" class="ai-muted ai-empty">暂无记录</p>
        <div v-else class="ai-table-wrap">
          <table class="ai-table">
            <thead><tr><th scope="col">时间</th><th scope="col">事件</th><th scope="col">模型</th><th scope="col">Token</th><th scope="col">耗时</th></tr></thead>
            <tbody>
              <tr v-for="(item, index) in data.records" :key="`${item.time}-${index}`">
                <td>{{ clock.format(new Date(item.time)) }}</td>
                <td :class="{ 'ai-failed': !item.success }">{{ action(item) }}</td>
                <td>{{ item.model ?? '—' }}</td>
                <td>{{ item.tokens === null ? '—' : numbers.format(item.tokens) }}</td>
                <td>{{ item.elapsedMs === null ? '—' : `${numbers.format(item.elapsedMs)} ms` }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
    <el-dialog v-model="dialog" :title="editing ? `修改${labels[editing.stage]}模型` : '修改模型'" width="min(480px, 94vw)"
      :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close @closed="closed">
      <el-alert v-if="editError" :title="editError" type="error" show-icon :closable="false" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="模型名称"><el-input v-model="draft.model" :maxlength="120" :disabled="saving" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="draft.baseUrl" :maxlength="500" :disabled="saving" spellcheck="false" /></el-form-item>
        <el-form-item label="更换 API Key"><el-input v-model="draft.apiKey" type="password" autocomplete="new-password"
          :maxlength="1024" :disabled="saving" :placeholder="editing?.keyConfigured ? '留空继续使用当前 Key' : '输入 API Key'" /></el-form-item>
        <p v-if="editing?.stage === 'embedding'" class="ai-muted">更换向量模型或地址时，已发布资料会重新建立索引。</p>
        <p v-if="editing?.stage === 'rerank'" class="ai-muted">Base URL 填写供应商的完整重排接口地址。</p>
        <p v-if="saving && editing?.stage === 'embedding'" role="status" class="ai-muted">正在保存向量配置，请稍候。</p>
        <div class="ai-dialog-actions">
          <el-button :disabled="saving" @click="dialog = false">取消</el-button>
          <el-button type="primary" native-type="submit" :loading="saving" :disabled="!draft.model.trim() || !draft.baseUrl.trim()">保存</el-button>
        </div>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.ai-service { display: grid; gap: 16px; }
.ai-panel { min-width: 0; border: 1px solid var(--xlk-color-border); background: var(--xlk-color-surface); border-radius: var(--xlk-radius-sm); }
.ai-overview { display: grid; grid-template-columns: minmax(170px, 1fr) minmax(0, 2fr); }
.ai-overview > div { padding: 16px 20px; }
.ai-overview > div + div { border-left: 1px solid var(--xlk-color-divider); }
.ai-muted { color: var(--xlk-color-muted); font-size: 13px; }
.ai-status { margin: 7px 0 12px; font-size: 20px; font-weight: 650; }
.ai-status.is-open { color: var(--xlk-color-success); }
.ai-token { margin: 4px 0; font-size: 32px; line-height: 1.15; font-weight: 650; font-variant-numeric: tabular-nums; }
.ai-count-warning,.ai-empty { margin: 4px 0 0; }
.ai-panel h2 { margin: 0; padding: 14px 20px; font-size: 16px; line-height: 1.25; }
.ai-model { display: grid; grid-template-columns: 56px minmax(0, 1fr) 120px auto; align-items: center; gap: 16px; padding: 11px 20px; border-top: 1px solid var(--xlk-color-divider); }
.ai-model-address { min-width: 0; overflow-wrap: anywhere; line-height: 1.45; }
.ai-model-address small { display: block; margin-top: 1px; }
.ai-empty { padding: 6px 20px 18px; }
.ai-table-wrap { overflow-x: auto; }
.ai-table { width: 100%; border-collapse: collapse; white-space: nowrap; font-size: 13px; }
.ai-table th,.ai-table td { padding: 9px 20px; text-align: left; border-top: 1px solid var(--xlk-color-divider); }
.ai-table th { font-weight: 550; color: var(--xlk-color-muted); }
.ai-table th:nth-last-child(-n+2),.ai-table td:nth-last-child(-n+2) { text-align: right; font-variant-numeric: tabular-nums; }
.ai-failed { color: var(--xlk-color-danger); }
.ai-dialog-actions { display: flex; justify-content: flex-end; margin-top: 18px; }
@media (max-width: 560px) {
  .ai-overview { grid-template-columns: 1fr; }
  .ai-overview > div + div { border-left: 0; border-top: 1px solid var(--xlk-color-divider); }
  .ai-model { grid-template-columns: 1fr auto; gap: 8px 12px; padding-inline: 14px; }
  .ai-model strong { grid-column: 1 / -1; }
  .ai-model-address { grid-column: 1 / -1; }
  .ai-model .el-button { justify-self: end; }
}
</style>
