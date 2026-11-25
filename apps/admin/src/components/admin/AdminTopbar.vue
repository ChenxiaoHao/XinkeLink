<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, ChevronDown, LogOut, Search, UserRound } from '@lucide/vue'
import { useAdminAuthStore } from '../../stores/admin-auth'
import { ADMIN_NAV_ITEMS } from '../../config/admin-navigation'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuthStore()
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
.top-search-wrap { position: relative; }
.top-search :deep(kbd) { padding: 2px 5px; color: var(--xlk-color-muted); background: #ffffff; border: 1px solid var(--xlk-color-border); border-radius: 6px; font-family: inherit; font-size: 12px; }
.search-suggestions { position: absolute; top: calc(100% + 8px); right: 0; z-index: 5; width: min(360px, 78vw); padding: 6px; background: #ffffff; border: 1px solid var(--xlk-color-divider); border-radius: 16px; box-shadow: 0 16px 40px rgba(15, 23, 42, 0.12); }
.search-suggestion { width: 100%; display: flex; flex-direction: column; align-items: flex-start; gap: 3px; padding: 10px 12px; color: var(--xlk-color-text); background: #ffffff; border: 0; border-radius: 10px; text-align: left; cursor: pointer; }
.search-suggestion:hover { background: var(--xlk-color-primary-soft); }
.search-suggestion small, .search-no-result { color: var(--xlk-color-muted); font-size: 12px; }
.search-no-result { padding: 12px 10px; }
:deep(.el-dropdown-menu__item) { display: flex; align-items: center; gap: 8px; }
</style>
