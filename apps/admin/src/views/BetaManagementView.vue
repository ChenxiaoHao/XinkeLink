<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Copy, RefreshCw, Search } from '@lucide/vue'
import { useAdminBetaStore } from '../stores/admin-beta'
import { errorMessage } from '../services/admin-http'
import type { BetaCode, BetaCodeStatus } from '../services/admin-beta'

const store = useAdminBetaStore()
const keyword = ref('')
const status = ref<BetaCodeStatus | 'all'>('all')
const confirming = ref(false)
const copyingId = ref<string | null>(null)
let active = true
const rows = computed(() => store.filterCodes(keyword.value, status.value))
const unavailable = computed(() => store.busy || confirming.value || !!store.error || !store.snapshot)
const formatter = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
function formatDate(value: string | null) { return value ? formatter.format(new Date(value)) : '—' }
onMounted(() => { void store.load() })
onUnmounted(() => { active = false; if (confirming.value) ElMessageBox.close() })

async function initialize() {
  if (unavailable.value) return
  try { await store.initialize(); if (active) ElMessage.success('内测码已初始化，内测已开启') }
  catch (error) { if (active) ElMessage.error(errorMessage(error)) }
}
async function changePhase() {
  if (unavailable.value || !store.snapshot?.total) return
  const { phase, version } = store.snapshot
  const target = phase === 'beta' ? 'open' : 'beta'
  confirming.value = true
  try {
    await ElMessageBox.confirm(target === 'open'
      ? '切换为正式开放后，所有账号将按正常访问规则使用社区；内测码与账号绑定记录会保留。'
      : '重新开启内测后，未激活账号将立即需要兑换内测码才能访问社区；已有激活账号继续有效。',
    target === 'open' ? '确认正式开放' : '确认重新开启内测', { confirmButtonText: '确认切换', cancelButtonText: '取消', distinguishCancelAndClose: true })
    if (!active) return
    await store.setPhase(target, version)
    if (active) ElMessage.success(target === 'open' ? '已切换为正式开放' : '已重新开启内测')
  } catch (error) { if (active && error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
  finally { confirming.value = false }
}
async function copyCode(row: BetaCode) {
  if (row.status !== 'unused' || copyingId.value || unavailable.value) return
  copyingId.value = row.id
  try { await navigator.clipboard.writeText(row.code); if (active) ElMessage.success('内测码已复制') }
  catch { if (active) ElMessage.error('复制失败，请选中内测码手动复制') }
  finally { copyingId.value = null }
}
</script>

<template>
  <div class="beta-management-view" data-testid="beta-view">
    <header class="page-heading-row">
      <div><div class="page-eyebrow">运营管理 <span aria-hidden="true">/</span> 内测管理</div><h1 class="page-title">内测管理</h1><p class="page-description">分发内测码，查看账号激活与剩余额度。</p></div>
      <el-button :disabled="store.busy || confirming" :loading="store.loading" aria-label="刷新内测数据" @click="store.load()"><RefreshCw :size="14" />刷新</el-button>
    </header>
    <section class="panel-card beta-stage" aria-label="访问阶段">
      <div><h2>当前阶段 <span class="beta-state">{{ store.phaseLabel }}</span></h2>
        <p v-if="store.error">阶段暂无法确认，请刷新后再操作。</p>
        <p v-else-if="!store.snapshot">正在读取服务器阶段与内测批次。</p>
        <p v-else-if="store.snapshot.phase === 'beta'">微信登录后需兑换内测码；一码绑定一个账号。</p>
        <p v-else>社区按正常访问规则开放，已激活账号与内测码记录保留。</p>
      </div>
      <el-button v-if="store.snapshot?.total" :disabled="unavailable" :loading="store.mutating" @click="changePhase">{{ store.snapshot.phase === 'beta' ? '切换为正式开放' : '重新开启内测' }}</el-button>
      <el-button v-else-if="store.snapshot && !store.error" type="primary" :disabled="unavailable" :loading="store.mutating" @click="initialize">初始化 30 个内测码</el-button>
    </section>
    <div v-if="store.error" class="beta-error" role="alert" data-testid="beta-error"><strong>内测数据加载失败</strong><span>{{ store.error }}</span><span v-if="store.snapshot">当前为上次成功加载的数据（{{ formatDate(store.updatedAt) }}），刷新成功前暂停分发与阶段切换。</span><span v-else>统计与阶段尚未确认，请刷新重试。</span></div>
    <!-- Three equal columns and two dividers fill the same panel width as the list. -->
    <dl class="panel-card beta-metrics" aria-label="内测码统计" :aria-busy="store.loading">
      <div class="beta-metric-lead"><dt>剩余可用</dt><dd data-testid="beta-remaining">{{ store.snapshot?.remaining ?? '—' }}</dd></div>
      <div><dt>已使用</dt><dd data-testid="beta-used">{{ store.snapshot?.used ?? '—' }}</dd></div>
      <div><dt>内测码总数</dt><dd data-testid="beta-total">{{ store.snapshot?.total ?? '—' }}</dd></div>
    </dl>
    <section class="panel-card beta-list" aria-label="内测码与绑定账号">
      <header class="beta-list-heading"><div><h2>内测码与绑定账号</h2><p>完整内测码仅供管理员分发。时间为北京时间。</p></div><span v-if="store.updatedAt" class="beta-updated">{{ formatDate(store.updatedAt) }} 更新</span></header>
      <div class="beta-toolbar">
        <el-input v-model="keyword" clearable aria-label="搜索内测码或账号" placeholder="搜索内测码、账号 ID 或昵称"><template #prefix><Search :size="15" /></template></el-input>
        <label class="beta-filter"><span>状态</span><select v-model="status" aria-label="内测码状态"><option value="all">全部状态</option><option value="unused">未使用</option><option value="used">已使用</option></select></label>
        <span class="beta-result-count">{{ store.snapshot ? `显示 ${rows.length} / ${store.snapshot.total} 个` : '等待加载' }}</span>
      </div>
      <div v-if="!store.snapshot" class="beta-empty">{{ store.loading ? '正在加载内测码…' : '内测码尚未加载，请刷新重试。' }}</div>
      <div v-else-if="!store.snapshot.total" class="beta-empty">尚未初始化内测码。初始化后将生成固定 30 个码并开启内测；重复初始化不会追加或重置。</div>
      <div v-else-if="rows.length === 0" class="beta-empty" data-testid="beta-no-results">没有匹配的内测码或账号，请调整搜索与筛选条件。</div>
      <div v-else class="beta-table-wrap">
        <table class="beta-table" data-testid="beta-table">
          <thead><tr><th scope="col">内测码</th><th scope="col">状态</th><th scope="col">绑定账号</th><th scope="col" class="beta-date">使用时间</th><th scope="col" class="beta-date">创建时间</th><th scope="col" class="beta-action">操作</th></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.id">
            <td class="beta-code"><code>{{ row.code }}</code></td><td><span class="beta-state" :class="{ 'is-used': row.status === 'used' }">{{ row.status === 'used' ? '已使用' : '未使用' }}</span></td>
            <td class="beta-user"><template v-if="row.user"><span>{{ row.user.nickname || '未设置昵称' }}</span><small>账号 ID：{{ row.user.id }}</small></template><span v-else class="beta-muted">尚未绑定</span></td>
            <td class="beta-date" data-label="使用">{{ formatDate(row.usedAt) }}</td><td class="beta-date" data-label="创建">{{ formatDate(row.createdAt) }}</td>
            <td class="beta-action"><el-button v-if="row.status === 'unused'" link :disabled="unavailable || !!copyingId" :aria-label="`复制内测码 ${row.id}`" @click="copyCode(row)"><Copy :size="14" />复制</el-button><span v-else class="beta-muted">—</span></td>
          </tr></tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.beta-management-view { display: grid; gap: 20px; min-width: 0; }
.beta-management-view .page-heading-row { margin: 0; }
.beta-management-view :deep(.el-button) { gap: 6px; }
.beta-stage { display: flex; justify-content: space-between; align-items: center; gap: 20px; padding: 20px 24px; }
.beta-stage h2, .beta-list-heading h2 { margin: 0; font-size: 16px; font-weight: 600; }
.beta-stage h2 { display: flex; align-items: center; gap: 12px; }
.beta-stage p, .beta-list-heading p { margin: 8px 0 0; color: var(--xlk-color-muted); font-size: 13px; line-height: 1.6; }
.beta-state { display: inline-flex; align-items: center; flex: none; white-space: nowrap; border-radius: 4px; background: #f2f2f2; padding: 3px 8px; color: #444; font-size: 12px; font-weight: 400; }
.beta-state.is-used { background: #fafafa; color: #666; }
.beta-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 0; padding: 20px 0; font-variant-numeric: tabular-nums; }
.beta-metrics > div { padding: 0 24px; border-left: 1px solid var(--xlk-color-border); }
.beta-metrics > div:first-child { border-left: 0; }
.beta-metrics dt { color: var(--xlk-color-muted); font-size: 13px; }
.beta-metrics dd { margin: 10px 0 0; font-size: 24px; line-height: 34px; font-weight: 500; }
.beta-metric-lead dd { font-size: 32px; }
.beta-error { display: grid; gap: 6px; padding: 16px 20px; border: 1px solid var(--xlk-color-border); background: #fff; border-radius: 8px; font-size: 13px; color: #666; }
.beta-error strong { color: #b42318; font-weight: 500; }
.beta-list { min-width: 0; overflow: hidden; }
.beta-list-heading { display: flex; gap: 16px; justify-content: space-between; align-items: flex-start; padding: 22px 24px 18px; }
.beta-updated { color: #666; font-size: 12px; white-space: nowrap; }
.beta-toolbar { display: flex; align-items: center; gap: 16px; padding: 0 24px 16px; }
.beta-toolbar :deep(.el-input) { width: min(320px, 100%); }
.beta-filter { display: flex; align-items: center; gap: 8px; color: #666; font-size: 13px; white-space: nowrap; }
.beta-filter select { height: 34px; padding: 0 28px 0 10px; background: #fff; color: #333; border: 1px solid #d5d5d5; border-radius: 6px; font: inherit; }
.beta-result-count { margin-left: auto; color: #666; font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.beta-table-wrap { overflow-x: auto; padding: 0 24px 8px; }
.beta-table { width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; }
.beta-table th { color: #666; font-size: 12px; font-weight: 400; padding: 12px 8px; white-space: nowrap; }
.beta-table td { padding: 14px 8px; border-top: 1px solid #efefef; vertical-align: middle; line-height: 20px; }
.beta-table th:first-child, .beta-table td:first-child { padding-left: 0; }
.beta-table th:last-child, .beta-table td:last-child { padding-right: 0; }
.beta-code { white-space: nowrap; user-select: all; }
.beta-code code { font-size: 13px; }
.beta-user { min-width: 130px; overflow-wrap: anywhere; }
.beta-user span, .beta-user small { display: block; }
.beta-user small { font-size: 12px; color: #666; }
.beta-table .beta-date { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; color: #666; }
.beta-action { width: 70px; text-align: right; white-space: nowrap; }
.beta-muted { color: #666; }
.beta-empty { padding: 32px 24px; color: #666; font-size: 14px; line-height: 1.8; border-top: 1px solid #efefef; }
@media (max-width: 1100px) { .beta-table { min-width: 850px; } .beta-list-heading { flex-wrap: wrap; } }
@media (max-width: 640px) {
  .beta-management-view { gap: 16px; }
  .beta-stage { align-items: flex-start; flex-direction: column; padding: 18px; }
  .beta-metrics > div { padding: 0 12px; }
  .beta-metrics dt { font-size: 12px; }
  .beta-metrics dd { font-size: 22px; }
  .beta-metric-lead dd { font-size: 28px; }
  .beta-list-heading { padding: 18px 18px 14px; gap: 8px; }
  .beta-toolbar { flex-wrap: wrap; gap: 12px; padding: 0 18px 16px; }
  .beta-toolbar :deep(.el-input) { width: 100%; }
  .beta-toolbar :deep(.el-input__wrapper), .beta-filter select { min-height: 44px; }
  .beta-table-wrap { padding: 0 18px; }
  .beta-table { min-width: 0; display: block; }
  .beta-table thead { display: none; }
  .beta-table tbody { display: block; }
  .beta-table tr { display: grid; grid-template-columns: 1fr auto; gap: 10px 8px; border-top: 1px solid #efefef; padding: 18px 0; }
  .beta-table td { display: block; border: 0; padding: 0; }
  .beta-code { grid-column: 1; }
  .beta-code code { font-size: 14px; }
  .beta-table tr td:nth-child(2) { grid-column: 2; grid-row: 1; text-align: right; }
  .beta-user { grid-column: 1; grid-row: 2; font-size: 14px; }
  .beta-table .beta-date { grid-column: 1 / -1; text-align: left; font-size: 12px; }
  .beta-date::before { content: attr(data-label); margin-right: 8px; }
  .beta-action { grid-column: 2; grid-row: 2; }
  .beta-action :deep(.el-button) { min-height: 44px; }
}
</style>
