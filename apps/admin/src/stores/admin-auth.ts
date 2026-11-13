import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { adminHttp, errorMessage, type AdminMe } from '../services/admin-http'

// Retained only to remove the obsolete marker; it never grants authentication.
export const ADMIN_SESSION_KEY = 'xinkelink.admin.demo.session'
export const REMEMBERED_ACCOUNT_KEY = 'xinkelink.admin.remembered-account'
function readRememberedAccount() { try { return globalThis.localStorage?.getItem(REMEMBERED_ACCOUNT_KEY) ?? '' } catch { return '' } }

export const useAdminAuthStore = defineStore('admin-auth', () => {
  try { globalThis.sessionStorage?.removeItem(ADMIN_SESSION_KEY) } catch { /* Legacy storage is optional. */ }
  const rememberedAccount = ref(readRememberedAccount())
  const loginError = ref('')
  const isAuthenticated = computed(() => Boolean(adminHttp.admin.value && adminHttp.accessToken))
  const currentAccount = computed(() => adminHttp.admin.value?.loginName ?? '')
  const displayName = computed(() => adminHttp.admin.value?.displayName ?? '')
  const administrator = adminHttp.admin
  async function signIn(accountInput: string, password: string, remember: boolean) {
    const account = accountInput.trim()
    if (!account || !password.trim()) { loginError.value = '请输入管理员账号和密码'; return false }
    loginError.value = ''
    try {
      await adminHttp.login(account, password)
      administrator.value = await adminHttp.request<AdminMe>('/me')
      try {
        if (remember) globalThis.localStorage?.setItem(REMEMBERED_ACCOUNT_KEY, account)
        else globalThis.localStorage?.removeItem(REMEMBERED_ACCOUNT_KEY)
      } catch { /* Remembering an account is optional and never changes authentication. */ }
      rememberedAccount.value = remember ? account : ''
      return true
    } catch (error) { adminHttp.clear(); loginError.value = errorMessage(error); return false }
  }
  async function signOut() { try { await adminHttp.logout() } catch (error) { loginError.value = errorMessage(error) } }
  const restoreSession = () => adminHttp.restore()
  function clearLoginError() { loginError.value = '' }
  return { isAuthenticated, rememberedAccount, loginError, currentAccount, displayName, administrator, signIn, signOut, restoreSession, clearLoginError }
})
