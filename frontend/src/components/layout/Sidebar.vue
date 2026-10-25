<template>
  <!-- 侧边导航：深墨蓝背景，按当前角色动态显示菜单 -->
  <el-aside :width="collapsed ? '64px' : '240px'" class="layout-aside">
    <div class="brand">
      <el-icon color="#2B6CB0"><Reading /></el-icon>
      <span v-show="!collapsed">LibMS 借阅系统</span>
    </div>
    <el-menu
      :default-active="activeMenu"
      :collapse="collapsed"
      background-color="#1F3552"
      text-color="#C0C8D4"
      active-text-color="#FFFFFF"
      :collapse-transition="false"
      router
    >
      <el-menu-item
        v-for="m in menus"
        :key="m.path"
        :index="'/' + m.path"
        :data-testid="'menu-' + m.path"
      >
        <el-icon><component :is="m.meta.icon" /></el-icon>
        <template #title>
          <span class="menu-row">
            <span class="menu-label">{{ menuTitle(m.path, m.meta.title, userStore.role) }}</span>
            <!-- 读者有未缴罚金时，在「我的借阅」旁给一行小红字，不打扰但看得见 -->
            <span v-if="showFineHint(m)" class="menu-fine-hint">{{ unpaidFineCount }} 笔待缴</span>
            <span v-if="showReservationHint(m)" class="menu-reserve-hint"
              >{{ reservationReadyCount }} 本待取</span
            >
          </span>
        </template>
      </el-menu-item>
    </el-menu>
  </el-aside>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { menuRoutes, menuTitle } from '@/router'
import { useUserStore } from '@/stores/user'

defineProps({ collapsed: { type: Boolean, default: false } })

const route = useRoute()

// 菜单数据来自守卫中 addRoute 动态注册的结果（已按角色过滤并剔除隐藏项）
const menus = computed(() => {
  const list = menuRoutes.value
  // 馆员每天的主业是办借还，把它提到第一行（管理员保持"管理类在前"的顺序）
  if (userStore.role === 'LIBRARIAN') {
    const idx = list.findIndex((m) => m.path === 'borrow-handle')
    if (idx > 0) {
      const copy = list.slice()
      const item = copy.splice(idx, 1)[0]
      copy.unshift(item)
      return copy
    }
  }
  // 读者：按"找书 → 我的书架 → 公告 → 借阅规则 → 个人中心"固定排序，
  // 贴合使用频率（进门先找书，通知与规则属查阅类，放后面）
  if (userStore.role === 'READER') {
    const order = ['book-search', 'my-borrow', 'notice-manage', 'rule-config', 'profile']
    const map = new Map(list.map((m) => [m.path, m]))
    const sorted = order.map((p) => map.get(p)).filter(Boolean)
    // 兜底：数组里若新增了读者可见页，追加到个人中心之前，不丢项
    const rest = list.filter((m) => !order.includes(m.path))
    if (rest.length) {
      const profile = sorted.pop()
      return profile ? [...sorted, ...rest, profile] : [...sorted, ...rest]
    }
    return sorted
  }
  return list
})

const activeMenu = computed(() => '/' + route.path.split('/')[1])

const userStore = useUserStore()
const unpaidFineCount = computed(() => userStore.unpaidFineCount)
const reservationReadyCount = computed(() => userStore.reservationReadyCount)

/** 只在「我的借阅」这一项上提示未缴罚金（罚金 Tab 就在该页内） */
function showFineHint(m) {
  return m.path === 'my-borrow' && unpaidFineCount.value > 0
}

/** 预约到书提示（待取 Tab 就在「我的借阅」内，到书后即登录即见） */
function showReservationHint(m) {
  return m.path === 'my-borrow' && reservationReadyCount.value > 0
}

onMounted(() => {
  userStore.refreshUnpaidFines()
  userStore.refreshReservationReady()
})

// 进入「我的借阅」（可能刚缴完费/取走预约）时重算一次，保证提示与后端一致
watch(
  () => route.path,
  (p) => {
    if (p.indexOf('my-borrow') >= 0) {
      userStore.refreshUnpaidFines()
      userStore.refreshReservationReady()
    }
  }
)
</script>

<style scoped>
.layout-aside {
  background: var(--libms-aside);
  transition: width 0.2s;
  overflow-x: hidden;
}
.brand {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  background: #182a42;
  white-space: nowrap;
}
.layout-aside :deep(.el-menu) {
  border-right: none;
}
/* 菜单项内部：标题占满，红字提示靠右 */
.menu-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
}
.menu-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.menu-fine-hint {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 8px;
  font-size: 10.5px;
  line-height: 16px;
  color: #ffb4b4;
  background: rgba(255, 90, 90, 0.16);
  border: 1px solid rgba(255, 120, 120, 0.28);
  animation: fineBlink 2.4s ease-in-out infinite;
}
/* 红字轻微呼吸，吸引注意但不刺眼 */
@keyframes fineBlink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.62;
  }
}
/* 预约到书：绿色系，与欠费红字区分 */
.menu-reserve-hint {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 8px;
  font-size: 10.5px;
  line-height: 16px;
  color: #b8f0d0;
  background: rgba(60, 200, 120, 0.16);
  border: 1px solid rgba(90, 220, 150, 0.28);
  animation: fineBlink 2.4s ease-in-out infinite;
}
/* 折叠时只留图标，提示会挤，直接隐藏 */
.layout-aside:has(:deep(.el-menu--collapse)) .menu-fine-hint,
.layout-aside:has(:deep(.el-menu--collapse)) .menu-reserve-hint {
  display: none;
}
/* 激活项：主色背景 + 左侧指示条 */
.layout-aside :deep(.el-menu-item.is-active) {
  background: #2b6cb0 !important;
}
</style>
