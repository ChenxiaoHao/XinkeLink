<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useAttrs, watch } from 'vue'
import { ElAvatar, ElImage } from 'element-plus'
import { adminHttp, ApiError } from '../../services/admin-http'
import { adminImageDue, renewAdminImage } from '../../services/admin-images'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{ src?: string; previewSrcList?: string[]; avatar?: boolean }>(), { src: '', previewSrcList: () => [], avatar: false })
const attrs = useAttrs()
const replacements = ref<Record<string, string>>({})
const sources = computed(() => [...new Set([props.src, ...props.previewSrcList].filter(Boolean))])
const resolved = (source: string) => replacements.value[source] ?? source
const retryAfter = new Map<string, number>()
let active = true, timer: ReturnType<typeof setTimeout> | undefined

// 仅更新图片组件的地址，表格、抽屉和业务状态不会重新加载。
async function refresh(force = false) {
  if (!active) return
  const owner = adminHttp.admin.value?.id
  await Promise.all(sources.value.map(async source => {
    if ((retryAfter.get(source) ?? 0) > Date.now() || (!force && !adminImageDue(resolved(source)))) return
    let url: string
    try { url = await renewAdminImage(resolved(source)); retryAfter.delete(source) }
    catch (error) {
      retryAfter.set(source, Date.now() + 60000)
      if (!(error instanceof ApiError) || ![401, 403, 404].includes(error.status)) return
      url = '' // 权限拒绝时不能回退到原来的签名地址。
    }
    if (active && owner === adminHttp.admin.value?.id && sources.value.includes(source)) replacements.value[source] = url
  }))
}
async function tick() {
  await refresh()
  if (active) timer = setTimeout(() => { void tick() }, 15000)
}
watch(sources, () => { void refresh() })
onMounted(() => { void tick() })
onBeforeUnmount(() => { active = false; if (timer) clearTimeout(timer) })
</script>

<template>
  <component :is="avatar ? ElAvatar : ElImage" v-bind="attrs" :src="resolved(src)" :preview-src-list="previewSrcList.map(resolved)" @error="refresh(true)">
    <template v-for="(_, name) in $slots" #[name]="slotProps"><slot :name="name" v-bind="slotProps || {}" /></template>
  </component>
</template>
