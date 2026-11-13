<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { errorMessage } from '../services/admin-http'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Building2, CheckCircle2, FileText, Info, Plus, RotateCcw, Settings2, ShieldCheck, UserRound } from '@lucide/vue'
import { useAdminSettingsStore, type ImageSensitivity } from '../stores/admin-settings'
import { reviewSettingsChanged } from '../utils/admin-display'

const store = useAdminSettingsStore()
const form = reactive({
  ...store.settings.contentReview,
  sensitiveWords: [...store.settings.contentReview.sensitiveWords],
})
const loadError = ref('')
onMounted(async () => { try { await store.loadSettings(); Object.assign(form, store.settings.contentReview, { sensitiveWords: [...store.settings.contentReview.sensitiveWords] }) } catch (error) { loadError.value = errorMessage(error); ElMessage.error(loadError.value) } })
const keywordInput = ref('')
const validationErrors = ref<string[]>([])
const hasChanges = computed(() => store.loaded && reviewSettingsChanged(form, store.settings.contentReview))
const sensitivityOptions: Array<{ value: ImageSensitivity; label: string; note: string }> = [
  { value: 'relaxed', label: '宽松（中低风险）', note: '适用于一般图片初步筛查' },
  { value: 'standard', label: '适中（标准模式-推荐）', note: '标准强度' },
  { value: 'strict', label: '高严（严格拦截）', note: '配置不执行自动拦截' },
]
const sectionItems = [
  { id: 'platform-info', label: '平台基本信息', icon: Building2 },
  { id: 'content-review', label: '内容审核与规则', icon: ShieldCheck },
  { id: 'community-rules', label: '社区治理公约', icon: FileText },
  { id: 'admin-info', label: '管理员账号与权限', icon: UserRound },
  { id: 'system-params', label: '系统基础参数', icon: Settings2 },
]

