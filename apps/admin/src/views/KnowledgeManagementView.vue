<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, RefreshCw } from '@lucide/vue'
import { adminHttp, errorMessage } from '../services/admin-http'
import { adminKnowledge, KNOWLEDGE_UPLOAD_MAX_MIB, type KnowledgeDocument, type KnowledgeDraft, type KnowledgeExtraction } from '../services/admin-knowledge'
import { useKnowledgeStore } from '../stores/admin-knowledge'

const store = useKnowledgeStore()
const keyword = ref(''), status = ref('all'), visible = ref(false), editId = ref<string>(), extracting = ref(false), confirming = ref(false), actionError = ref(''), editorError = ref(''), warnings = ref<string[]>([]), url = ref('')
const emptyDraft = (): KnowledgeDraft => ({ title: '', source: '', version: '', publishedDate: '', validFrom: '', validUntil: '', scope: 'all', validity: '', body: '', reviewed: false })
const draft = reactive<KnowledgeDraft>(emptyDraft())
const labels = { draft: '草稿', published: '已发布', disabled: '已停用' }
const rows = computed(() => store.items.filter(row => (status.value === 'all' || row.status === status.value) && `${row.title} ${row.source} ${row.version}`.includes(keyword.value.trim())))
const busy = computed(() => store.mutating || extracting.value || confirming.value)
const cannotWrite = computed(() => busy.value || store.loading || !store.loaded || store.writeForbidden)
const indexStatus = computed(() => {
  if (store.loading) return { label: '正在检查检索状态', type: 'info' } as const
  if (store.ready === true) {
    const hasPublishedVersion = store.items.some(row => row.status !== 'disabled' && row.activeVersionId)
    return { label: hasPublishedVersion ? '检索索引已就绪' : '检索索引已就绪 · 当前列表暂无发布版本', type: 'success' } as const
  }
  if (store.ready === false) return { label: '检索索引未就绪', type: 'warning' } as const
  return { label: '检索状态未知', type: 'info' } as const
})
let active = true, generation = 0
onMounted(() => { void store.load() })
onUnmounted(() => { active = false; generation++; if (confirming.value) ElMessageBox.close() })
watch(adminHttp.admin, (next, previous) => { if (next?.id !== previous?.id) { generation++; visible.value = false; Object.assign(draft, emptyDraft()); warnings.value = []; editorError.value = ''; extracting.value = false; ElMessageBox.close() } }, { flush: 'sync' })

function edit(row?: KnowledgeDocument) {
  generation++; editId.value = row?.id; Object.assign(draft, emptyDraft(), row ? { title: row.title, source: row.source, version: row.version, publishedDate: row.publishedDate, validFrom: row.validFrom ?? '', validUntil: row.validUntil ?? '', scope: row.scope, validity: row.validity, body: row.body, reviewed: row.reviewed, filename: row.filename } : {})
  // An editor never carries a previous document's raw file.
  delete draft.originalBase64
  if (!row) delete draft.filename
  warnings.value = row?.warnings ?? []; editorError.value = ''; url.value = ''; visible.value = true
}
function closeEditor() { generation++; visible.value = false; extracting.value = false }
function changed() { draft.reviewed = false }
async function extract(action: () => Promise<KnowledgeExtraction>) {
  const request = ++generation; extracting.value = true; editorError.value = ''
  try {
    const result = await action()
    if (!active || request !== generation || !visible.value) return
    Object.assign(draft, { body: result.body, filename: result.filename, originalBase64: result.originalBase64, reviewed: false })
    if (result.title) draft.title = result.title
    if (result.source) draft.source = result.source
    warnings.value = result.warnings
  } catch (error) { if (active && request === generation) editorError.value = errorMessage(error) }
  finally { if (active && request === generation) extracting.value = false }
}
function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (file) void extract(() => adminKnowledge.extract(file))
}
function importArticle() { const address = url.value; void extract(() => adminKnowledge.importUrl(address)) }
async function save() {
  if (cannotWrite.value) return
  const request = generation; editorError.value = ''
  try { await store.save({ ...draft }, editId.value); if (active && request === generation) { closeEditor(); ElMessage.success('草稿已保存，请在列表中单独确认发布') } }
  catch (error) { if (active && request === generation) editorError.value = errorMessage(error) }
}
async function changeState(row: KnowledgeDocument, action: 'publish' | 'disable') {
  if (cannotWrite.value) return
  confirming.value = true; actionError.value = ''
  const request = generation
  try {
    await ElMessageBox.confirm(action === 'publish' ? `确认已校对「${row.title}」的正文、版本、适用范围和有效性？发布成功后才可用于 Link AI 回答。` : `停用「${row.title}」后，该资料将不再用于 Link AI 回答。`, action === 'publish' ? '确认发布知识资料' : '确认停用知识资料', { type: 'warning', confirmButtonText: action === 'publish' ? '确认发布' : '确认停用', cancelButtonText: '返回', distinguishCancelAndClose: true })
    if (!active || request !== generation) return
    if (action === 'publish') await store.publish(row); else await store.disable(row)
    if (active && request === generation) ElMessage.success(action === 'publish' ? '资料已发布' : '资料已停用')
  } catch (error) { if (active && request === generation && error !== 'cancel' && error !== 'close') actionError.value = errorMessage(error) }
  finally { confirming.value = false }
}
</script>

