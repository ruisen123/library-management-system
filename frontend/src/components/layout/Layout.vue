<template>
  <!-- 全局布局：左侧导航 + 顶部栏 + 内容区 -->
  <el-container class="layout-root">
    <Sidebar :collapsed="collapsed" />
    <!-- direction="vertical" 必须显式指定：Element Plus 的容器方向自动检测只识别直接的
         <el-header>/<el-footer> 标签，本项目的顶栏是自定义组件 <Navbar>，无法被识别，
         缺此属性时容器会退化为横向 row（顶栏与内容区并排 → 内容被推右、与顶栏顶格） -->
    <el-container direction="vertical">
      <Navbar :collapsed="collapsed" @toggle="collapsed = !collapsed" />
      <el-main class="layout-main">
        <!-- 路由出口：不包 transition（曾用 fade+out-in 过渡，但组件根节点带 v-loading 指令时
             会干扰 Vue Transition 的 transitionend 检测，导致切换后新组件不渲染、页面空白） -->
        <router-view />
      </el-main>
    </el-container>

    <!-- 图书助理：纯前端，浮动入口 + 抽屉，不占路由页面 -->
    <BookAssistant />
  </el-container>
</template>

<script setup>
import { ref, watchEffect, onBeforeUnmount } from 'vue'
import Sidebar from './Sidebar.vue'
import Navbar from './Navbar.vue'
import BookAssistant from '@/components/BookAssistant.vue'

const collapsed = ref(false) // 侧边栏折叠状态

// 弹窗需要按侧边栏宽度让位（见 styles/index.css 第 9 节）。弹窗的遮罩层挂在 body 下（teleport），
// 在它里面取不到组件状态，所以把宽度写到 :root 变量上，由全局样式使用。
// 取值与 Sidebar.vue 中 el-aside 的 width 保持一致（展开 240px / 折叠 64px）。
watchEffect(() => {
  document.documentElement.style.setProperty('--libms-aside-w', collapsed.value ? '64px' : '240px')
})
onBeforeUnmount(() => {
  document.documentElement.style.removeProperty('--libms-aside-w')
})
</script>

<style scoped>
.layout-root {
  height: 100vh;
}
.layout-main {
  /* 32px = 原 24px + 8px：与 styles/index.css 里 body margin 归零配套，
     保证内容卡片的左边距与改动前一致（改动前是 8px body margin + 24px 内边距） */
  padding: 32px;
  background: var(--libms-bg);
  overflow-y: auto;
  height: calc(100vh - 60px);
}
</style>