function jumpToSection(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function addKeyword() {
  const value = keywordInput.value.trim()
  if (!value) { ElMessage.warning('请输入关键词'); return }
  if (form.sensitiveWords.includes(value)) { ElMessage.warning('该关键词已存在'); return }
  if (form.sensitiveWords.length >= 30) { ElMessage.warning('最多保存 30 个敏感关键词'); return }
  form.sensitiveWords.push(value)
  keywordInput.value = ''
  validationErrors.value = []
}
function removeKeyword(index: number) {
  form.sensitiveWords.splice(index, 1)
  validationErrors.value = []
}
async function saveSettings() {
  try {
  const result = await store.saveSettings({ ...store.settings, contentReview: { ...form, sensitiveWords: [...form.sensitiveWords] } })
  validationErrors.value = result.errors.map(error => `${error.field}：${error.code === 'required' ? '必填项' : '格式不符合允许范围'}`)
  if (!result.ok) { ElMessage.warning('请检查标出的配置项'); return }
  if (!result.changedFields.length) { ElMessage.info('配置没有变化'); return }
  ElMessage.success('设置已保存至服务器')
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function resetSettings() {
  try {
    await ElMessageBox.confirm('将恢复服务器提供的默认设置。该操作不会更改帖子、举报、用户或其他业务数据。', '确认恢复默认值', {
      type: 'warning', confirmButtonText: '确认恢复默认值', cancelButtonText: '取消', distinguishCancelAndClose: true,
    })
    const result = await store.resetToDefaults()
    Object.assign(form, store.settings.contentReview, { sensitiveWords: [...store.settings.contentReview.sensitiveWords] })
    validationErrors.value = []
    ElMessage.success(result.changedFields.length ? '已恢复默认值' : '当前配置已是默认值')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error))
  }
}
</script>

<template>
  <div class="settings-view" data-testid="settings-view">
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <header class="page-heading-row settings-heading">
      <div>
        <div class="page-eyebrow">系统 <span aria-hidden="true">/</span> 系统设置</div>
        <h1 class="page-title">系统设置</h1>
        <p class="page-description">平台信息与内容审核参数。</p>
      </div>
      <span class="demo-badge"><span class="environment-dot" />服务器配置</span>
    </header>

    <el-alert class="settings-simulation-notice" data-testid="settings-simulation-notice" type="info" :closable="false" show-icon>
      内容审核规则保存在服务器，当前仅作为配置，不执行自动内容拦截。
    </el-alert>

    <div class="settings-layout">
      <aside class="settings-section-nav" aria-label="设置分段">
        <h2>配置分段</h2>
        <button v-for="section in sectionItems" :key="section.id" class="settings-section-link" type="button" @click="jumpToSection(section.id)">
          <component :is="section.icon" :size="15" aria-hidden="true" /><span>{{ section.label }}</span>
        </button>
        <p class="settings-nav-note"><Info :size="13" />平台信息与环境参数仅供识别，不代表真实服务器运行状态。</p>
      </aside>

      <div class="settings-content">
        <section id="platform-info" class="panel-card settings-card" data-testid="platform-config">
          <header class="settings-card-heading"><div><span class="settings-card-icon"><Building2 :size="16" /></span><div><h2>平台基本信息</h2><p>展示品牌和服务范围，不代表学校官方平台。</p></div></div><span class="readonly-pill">只读</span></header>
          <div class="settings-info-grid">
            <div class="settings-info-field"><span>平台名称</span><strong>{{ store.settings.platform.brandName }}</strong></div>
            <div class="settings-info-field"><span>服务范围</span><strong>{{ store.settings.platform.serviceArea }}</strong></div>
            <div class="settings-info-callout"><Info :size="15" /><span>{{ store.settings.platform.platformNotice }}</span></div>
          </div>
        </section>

        <section id="content-review" class="panel-card settings-card" data-testid="content-review-config">
          <header class="settings-card-heading"><div><span class="settings-card-icon"><ShieldCheck :size="16" /></span><div><h2>内容审核与规则</h2><p>配置持久化保存，当前不执行自动拦截。</p></div></div><span class="demo-field-pill">服务器配置</span></header>
          <div class="settings-control-row">
            <div><strong>敏感词实时拦截</strong><small>当前仅保存开关，不改变用户发布流程。</small></div>
            <el-switch v-model="form.sensitiveWordIntercept" aria-label="敏感词实时拦截" />
          </div>
          <div class="settings-control-row">
            <div><strong>夜间自动队列模式（23:00 - 06:00）</strong><small>仅保存服务器配置，不执行内容排队。</small></div>
            <el-switch v-model="form.nightQueueMode" aria-label="夜间队列模式" />
          </div>
          <div class="settings-subsection">
            <div class="settings-subsection-heading"><div><strong>图片识别审核强度</strong><small>当前仅保存强度设置，不调用 OCR 或 AI 服务。</small></div></div>
            <div class="sensitivity-options">
              <label v-for="item in sensitivityOptions" :key="item.value" class="sensitivity-option" :class="{ 'is-selected': form.imageSensitivity === item.value }">
                <el-radio v-model="form.imageSensitivity" :value="item.value">{{ item.label }}</el-radio>
                <small>{{ item.note }}</small>
              </label>
            </div>
          </div>
          <div class="settings-subsection keyword-section">
            <div class="settings-subsection-heading"><div><strong>敏感关键词</strong><small>保存在服务器配置中，当前不参与自动检测。</small></div><span class="keyword-count">{{ form.sensitiveWords.length }} / 30</span></div>
            <div class="keyword-entry"><el-input v-model="keywordInput" aria-label="新增审核关键词" placeholder="输入 2–20 个字符" @keyup.enter="addKeyword" /><el-button data-testid="settings-add-keyword" @click="addKeyword"><Plus :size="14" />添加</el-button></div>
            <div class="keyword-tags" data-testid="settings-keywords"><el-tag v-for="(word, index) in form.sensitiveWords" :key="`${index}-${word}`" closable effect="plain" @close="removeKeyword(index)">{{ word }}</el-tag><span v-if="form.sensitiveWords.length === 0" class="keyword-empty">尚未添加敏感关键词</span></div>
          </div>
          <div v-if="validationErrors.length" class="settings-validation-error" data-testid="settings-validation-errors"><strong>配置校验未通过</strong><span v-for="message in validationErrors" :key="message">{{ message }}</span></div>
        </section>

        <section id="community-rules" class="panel-card settings-card community-readonly" data-testid="community-config-readonly">
          <header class="settings-card-heading"><div><span class="settings-card-icon"><FileText :size="16" /></span><div><h2>社区治理公约</h2><p>校园交流与内容管理的基本约定。</p></div></div><span class="readonly-pill">只读</span></header>
          <div class="community-reference-note"><Info :size="16" /><p>{{ store.settings.community.note }}</p></div>
          <div class="community-scope">信科 Link 是校园交流与互助社区。学校和专业等资料由用户自行填写，平台不提供学校官方认证。</div>
        </section>

        <div class="settings-secondary-grid">
          <section id="admin-info" class="panel-card settings-card" data-testid="administrator-config">
            <header class="settings-card-heading"><div><span class="settings-card-icon"><UserRound :size="16" /></span><div><h2>管理员账号与权限</h2><p>当前登录管理员的账号与角色。</p></div></div><span class="readonly-pill">只读</span></header>
            <div class="settings-info-field"><span>当前账号</span><strong>{{ store.settings.administrator.account }}</strong></div>
            <div class="settings-info-field"><span>角色</span><strong>{{ store.settings.administrator.role }}</strong></div>
          </section>
          <section id="system-params" class="panel-card settings-card" data-testid="system-parameters">
            <header class="settings-card-heading"><div><span class="settings-card-icon"><Settings2 :size="16" /></span><div><h2>系统基础参数</h2><p>仅供界面展示，不表示在线运行状态。</p></div></div><span class="readonly-pill">只读</span></header>
            <div class="settings-info-field"><span>系统架构</span><strong>{{ store.settings.system.environment }}</strong></div>
            <div class="settings-info-field"><span>配置版本</span><strong>{{ store.settings.system.version }}</strong></div>
            <p class="settings-persistence-note">{{ store.settings.system.persistenceNote }}</p>
          </section>
        </div>
      </div>
    </div>

    <footer class="settings-save-bar">
      <span class="settings-save-state"><CheckCircle2 :size="15" />{{ hasChanges ? '有未保存的配置' : '当前配置已同步至服务器' }}</span>
      <div><el-button data-testid="settings-reset" @click="resetSettings"><RotateCcw :size="14" />恢复默认值</el-button><el-button data-testid="settings-save" type="primary" :disabled="!store.loaded || !hasChanges" @click="saveSettings"><CheckCircle2 :size="14" />保存当前配置</el-button></div>
    </footer>
  </div>
</template>

<style scoped>
.settings-view { width: 100%; max-width: 1560px; margin: 0 auto; padding-bottom: 74px; color: var(--xlk-color-text); }
.settings-heading { margin-bottom: 13px; }
.settings-simulation-notice { margin-bottom: 15px; border-color: #eeeeee; background: #f5f5f5; color: #444444; }
.settings-layout { display: grid; grid-template-columns: 195px minmax(0, 1fr); align-items: start; gap: 15px; }
.settings-section-nav { position: sticky; top: 0; display: flex; flex-direction: column; gap: 4px; padding: 14px 10px; background: #ffffff; border: 1px solid #f5f5f5; border-radius: 10px; box-shadow: var(--xlk-shadow-card); }
.settings-section-nav h2 { margin: 1px 8px 7px; color: #444444; font-size: 13px; font-weight: 600; }
.settings-section-link { display: flex; align-items: center; gap: 8px; min-height: 34px; padding: 0 8px; color: #444444; background: transparent; border: 0; border-radius: 6px; text-align: left; font: inherit; font-size: 13px; cursor: pointer; }
.settings-section-link:hover { color: #171717; background: #f5f5f5; }
.settings-section-link svg { flex: none; color: #666666; }
.settings-nav-note { display: flex; align-items: flex-start; gap: 6px; padding: 10px 7px 0; margin: 6px 0 0; color: #666666; border-top: 1px solid #f5f5f5; font-size: 13px; line-height: 1.55; }
.settings-nav-note svg { flex: none; color: #171717; }
.settings-content { min-width: 0; display: flex; flex-direction: column; gap: 13px; }
.settings-card { padding: 15px 17px; scroll-margin-top: 14px; }
.settings-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 12px; margin-bottom: 11px; border-bottom: 1px solid #f5f5f5; }
.settings-card-heading > div { display: flex; align-items: center; gap: 9px; min-width: 0; }
.settings-card-icon { width: 31px; height: 31px; display: grid; place-items: center; flex: none; color: #666666; background: #f5f5f5; border-radius: 8px; }
.settings-card-heading h2 { margin: 0; color: #303030; font-size: 13px; font-weight: 700; }
.settings-card-heading p { margin: 3px 0 0; color: #666666; font-size: 13px; }
.readonly-pill, .demo-field-pill { flex: none; padding: 4px 7px; border-radius: 11px; color: #666666; background: #f5f5f5; font-size: 13px; }
.demo-field-pill { color: #444444; background: #f5f5f5; }
.settings-info-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.settings-info-field { min-width: 0; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 11px; background: #fafafa; border-radius: 6px; }
.settings-info-field > span { color: #666666; font-size: 13px; }
.settings-info-field strong { overflow: hidden; color: #303030; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.settings-info-callout { grid-column: 1 / -1; display: flex; align-items: center; gap: 8px; padding: 9px 10px; color: #666666; background: #f5f5f5; border-radius: 6px; font-size: 13px; }
.settings-info-callout svg { flex: none; }
.settings-control-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 53px; padding: 8px 4px; border-bottom: 1px solid #f5f5f5; }
.settings-control-row > div { display: flex; flex-direction: column; gap: 4px; }
.settings-control-row strong, .settings-subsection-heading strong { color: #303030; font-size: 13px; font-weight: 650; }
.settings-control-row small, .settings-subsection-heading small { color: #666666; font-size: 13px; }
.settings-subsection { padding: 12px 11px; margin-top: 11px; background: #fafafa; border-radius: 7px; }
.settings-subsection-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.settings-subsection-heading > div { display: flex; flex-direction: column; gap: 4px; }
.settings-sensitivity-select { width: 205px; }
.sensitivity-options { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-top: 10px; }
.sensitivity-option { min-width: 0; display: flex; flex-direction: column; gap: 6px; padding: 8px; background: #ffffff; border: 1px solid #f5f5f5; border-radius: 6px; cursor: pointer; }
.sensitivity-option.is-selected { background: #f5f5f5; border-color: #dddddd; }
.sensitivity-option :deep(.el-radio) { margin-right: 0; height: auto; }
.sensitivity-option :deep(.el-radio__label) { color: #444444; font-size: 13px; white-space: normal; }
.sensitivity-option small { padding-left: 19px; color: #666666; font-size: 13px; line-height: 1.4; }
.keyword-section { padding-bottom: 11px; }
.keyword-count { color: #666666; font-size: 13px; }
.keyword-entry { display: flex; gap: 7px; margin-top: 10px; }
.keyword-entry .el-input { max-width: 360px; }
.keyword-entry .el-input__wrapper { background: #ffffff; }
.keyword-tags { display: flex; flex-wrap: wrap; gap: 6px; min-height: 25px; margin-top: 10px; }
.keyword-tags .el-tag { color: #666666; border-color: #eeeeee; background: #ffffff; font-size: 13px; }
.keyword-empty { color: #666666; font-size: 13px; }
.settings-validation-error { display: flex; flex-direction: column; gap: 4px; padding: 9px 11px; margin-top: 10px; color: #b42318; background: #f5f5f5; border-radius: 6px; font-size: 13px; }
.settings-validation-error strong { font-size: 13px; }
.community-reference-note { display: flex; align-items: flex-start; gap: 9px; padding: 11px; color: #444444; background: #f5f5f5; border-radius: 7px; }
.community-reference-note svg { flex: none; }
.community-reference-note p { margin: 0; font-size: 13px; line-height: 1.6; }
.community-scope { padding: 10px 11px; margin-top: 9px; color: #666666; background: #fafafa; border: 1px solid #f5f5f5; border-radius: 6px; font-size: 13px; line-height: 1.6; }
.settings-secondary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px; }
.settings-secondary-grid .settings-info-field { margin-top: 7px; }
.settings-persistence-note { padding-top: 8px; margin: 9px 0 0; color: #666666; border-top: 1px solid #f5f5f5; font-size: 13px; line-height: 1.55; }
.settings-save-bar { position: sticky; bottom: 0; z-index: 3; display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 11px 15px; margin: 15px 0 0; background: #ffffff; border: 1px solid #e5e5e5; border-radius: 9px; box-shadow: 0 -3px 15px rgba(0, 0, 0, .04); }
.settings-save-state { display: inline-flex; align-items: center; gap: 7px; color: #666666; font-size: 13px; }
.settings-save-state svg { color: #666666; }
.settings-save-bar > div { display: flex; gap: 7px; }
.settings-save-bar .el-button { margin: 0; }
@media (max-width: 1280px) { .settings-layout { grid-template-columns: 170px minmax(0, 1fr); gap: 11px; } .settings-info-field strong { font-size: 13px; } }
@media (max-width: 1050px) { .settings-layout { grid-template-columns: 1fr; } .settings-section-nav { position: static; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); } .settings-section-nav h2, .settings-nav-note { grid-column: 1 / -1; } }
@media (max-width: 720px) { .settings-info-grid, .settings-secondary-grid { grid-template-columns: 1fr; } .sensitivity-options { grid-template-columns: 1fr; } .settings-subsection-heading { align-items: flex-start; flex-direction: column; } .settings-sensitivity-select { width: 100%; } .settings-save-bar { align-items: flex-start; flex-direction: column; bottom: 0; } .settings-save-bar > div { width: 100%; } .settings-save-bar .el-button { flex: 1; } }
</style>
