<script setup lang="ts">
import type { Component } from 'vue'
import { RouterLink } from 'vue-router'
import {
  Bell, ChartNoAxesCombined, FileText, Megaphone, MessageSquareText,
  ScrollText, Settings2, ShieldAlert, UsersRound, KeyRound, Gift,
} from '@lucide/vue'
import { ADMIN_NAVIGATION } from '../../config/admin-navigation'
import { useContentModerationStore } from '../../stores/content-moderation'

const moderation = useContentModerationStore()

const icons: Record<string, Component> = {
  ChartNoAxesCombined, FileText, MessageSquareText,
  ShieldAlert, UsersRound, Megaphone, Bell, ScrollText, Settings2, KeyRound, Gift,
}
</script>

<template>
  <aside class="admin-sidebar" aria-label="后台导航">
    <div class="sidebar-brand">
      <img class="brand-mark" src="../../assets/xinkelink-mark.png" alt="" />
      <div class="brand-copy">
        <div class="brand-name">信科 Link</div>
        <div class="brand-caption">管理后台</div>
      </div>
      <span class="brand-tag">Admin</span>
    </div>

    <nav class="sidebar-menu" aria-label="管理菜单">
      <section v-for="group in ADMIN_NAVIGATION" :key="group.label" class="nav-group">
        <div class="nav-group-title">{{ group.label }}</div>
        <RouterLink v-for="item in group.items" :key="item.name" :to="item.path" :aria-label="item.label" class="nav-item">
          <component :is="icons[item.icon]" class="nav-item-icon" aria-hidden="true" />
          <span>{{ item.label }}</span>
          <span v-if="item.name === 'reports' && moderation.reportStats.pending" class="nav-count">{{ moderation.reportStats.pending }}</span>
        </RouterLink>
      </section>
    </nav>

    <footer class="sidebar-footer">
      <div class="sidebar-footer-school">河南信息科技学院</div>
      <div class="sidebar-footer-meta">信科 Link · 管理后台</div>
    </footer>
  </aside>
</template>

<style scoped>
.nav-count { min-width: 20px; height: 20px; padding: 0 6px; display: inline-grid; place-items: center; margin-left: auto; color: #ffffff; background: var(--xlk-accent-rose); border-radius: 999px; font-size: 11px; font-weight: 700; }
.nav-item.router-link-active .nav-count { color: var(--xlk-color-primary); background: var(--xlk-accent-lime); }
</style>
