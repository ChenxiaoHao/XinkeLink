<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminHttp, errorMessage } from '../services/admin-http'
interface Feedback { id: string; nickname: string | null; userId: string; content: string; createdAt: string }
const items=ref<Feedback[]>([]), total=ref(0), page=ref(1), loading=ref(false), error=ref('')
async function load() {
  loading.value=true; error.value=''
  try { const result=await adminHttp.request<{items: Feedback[];total: number}>(`/feedback?page=${page.value}&pageSize=10`); items.value=result.items; total.value=result.total }
  catch (e) { error.value=errorMessage(e) } finally { loading.value=false }
}
onMounted(load)
</script>
<template>
  <div class="content-management-view"><header class="page-heading-row content-heading"><div><div class="page-eyebrow">运营管理 / 意见反馈</div><h1 class="page-title">意见反馈</h1><p class="page-description">查看用户提交的意见和建议</p></div><el-button @click="load">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <section class="panel-card moderation-table-panel"><el-table v-loading="loading" :data="items" empty-text="暂无用户反馈"><el-table-column label="用户" width="180"><template #default="{ row }">{{ row.nickname || '用户' }}<br><small>编号 {{ row.userId }}</small></template></el-table-column><el-table-column prop="content" label="意见与建议" min-width="320" /><el-table-column label="提交时间" width="200"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString('zh-CN') }}</template></el-table-column></el-table><div class="table-footer"><span>共 {{ total }} 条</span><el-pagination v-model:current-page="page" :page-size="10" :total="total" layout="prev, pager, next" @current-change="load" /></div></section>
  </div>
</template>
