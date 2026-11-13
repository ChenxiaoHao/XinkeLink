<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, ChevronDown, LogOut, Search, UserRound } from '@lucide/vue'
import { useAdminAuthStore } from '../../stores/admin-auth'
import { ADMIN_NAV_ITEMS } from '../../config/admin-navigation'
import { useAdminBetaStore } from '../../stores/admin-beta'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuthStore()
const beta = useAdminBetaStore()
onMounted(() => { void beta.load() })
onUnmounted(() => { beta.reset() })
const searchText = ref('')
const showSuggestions = ref(false)
const currentTitle = computed(() => String(route.meta.title ?? '数据概览'))
const suggestions = computed(() => {
  const query = searchText.value.trim().toLocaleLowerCase()
  if (!query) return []
  return ADMIN_NAV_ITEMS.filter(item => `${item.label} ${item.description}`.toLocaleLowerCase().includes(query)).slice(0, 5)
})

function openDestination(path: string) {
  searchText.value = ''
  showSuggestions.value = false
  void router.push(path)
}

async function signOut() {
  await auth.signOut()
  void router.replace({ name: 'login' })
}

function hideSuggestions() {
  setTimeout(() => { showSuggestions.value = false }, 120)
}
</script>

<template>
  <header class="admin-topbar">
    <div class="breadcrumb" aria-label="当前位置">
      <span>工作台</span><span aria-hidden="true">/</span><span class="breadcrumb-current">{{ currentTitle }}</span>
    </div>
    <div class="topbar-actions">
      <RouterLink to="/admin/beta" class="topbar-phase" data-testid="beta-phase-badge" aria-label="查看内测阶段">{{ beta.phaseLabel }}</RouterLink>
      <div class="top-search-wrap">
        <el-input
          v-model="searchText"
          class="top-search"
          aria-label="搜索管理菜单"
          placeholder="搜索管理菜单"
          clearable
          @focus="showSuggestions = true"
          @input="showSuggestions = true"
          @keyup.enter="suggestions[0] && openDestination(suggestions[0].path)"
          @blur="hideSuggestions"
        >
          <template #prefix><Search :size="15" /></template>
          
        </el-input>
        <div v-if="showSuggestions && searchText" class="search-suggestions">
          <button v-for="item in suggestions" :key="item.name" type="button" class="search-suggestion" @mousedown.prevent="openDestination(item.path)">
            <span>{{ item.label }}</span><small>{{ item.description }}</small>
          </button>
          <div v-if="suggestions.length === 0" class="search-no-result">没有匹配的管理菜单</div>
        </div>
      </div>

      <button class="topbar-icon-button" type="button" aria-label="打开通知管理" @click="openDestination('/admin/notifications')">
        <Bell aria-hidden="true" /><span class="notification-dot" aria-hidden="true" />
      </button>

      <el-dropdown trigger="click" @command="signOut">
        <button class="account-trigger" type="button" aria-label="管理员菜单">
          <span class="account-avatar"><UserRound aria-hidden="true" /></span>
          <span class="account-copy"><span class="account-name">{{ auth.displayName || auth.currentAccount }}</span><span class="account-role">管理员</span></span>
          <ChevronDown class="account-caret" aria-hidden="true" />
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="signout"><LogOut :size="15" />退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.topbar-phase { flex: none; padding: 4px 8px; border: 1px solid #e5e5e5; border-radius: 4px; color: #555; font-size: 12px; white-space: nowrap; text-decoration: none; }
.top-search-wrap { position: relative; }
.top-search :deep(kbd) { padding: 2px 5px; color: #666666; background: #ffffff; border: 1px solid #eeeeee; border-radius: 4px; font-family: inherit; font-size: 12px; }
.search-suggestions { position: absolute; top: calc(100% + 7px); right: 0; z-index: 5; width: min(360px, 78vw); padding: 5px; background: #ffffff; border: 1px solid #eeeeee; border-radius: 9px; box-shadow: 0 8px 24px rgba(0, 0, 0, .13); }
.search-suggestion { width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 3px; padding: 9px 10px; color: #303030; background: #ffffff; border: 0; border-radius: 6px; text-align: left; cursor: pointer; }
.search-suggestion:hover { background: #fafafa; }
.search-suggestion small, .search-no-result { color: #666666; font-size: 12px; }
.search-no-result { padding: 12px 10px; }
:deep(.el-dropdown-menu__item) { display: flex; align-items: center; gap: 8px; }
</style>
