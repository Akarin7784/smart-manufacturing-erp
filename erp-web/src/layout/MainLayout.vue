<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">ERP 管理系统</div>
      <el-menu :default-active="route.path" router background-color="#001529" text-color="#bfcbd9" active-text-color="#409eff">
        <el-sub-menu index="mdm">
          <template #title>主数据</template>
          <el-menu-item index="/mdm/materials">物料</el-menu-item>
          <el-menu-item index="/mdm/customers">客户</el-menu-item>
          <el-menu-item index="/mdm/suppliers">供应商</el-menu-item>
          <el-menu-item index="/mdm/warehouses">仓库</el-menu-item>
        </el-sub-menu>
        <el-menu-item index="/purchase">采购</el-menu-item>
        <el-menu-item index="/sales">销售</el-menu-item>
        <el-menu-item index="/inventory">库存</el-menu-item>
        <el-menu-item index="/finance">财务</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title }}</span>
        <div class="user">
          <span data-test="username">{{ auth.username || '未知用户' }}</span>
          <el-button link type="primary" data-test="logout" @click="onLogout">退出登录</el-button>
        </div>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

async function onLogout() {
  auth.logout()
  ElMessage.success('已退出登录')
  await router.replace({ name: 'login' })
}
defineExpose({ onLogout })
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #001529; }
.logo { color: #fff; height: 60px; line-height: 60px; text-align: center; font-weight: bold; }
.header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #eee; }
.user { display: flex; gap: 12px; align-items: center; }
</style>
