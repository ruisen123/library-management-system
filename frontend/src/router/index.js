import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ref } from 'vue'

/**
 * 路由表 + 登录/权限守卫（addRoute 动态路由模式）
 * meta 约定：
 *   title  —— 页面标题（面包屑 / 浏览器标签页）
 *   roles  —— 允许访问的角色数组（缺省表示登录即可访问）
 *   icon   —— 侧边菜单图标名（Element Plus 图标）
 *   hidden —— true 时不显示在侧边菜单（如图书详情页）
 */

/**
 * 同一路由在不同角色下的不同叫法。
 * 公告页是三角色共用的：只有管理员能编辑/撤回/删除、能看已撤回，才叫「公告管理」；
 * 馆员只能发布、读者只能看，都叫「公告」。菜单与面包屑都必须走这里，否则会出现
 * 「菜单叫公告、页面标题叫公告管理」的自相矛盾。
 */
const ROLE_TITLE_OVERRIDE = {
  'notice-manage': { ADMIN: '公告管理' }
}

/** @param {string} path 路由 path（可带或不带前导 /） @param {string} fallback meta.title @param {string} role 当前角色 */
export function menuTitle(path, fallback, role) {
  const key = String(path || '').replace(/^\/+/, '')
  const override = ROLE_TITLE_OVERRIDE[key]
  return (override && role && override[role]) || fallback
}

// 各角色登录后的默认首页
// 馆员登录直接落在借还办理（日常主业），而不是图书档案
export const ROLE_HOME = { ADMIN: '/dashboard', LIBRARIAN: '/borrow-handle', READER: '/book-search' }

// 布局下的全部子路由（静态定义，仅作“注册源”）：登录后按角色过滤，通过 router.addRoute 动态注入
export const layoutChildren = [
  // —— 平台管理（系统管理员）——
  {
    path: 'dashboard',
    name: 'Dashboard',
    component: () => import('@/views/admin/Dashboard.vue'),
    meta: { title: '数据总览', roles: ['ADMIN'], icon: 'DataLine' }
  },
  {
    path: 'user-manage',
    name: 'UserManage',
    component: () => import('@/views/admin/UserManage.vue'),
    meta: { title: '用户管理', roles: ['ADMIN'], icon: 'UserFilled' }
  },
  // —— 业务管理（管理类集中在前面）——
  {
    path: 'book-manage',
    name: 'BookManage',
    component: () => import('@/views/librarian/BookManage.vue'),
    meta: { title: '图书档案管理', roles: ['ADMIN', 'LIBRARIAN'], icon: 'Collection' }
  },
  {
    path: 'category-manage',
    name: 'CategoryManage',
    component: () => import('@/views/admin/CategoryManage.vue'),
    meta: { title: '分类管理', roles: ['ADMIN'], icon: 'Menu' }
  },
  {
    path: 'publisher-manage',
    name: 'PublisherManage',
    component: () => import('@/views/admin/PublisherManage.vue'),
    meta: { title: '出版社管理', roles: ['ADMIN'], icon: 'OfficeBuilding' }
  },
  {
    path: 'notice-manage',
    name: 'NoticeManage',
    component: () => import('@/views/admin/NoticeManage.vue'),
    // 三角色可见：管理员管理公告、馆员可发布、读者只读（页面内按角色切换视图）
    meta: { title: '公告', roles: ['ADMIN', 'LIBRARIAN', 'READER'], icon: 'Bell' }
  },
  {
    path: 'fine-manage',
    name: 'FineManage',
    component: () => import('@/views/librarian/FineManage.vue'),
    meta: { title: '罚金管理', roles: ['ADMIN', 'LIBRARIAN'], icon: 'Coin' }
  },
  // —— 借还业务 ——
  {
    path: 'borrow-handle',
    name: 'BorrowHandle',
    component: () => import('@/views/librarian/BorrowHandle.vue'),
    meta: { title: '借还办理', roles: ['ADMIN', 'LIBRARIAN'], icon: 'Tickets' }
  },
  {
    path: 'borrow-record',
    name: 'BorrowRecord',
    component: () => import('@/views/librarian/BorrowRecord.vue'),
    meta: { title: '借阅记录查询', roles: ['ADMIN', 'LIBRARIAN'], icon: 'List' }
  },
  // —— 说明与留痕 ——
  {
    path: 'rule-config',
    name: 'RuleConfig',
    component: () => import('@/views/admin/RuleConfig.vue'),
    // 三角色都开放：馆员要能向读者解释规则、读者也要能自己查（页面纯只读）
    meta: { title: '借阅规则', roles: ['ADMIN', 'LIBRARIAN', 'READER'], icon: 'Setting' }
  },
  {
    path: 'operation-log',
    name: 'OperationLog',
    component: () => import('@/views/admin/OperationLog.vue'),
    meta: { title: '操作日志', roles: ['ADMIN'], icon: 'Document' }
  },
  // —— 读者端 ——
  {
    path: 'book-search',
    name: 'BookSearch',
    component: () => import('@/views/reader/BookSearch.vue'),
    meta: { title: '找书', roles: ['READER'], icon: 'Search' }
  },
  {
    path: 'book-detail/:id',
    name: 'BookDetail',
    component: () => import('@/views/reader/BookDetail.vue'),
    meta: { title: '图书详情', roles: ['READER'], icon: 'Reading', hidden: true }
  },
  {
    path: 'my-borrow',
    name: 'MyBorrow',
    component: () => import('@/views/reader/MyBorrow.vue'),
    meta: { title: '我的书架', roles: ['READER'], icon: 'Notebook' }
  },
  {
    path: 'notifications',
    name: 'NotificationCenter',
    component: () => import('@/views/reader/NotificationCenter.vue'),
    // 入口在顶栏铃铛，不占侧边菜单；页面组件数计入总数（16 → 17）
    meta: { title: '消息中心', roles: ['READER'], hidden: true }
  },
  // —— 公共（三角色共用）——
  {
    path: 'profile',
    name: 'Profile',
    component: () => import('@/views/Profile.vue'),
    meta: { title: '个人中心', roles: ['ADMIN', 'LIBRARIAN', 'READER'], icon: 'Avatar' }
  }
]

