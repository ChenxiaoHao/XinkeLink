<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, BookOpenCheck, Eye, EyeOff, LockKeyhole, ShieldCheck, Sparkles, UserRound } from '@lucide/vue'
import logo from '../assets/xinkelink-mark.png'
import { useAdminAuthStore } from '../stores/admin-auth'

const route = useRoute()
const router = useRouter()
const auth = useAdminAuthStore()
const account = ref(auth.rememberedAccount)
const password = ref('')
const remember = ref(Boolean(auth.rememberedAccount))
const visiblePassword = ref(false)
const loading = ref(false)
const showError = computed(() => auth.loginError.length > 0)

async function submit() {
  if (!account.value.trim() || !password.value.trim()) {
    auth.loginError = '请输入管理员账号和密码'
    return
  }
  loading.value = true
  const ok = await auth.signIn(account.value, password.value, remember.value)
  loading.value = false
  password.value = ''
  if (!ok) return
  const requestedPath = typeof route.query.redirect === 'string' ? route.query.redirect : '/admin/dashboard'
  const safePath = requestedPath.startsWith('/admin/') ? requestedPath : '/admin/dashboard'
  ElMessage.success('登录成功')
  await router.replace(safePath)
}
</script>

<template>
  <div class="login-page">
    <header class="login-header">
      <div class="login-brand">
        <img :src="logo" alt="信科 Link 标志" />
        <div><div class="login-brand-name">信科 Link</div><div class="login-brand-caption">管理后台</div></div>
      </div>
      <div class="login-environment"><span class="environment-dot" />管理工作台</div>
    </header>

    <main class="login-main">
      <div class="login-split">
        <section class="login-hero" aria-label="平台概览">
          <div class="login-hero-status"><span class="login-hero-dot" aria-hidden="true" />SYSTEM ONLINE · UNIFIED ACCESS</div>
          <h2 class="login-hero-title">AI 驱动，<br />校园社区<span class="login-hero-accent">智能治理</span>。</h2>
          <p class="login-hero-desc">面向河南信息科技学院校园社区的统一管理工作台，覆盖内容审核、举报处置、操作审计和 Link AI 知识库管理。</p>
          <div class="login-feature-grid">
            <article class="login-feature-card">
              <span class="login-feature-icon"><ShieldCheck aria-hidden="true" /></span>
              <strong>社区内容治理</strong>
              <span>帖子、评论与举报队列联动处置</span>
            </article>
            <article class="login-feature-card">
              <span class="login-feature-icon"><Sparkles aria-hidden="true" /></span>
              <strong>AI 知识库</strong>
              <span>官方校务资料导入与人工校对发布</span>
            </article>
            <article class="login-feature-card">
              <span class="login-feature-icon"><BookOpenCheck aria-hidden="true" /></span>
              <strong>操作审计</strong>
              <span>查询与导出管理员操作日志</span>
            </article>
          </div>
        </section>

        <section class="login-card" aria-labelledby="login-title">
          <div class="login-card-brand">
            <img :src="logo" alt="" />
            <span class="login-card-brand-name">信科 Link</span>
            <span class="login-role-chip">管理员</span>
          </div>
          <h1 id="login-title" class="login-welcome">欢迎回来</h1>
          <p class="login-intro">登录管理后台，继续管理校园社区。</p>

          <el-alert v-if="showError" class="login-error" type="error" :title="auth.loginError" :closable="false" show-icon />

          <form @submit.prevent="submit">
            <label class="login-form-label" for="admin-account">管理员账号</label>
            <el-input id="admin-account" v-model="account" class="login-form-item" autocomplete="username" placeholder="请输入管理员账号" @input="auth.clearLoginError()">
              <template #prefix><UserRound class="login-field-icon" /></template>
            </el-input>

            <label class="login-form-label" for="admin-password">管理员密码</label>
            <el-input id="admin-password" v-model="password" class="login-form-item" autocomplete="current-password" :type="visiblePassword ? 'text' : 'password'" placeholder="请输入密码" @input="auth.clearLoginError()">
              <template #prefix><LockKeyhole class="login-field-icon" /></template>
              <template #suffix>
                <button class="password-toggle" type="button" :aria-label="visiblePassword ? '隐藏密码' : '显示密码'" @click="visiblePassword = !visiblePassword">
                  <EyeOff v-if="visiblePassword" /><Eye v-else />
                </button>
              </template>
            </el-input>

            <div class="login-options">
              <el-checkbox v-model="remember">记住账号</el-checkbox>
              <button class="login-help" type="button" @click="ElMessage.info('请使用已配置的管理员账号；账号问题请联系平台维护人员。')">登录说明</button>
            </div>
            <el-button class="login-submit" type="primary" native-type="submit" :loading="loading">
              <span class="login-submit-inner">
                <span>进入管理后台</span>
                <span class="login-submit-arrow" aria-hidden="true"><ArrowRight :size="15" /></span>
              </span>
            </el-button>
          </form>

          <div class="login-security"><ShieldCheck aria-hidden="true" />管理员身份由服务器验证</div>
          <div class="login-demo-hint">仅记住账号；不会保存密码。</div>
        </section>
      </div>
    </main>
    <footer class="login-footer">© {{ new Date().getFullYear() }} 信科 Link · 校园社区管理工具<br />仅供授权管理员使用</footer>
  </div>
</template>
