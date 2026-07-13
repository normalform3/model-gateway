<script setup lang="ts">defineProps<{ title: string; description: string }>();</script>
<template><section class="page-stack"><header class="page-heading"><div><p class="eyebrow">Role workspace</p><h1>{{ title }}</h1><p>{{ description }}</p></div></header><section class="surface empty-state"><h2>此角色的独立页面将在登录系统接入后启用</h2><p>当前仅完成角色菜单与管理员控制面，未用模拟数据替代真实权限和数据边界。</p></section></section></template>
