<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();

const title = computed(() => String(route.meta.title || '功能'));
</script>

<template>
  <div class="placeholder-page">
    <section class="placeholder-card">
      <h1>{{ title }}</h1>
      <p>正在开发中</p>
    </section>
  </div>
</template>

<style scoped>
.placeholder-page {
  min-height: calc(100vh - 112px);
  display: grid;
  place-items: center;
  padding: 48px 24px;
  background: var(--cf-page-bg);
  color: var(--cf-text-primary);
}

.placeholder-card {
  width: min(560px, 100%);
  min-height: 260px;
  border: 1px solid var(--cf-card-border);
  border-radius: 18px;
  background: var(--cf-card-bg);
  box-shadow: var(--cf-card-shadow);
  display: grid;
  place-items: center;
  gap: 10px;
  align-content: center;
  text-align: center;
}

.placeholder-card h1 {
  margin: 0;
  font-size: 22px;
}

.placeholder-card p {
  margin: 0;
  color: var(--cf-text-muted);
  font-size: 18px;
  font-weight: 800;
}
</style>