export const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/components/layout/Layout.vue'),
    // 根路径按当前登录角色跳转首页（未登录跳登录页，由守卫兜底）
    redirect: () => {
      try {
        const user = JSON.parse(localStorage.getItem('user') || '{}')
        return ROLE_HOME[user.role] || '/login'
      } catch (e) {
        return '/login'
      }
    },
    // 子路由不静态注册，由 setupDynamicRoutes 按角色动态注入
    children: []
  },
  {
    path: '/:pathMatch(.*)*',
    // 已登录刷新深层路由时（如直接回车 /borrow-handle），catch-all 会在"解析阶段"早于守卫触发，
    // 把原路径吞成 /login；故用函数式 redirect 把原路径带在 query 里，交给守卫 step2 回跳。
    // 未登录时维持原行为（直接去 /login，不带 redirect）。
    redirect: (to) =>
      localStorage.getItem('token') ? { path: '/login', query: { redirect: to.path } } : '/login'
  }
]

const router = createRouter({
  history: createWebHistory(), // history 模式
  routes
})

// —— 动态路由状态（W5 修复：addRoute 动态路由）——
/** 当前已注册路由对应的角色（null 表示尚未注册） */
let registeredRole = null
/** 当前角色可见的菜单（已过滤 hidden 与无权限项），供侧边栏渲染 */
export const menuRoutes = ref([])

/** 按角色过滤子路由并注入路由表（幂等：同一角色不重复注册） */
export function setupDynamicRoutes(role) {
  if (registeredRole === role) return
  // 先移除旧角色注入的子路由，再注入新角色的
  layoutChildren.forEach((child) => {
    if (router.hasRoute(child.name)) router.removeRoute(child.name)
  })
  const visible = layoutChildren.filter(
    (child) => !child.meta || !child.meta.roles || child.meta.roles.includes(role)
  )
  visible.forEach((child) => router.addRoute('Layout', child))
  menuRoutes.value = visible.filter((child) => !child.meta || !child.meta.hidden)
  registeredRole = role
}

/** 退出登录 / 登录失效时调用：清空已注入的动态路由 */
export function resetDynamicRoutes() {
  layoutChildren.forEach((child) => {
    if (router.hasRoute(child.name)) router.removeRoute(child.name)
  })
  menuRoutes.value = []
  registeredRole = null
}

// —— 全局前置守卫 ——
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  let user = {}
  try {
    user = JSON.parse(localStorage.getItem('user') || '{}')
  } catch (e) {
    user = {}
  }

  // 浏览器标签页标题也要按角色解析，否则管理员会看到「公告管理」页面配「公告」标签页
  const pageTitle = menuTitle(to.path, to.meta.title, user.role)
  document.title = pageTitle ? `${pageTitle} - LibMS 图书借阅管理系统` : 'LibMS 图书借阅管理系统'

  // 0. 动态路由前置注册（根因修复）：
  //    原实现在第 3 步才注册，但 catch-all 会在守卫执行前把未注册路径 redirect 回 /login，
  //    导致已登录跳转时 to 恒为 /login、第 2 步 next(首页) 再次解析失败，形成无限重定向死循环。
  //    将注册前置：只要登录且角色合法，先注入动态路由，后续跳转才能解析到真实页面。
  //    条件必须以"token 存在"为前提、并按"角色是否合法"二分支穷尽：若把"角色为空"漏在门外（原
  //    `token && user.role && ...` 的写法），第 2 步会把 next 指向未注册的首页，被 catch-all 折回
  //    /login 形成同址重定向回路；而 vue-router 的回路保护仅在非生产构建中生效（生产构建不收敛）。
  if (token) {
    if (['ADMIN', 'LIBRARIAN', 'READER'].includes(user.role)) {
      if (registeredRole !== user.role) {
        setupDynamicRoutes(user.role)
      }
    } else {
      // 角色缺失/损坏/被篡改：清理本地状态并踢回登录；清掉 token 后第二遍必然走"未登录"分支正常渲染登录页
      resetDynamicRoutes()
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      next('/login')
      return
    }
  }

  // 1. 未登录：仅放行登录页，并清理可能残留的动态路由
  if (!token) {
    resetDynamicRoutes()
    if (to.path === '/login') next()
    else next('/login')
    return
  }

  // 2. 已登录访问登录页：优先回跳刷新前所在页（catch-all 已把原路径转成 ?redirect=），否则跳角色首页
  if (to.path === '/login') {
    const r = to.query.redirect
    // 仅接受站内路径（单个 / 开头，拒绝 // 与外链，防开放重定向），且必须能解析到已注册路由；
    // 权限校验无需在此重复：next(r) 触发的新导航会再次经过本守卫第 3 步
    if (
      typeof r === 'string' &&
      r.startsWith('/') &&
      !r.startsWith('//') &&
      router.resolve(r).matched.length > 1
    ) {
      next(r)
      return
    }
    next(ROLE_HOME[user.role] || '/dashboard')
    return
  }

  // 3. 角色权限校验（防手输 URL 越权）
  if (to.meta.roles && !to.meta.roles.includes(user.role)) {
    ElMessage.warning('您没有权限访问该页面')
    next(ROLE_HOME[user.role] || '/dashboard')
    return
  }

  next()
})

export default router
