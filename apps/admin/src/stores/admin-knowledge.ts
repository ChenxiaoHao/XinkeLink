import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { adminHttp, ApiError, errorMessage } from '../services/admin-http'
import { adminKnowledge, type KnowledgeDraft, type KnowledgeDocument } from '../services/admin-knowledge'

export const useKnowledgeStore = defineStore('admin-knowledge', () => {
  const items = ref<KnowledgeDocument[]>([]), loading = ref(false), loaded = ref(false), mutating = ref(false), error = ref(''), writeForbidden = ref(false)
  const ready = ref<boolean | null>(null)
  let session = 0, sequence = 0
  watch(adminHttp.admin, (next, previous) => {
    if (next?.id === previous?.id) return
    session++; sequence++; items.value = []; ready.value = null; loaded.value = false; loading.value = false; mutating.value = false; error.value = ''; writeForbidden.value = false
  }, { flush: 'sync' })
  async function load() {
    const owner = session, request = ++sequence; loading.value = true; error.value = ''
    try { const data = await adminKnowledge.list(); if (owner === session && request === sequence) { items.value = data.items; ready.value = data.ready; loaded.value = true } }
    catch (reason) { if (owner === session && request === sequence) { error.value = errorMessage(reason); ready.value = null; loaded.value = false } }
    finally { if (owner === session && request === sequence) loading.value = false }
  }
  async function mutate(action: () => Promise<KnowledgeDocument>) {
    if (mutating.value) throw new Error('操作进行中，请等待结果')
    if (writeForbidden.value) throw new Error('当前账号没有写入权限，请重新登录')
    const owner = session; mutating.value = true
    try { const result = await action(); if (owner !== session) throw new Error('登录状态已变化，请重新打开页面'); await load(); return result }
    catch (reason) { if (owner === session && reason instanceof ApiError && reason.status === 403) writeForbidden.value = true; throw reason }
    finally { if (owner === session) mutating.value = false }
  }
  const save = (body: KnowledgeDraft, id?: string) => mutate(() => adminKnowledge.save(body, id))
  async function publish(row: KnowledgeDocument) {
    if (!row.reviewed) throw new Error('请先校对正文，确认已校对并保存草稿')
    if (![row.title, row.source, row.version, row.scope, row.validity, row.body].every(value => value?.trim())) throw new Error('发布前请补齐标题、来源、版本、适用范围、有效性及正文')
    return mutate(() => adminKnowledge.publish(row.id))
  }
  const disable = (row: KnowledgeDocument) => mutate(() => adminKnowledge.disable(row.id))
  return { items, ready, loading, loaded, mutating, error, writeForbidden, load, save, publish, disable }
})
