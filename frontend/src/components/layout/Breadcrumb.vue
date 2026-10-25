<template>
  <!-- 面包屑：根据当前路由层级自动生成 -->
  <el-breadcrumb separator="/">
    <el-breadcrumb-item :to="{ path: '/' }">{{ userStore.roleLabel }}工作台</el-breadcrumb-item>
    <el-breadcrumb-item v-for="item in matched" :key="item.path">{{
      menuTitle(item.path, item.meta.title, userStore.role)
    }}</el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { menuTitle } from '@/router'

const route = useRoute()
const userStore = useUserStore()

// 过滤出带 title 的匹配记录，形成层级面包屑
const matched = computed(() => route.matched.filter((m) => m.meta && m.meta.title))
</script>
