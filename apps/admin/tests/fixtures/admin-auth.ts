import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export const ADMIN_SESSION_KEY = 'xinkelink.admin.demo.session'
export const REMEMBERED_ACCOUNT_KEY = 'xinkelink.admin.remembered-account'

function readSession() {
  try { return globalThis.sessionStorage?.getItem(ADMIN_SESSION_KEY) === '1' } catch { return false }
}

function readRememberedAccount() {
  try { return globalThis.localStorage?.getItem(REMEMBERED_ACCOUNT_KEY) ?? '' } catch { return '' }
}

export const useAdminAuthStore = defineStore('admin-auth', () => {
  const isAuthenticated = ref(readSession())
  const rememberedAccount = ref(readRememberedAccount())
  const loginError = ref('')
  const currentAccountState = ref('')
  const currentAccount = computed(() => currentAccountState.value)

  async function signIn(accountInput: string, password: string, remember: boolean) {
    const account = accountInput.trim()
    if (!account || !password.trim()) {
      loginError.value = '请输入管理员账号和密码'
      return false
    }
    loginError.value = ''
    await new Promise(resolve => setTimeout(resolve, 280))
    try {
      globalThis.sessionStorage.setItem(ADMIN_SESSION_KEY, '1')
      if (remember) globalThis.localStorage.setItem(REMEMBERED_ACCOUNT_KEY, account)
      else globalThis.localStorage.removeItem(REMEMBERED_ACCOUNT_KEY)
      rememberedAccount.value = remember ? account : ''
      currentAccountState.value = account
      isAuthenticated.value = true
      return true
    } catch {
      try { globalThis.sessionStorage.removeItem(ADMIN_SESSION_KEY) } catch { /* Keep failed demo login closed. */ }
      loginError.value = '本地演示登录失败，请检查浏览器存储后重试'
      return false
    }
  }

  function signOut() {
    try { globalThis.sessionStorage.removeItem(ADMIN_SESSION_KEY) } catch { /* The in-memory session is still closed. */ }
    currentAccountState.value = ''
    isAuthenticated.value = false
  }

  function clearLoginError() { loginError.value = '' }

  return { isAuthenticated, rememberedAccount, loginError, currentAccount, signIn, signOut, clearLoginError }
})
