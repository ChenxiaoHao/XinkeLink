import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { adminBeta, type BetaManagement, type BetaPhase, type BetaCodeStatus } from '../services/admin-beta'
import { ApiError, errorMessage } from '../services/admin-http'

export const useAdminBetaStore = defineStore('admin-beta', () => {
  const snapshot = ref<BetaManagement | null>(null)
  const loading = ref(false)
  const mutating = ref(false)
  const error = ref('')
  const updatedAt = ref<string | null>(null)
  let sequence = 0
  let readFlight: Promise<void> | null = null
  let mutationFlight: Promise<void> | null = null
  const phaseLabel = computed(() => loading.value ? '阶段加载中' : error.value ? '阶段获取失败' : !snapshot.value ? '阶段未知' : snapshot.value.phase === 'beta' ? '内测中' : '正式开放')
  const busy = computed(() => loading.value || mutating.value)

  function accept(data: BetaManagement, ticket: number) {
    if (ticket !== sequence || snapshot.value && data.version < snapshot.value.version) return
    snapshot.value = data
    updatedAt.value = new Date().toISOString()
    error.value = ''
  }
  function fail(reason: unknown, ticket: number) {
    if (ticket !== sequence) return
    error.value = errorMessage(reason)
    if (reason instanceof ApiError && [401, 403].includes(reason.status)) snapshot.value = null
  }
  function load(): Promise<void> {
    if (mutationFlight) return mutationFlight.catch(() => undefined)
    if (readFlight) return readFlight
    const ticket = ++sequence
    loading.value = true
    error.value = ''
    const flight = adminBeta.get().then(data => accept(data, ticket)).catch(reason => fail(reason, ticket)).finally(() => {
      if (readFlight === flight) { readFlight = null; loading.value = false }
    })
    readFlight = flight
    return flight
  }
  function mutate(action: () => Promise<BetaManagement>): Promise<void> {
    if (mutationFlight) return mutationFlight
    const ticket = ++sequence
    // The earlier GET may still be on the wire; it must not block a later refresh.
    readFlight = null
    mutating.value = true
    loading.value = false
    error.value = ''
    const flight = action().then(data => accept(data, ticket)).catch(async reason => {
      fail(reason, ticket)
      if (ticket === sequence && reason instanceof ApiError && reason.code === 'VERSION_CONFLICT') {
        try { accept(await adminBeta.get(), ticket) } catch (refreshError) { fail(refreshError, ticket) }
      }
      throw reason
    }).finally(() => { if (mutationFlight === flight) { mutationFlight = null; mutating.value = false } })
    mutationFlight = flight
    return flight
  }
  function initialize() { return mutate(() => adminBeta.initialize()) }
  function setPhase(phase: BetaPhase, expectedVersion: number) { return mutate(() => adminBeta.setPhase(phase, expectedVersion)) }
  function filterCodes(keyword: string, status: BetaCodeStatus | 'all') {
    const query = keyword.trim().toLocaleLowerCase()
    return (snapshot.value?.codes ?? []).filter(row => (status === 'all' || row.status === status)
      && (!query || `${row.code} ${row.user?.id ?? ''} ${row.user?.nickname ?? ''}`.toLocaleLowerCase().includes(query)))
  }
  function reset() {
    sequence++
    readFlight = null
    mutationFlight = null
    snapshot.value = null
    error.value = ''
    updatedAt.value = null
    loading.value = false
    mutating.value = false
  }
  return { snapshot, loading, mutating, busy, error, updatedAt, phaseLabel, load, initialize, setPhase, filterCodes, reset }
})
