<template>
  <!-- 全局 Element Plus 中文语言包 + 路由出口 -->
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
// 按需引入模式下无全局 app.use(ElementPlus, { locale })，在此统一提供中文 locale
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