<template>
  <div class="knowledge-view" data-testid="knowledge-view">
    <header class="page-heading-row">
      <div><h1 class="page-title">Link AI 知识库</h1><p class="page-description">学校规章制度资料先保存草稿，经人工校对后发布；更新发布失败时保留原有效版本。</p></div>
      <div class="knowledge-actions"><RouterLink to="/admin/logs">查看审计日志</RouterLink><el-button :disabled="busy" :loading="store.loading" aria-label="刷新知识资料" @click="store.load()"><RefreshCw :size="14" />刷新</el-button><el-button type="primary" :disabled="cannotWrite" @click="edit()"><Plus :size="14" />新建资料</el-button></div>
    </header>
    <el-alert v-if="store.error" :title="store.error" description="读取失败，请刷新重试；当前资料仅供参考，暂不可写入。" type="error" :closable="false" show-icon />
    <el-alert v-if="store.writeForbidden" title="当前账号没有写入权限，请重新登录或联系管理员。" type="warning" :closable="false" show-icon />
    <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
    <section class="panel-card knowledge-panel" aria-label="知识资料列表">
      <div class="knowledge-filters"><el-input v-model="keyword" placeholder="搜索标题、来源、版本" aria-label="搜索知识资料" clearable /><el-select v-model="status" aria-label="知识资料状态"><el-option label="全部状态" value="all" /><el-option label="草稿" value="draft" /><el-option label="已发布" value="published" /><el-option label="已停用" value="disabled" /></el-select><el-tag :type="indexStatus.type" role="status">{{ indexStatus.label }}</el-tag></div>
      <el-table v-loading="store.loading" :data="rows" row-key="id" empty-text="暂无知识资料，请新建资料并校对正文。">
        <el-table-column prop="title" label="标题" min-width="190" />
        <el-table-column prop="version" label="版本" min-width="110" />
        <el-table-column prop="publishedDate" label="发布日期" width="120" />
        <el-table-column label="适用范围" min-width="110"><template #default="{ row }">{{ row.scope === 'all' ? '全校' : row.scope }}</template></el-table-column>
        <el-table-column label="状态" min-width="160"><template #default="{ row }"><el-tag :type="row.status === 'published' ? 'success' : row.status === 'disabled' ? 'info' : 'warning'">{{ labels[row.status as keyof typeof labels] }}</el-tag><small v-if="row.status === 'draft' && row.activeVersionId" class="knowledge-note">原发布版本仍生效</small></template></el-table-column>
        <el-table-column label="操作" width="230"><template #default="{ row }"><el-button text type="primary" :disabled="busy" @click="edit(row)">校对 / 更新</el-button><el-button v-if="row.status !== 'published'" text type="primary" :disabled="cannotWrite || !row.reviewed" @click="changeState(row, 'publish')">发布</el-button><el-button v-if="row.activeVersionId && row.status !== 'disabled'" text type="danger" :disabled="cannotWrite" @click="changeState(row, 'disable')">停用</el-button></template></el-table-column>
      </el-table>
    </section>
    <el-drawer v-model="visible" :title="editId ? '校对 / 更新知识资料' : '新建知识资料'" size="min(760px, 100vw)" :close-on-click-modal="false" :before-close="closeEditor">
      <div class="knowledge-editor">
        <el-alert title="导入仅提取正文，不会自动保存或发布。请核对条款、例外条件、页码及图片 / 附件。" type="info" :closable="false" />
        <div class="knowledge-import"><label class="operations-field"><span>上传文字版文件（不超过 {{ KNOWLEDGE_UPLOAD_MAX_MIB }} MiB）</span><input type="file" accept=".pdf,.docx,.txt,.md" aria-label="上传知识文件" :disabled="busy || store.writeForbidden" @change="upload" /></label><p class="knowledge-note">支持 PDF、DOCX、TXT、Markdown；扫描 PDF 请提供文字版或手工填写正文。</p><label class="operations-field"><span>指定学校官网文章</span><el-input v-model="url" aria-label="学校官网文章地址" placeholder="https://hbcet.hpu.edu.cn/..." :disabled="busy" /></label><el-button :loading="extracting" :disabled="busy || !url.trim() || store.writeForbidden" @click="importArticle">提取官网正文</el-button></div>
        <el-alert v-if="editorError" :title="editorError" type="error" :closable="false" show-icon />
        <el-alert v-for="(warning, index) in warnings" :key="index" :title="warning" type="warning" :closable="false" />
        <el-form label-position="top" :disabled="busy || store.writeForbidden" @submit.prevent="save">
          <el-form-item label="标题 *"><el-input v-model="draft.title" aria-label="资料标题" @input="changed" /></el-form-item>
          <el-form-item label="来源 / 官网链接 *"><el-input v-model="draft.source" aria-label="资料来源" @input="changed" /><small class="knowledge-note">虚构测试资料请在标题前加【测试样例】，来源填写 urn:xinkelink:demo-knowledge。</small></el-form-item>
          <div class="knowledge-grid"><el-form-item label="版本 *"><el-input v-model="draft.version" aria-label="资料版本" placeholder="如：2026 年修订版" @input="changed" /></el-form-item><el-form-item label="发布日期"><el-input v-model="draft.publishedDate" type="date" aria-label="资料发布日期" @input="changed" /></el-form-item></div>
          <div class="knowledge-grid"><el-form-item label="生效日期（可选）"><el-input v-model="draft.validFrom" type="date" aria-label="资料生效日期" @input="changed" /></el-form-item><el-form-item label="失效日期（可选）"><el-input v-model="draft.validUntil" type="date" aria-label="资料失效日期" @input="changed" /></el-form-item></div>
          <el-form-item label="适用范围 *"><el-select v-model="draft.scope" filterable allow-create aria-label="资料适用范围" placeholder="选择全校或填写具体适用人群" @change="changed"><el-option label="全校" value="all" /></el-select></el-form-item>
          <el-form-item label="有效性说明 *"><el-input v-model="draft.validity" aria-label="资料有效性说明" placeholder="适用学年、有效期限、替代关系或待核实事项" type="textarea" :rows="2" @input="changed" /></el-form-item>
          <el-form-item label="提取正文 / 人工校对 *"><el-input v-model="draft.body" aria-label="资料校对正文" type="textarea" :rows="15" resize="vertical" @input="changed" /></el-form-item>
          <p v-if="draft.filename" class="knowledge-note">原始文件：{{ draft.filename }}</p>
          <el-checkbox v-model="draft.reviewed" aria-label="确认已人工校对正文和资料信息">已人工校对正文、来源、版本、适用范围及有效性</el-checkbox>
          <p class="knowledge-note">修改任一资料字段后需重新确认校对。保存后返回列表，再单独确认发布。</p>
          <div class="knowledge-actions"><el-button :disabled="store.mutating" @click="closeEditor">关闭</el-button><el-button native-type="submit" type="primary" :loading="store.mutating" :disabled="cannotWrite">保存草稿</el-button></div>
        </el-form>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.knowledge-view, .knowledge-editor { display: grid; gap: 18px; }
.knowledge-actions, .knowledge-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.knowledge-actions a { color: inherit; font-size: 13px; }
.knowledge-panel { padding: 20px; }
.knowledge-filters { margin-bottom: 16px; }
.knowledge-filters .el-input { width: min(360px, 100%); }
.knowledge-filters .el-select { width: 150px; }
.knowledge-note { display: block; margin: 8px 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.knowledge-import { display: grid; gap: 12px; }
.knowledge-import > .el-button { justify-self: start; }
.knowledge-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.knowledge-editor :deep(.el-checkbox) { white-space: normal; height: auto; min-height: 32px; }
.knowledge-editor :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
@media (max-width: 800px) { .page-heading-row { flex-direction: column; } }
@media (max-width: 600px) { .knowledge-grid { grid-template-columns: 1fr; gap: 0; } .knowledge-panel { padding: 12px; } }
</style>
