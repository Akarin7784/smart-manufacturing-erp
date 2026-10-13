<template>
  <el-empty :description="`${route.meta.title || '该模块'}页面开发中（P4）`" />
</template>

<script setup>
import { useRoute } from 'vue-router'
const route = useRoute()
</script>
