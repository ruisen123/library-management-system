<template>
  <!-- 顶部导航：折叠按钮 + 面包屑 + 用户下拉 -->
  <el-header class="layout-header">
    <div class="header-left">
      <el-icon size="18" color="#2B6CB0" style="cursor: pointer" @click="emit('toggle')">
        <Fold v-if="!collapsed" />
        <Expand v-else />
      </el-icon>
      <Breadcrumb />
    </div>
    <div class="header-right">
      <!-- 消息中心入口：仅读者有站内消息，非读者不渲染铃铛 -->
      <el-badge
        v-if="isReader"
        class="notify-badge"
        :value="notificationStore.unread"
        :hidden="notificationStore.unread === 0"
        :max="99"
      >
        <el-button
          link
          aria-label="消息中心"
          data-testid="notification-bell"
          @click="router.push('/notifications')"
        >
          <el-icon size="18"><Bell /></el-icon>
        </el-button>
      </el-badge>
      <el-dropdown @command="onCommand">
        <div class="header-user">
          <el-avatar :size="34" style="background: #2b6cb0">{{
            userStore.userInfo.realName?.charAt(0)
          }}</el-avatar>
          <span class="name">{{ userStore.userInfo.realName }}</span>
          <span v-if="userStore.userInfo.realName !== userStore.roleLabel" class="role">{{
            userStore.roleLabel
          }}</span>
          <el-icon color="#909399"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">个人中心</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </el-header>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useNotificationStore } from '@/stores/notification'
import { msg } from '@/utils/message'
import Breadcrumb from './Breadcrumb.vue'

defineProps({ collapsed: { type: Boolean, default: false } })
const emit = defineEmits(['toggle'])

const router = useRouter()
const userStore = useUserStore()
const notificationStore = useNotificationStore()

const isReader = computed(() => userStore.userInfo.role === 'READER')

onMounted(() => notificationStore.startPolling(userStore.userInfo.role))
onBeforeUnmount(() => notificationStore.stopPolling())

const onCommand = (cmd) => {
  if (cmd === 'logout') {
    userStore.logout()
    msg.info('已退出登录')
    router.push('/login')
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}
</script>

<style scoped>
.layout-header {
  height: 60px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  /* 32px = 原 24px + 8px：与 styles/index.css 里 body margin 归零配套，
     让顶栏文字与窗口右边的距离与改动前保持一致（改动前是 8px body margin + 24px 内边距） */
  padding: 0 32px;
  border-bottom: 1px solid #e4e7ed;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.header-user {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
}
.notify-badge :deep(.el-badge__content) {
  border: none;
}
.header-user .name {
  color: #303133;
}
.header-user .role {
  font-size: 12px;
  color: #909399;
}
</style>
