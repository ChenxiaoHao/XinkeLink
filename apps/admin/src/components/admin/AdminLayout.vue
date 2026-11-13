<script setup lang="ts">
import AdminSidebar from './AdminSidebar.vue'
import AdminTopbar from './AdminTopbar.vue'
</script>

<template>
  <div class="admin-shell">
    <AdminSidebar />
    <section class="admin-workspace">
      <AdminTopbar />
      <main class="main-scroll" data-testid="main-scroll"><RouterView /></main>
    </section>
  </div>
</template>
