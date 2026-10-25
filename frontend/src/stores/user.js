import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, register as registerApi } from '@/api/auth'
import { listFines } from '@/api/fine'
import { listMyReservations } from '@/api/reservation'
import { roleText } from '@/utils/dict'

/**
 * 用户状态管理（Pinia，Composition API 写法）
 * 登录信息持久化到 localStorage，刷新页面不丢失
 */
export const useUserStore = defineStore('user', () => {
  // —— state ——
  const token = ref(localStorage.getItem('token') || '')
  // localStorage 数据损坏时兜底，避免 JSON.parse 抛异常导致整页白屏
  function readStoredUser() {
    try {
      return JSON.parse(localStorage.getItem('user') || '{}')
    } catch (e) {
      localStorage.removeItem('user')
      return {}
    }
  }
  const userInfo = ref(readStoredUser())

  // —— 读者未缴罚金笔数：侧边菜单红字提示用 ——
  // 只在读者角色下拉取；每次只取 1 条（size=1），拿 total 即可，开销极小
  const unpaidFineCount = ref(0)
  async function refreshUnpaidFines() {
    if (!token.value || userInfo.value.role !== 'READER') {
      unpaidFineCount.value = 0
      return
    }
    try {
      const res = await listFines({ page: 1, size: 1, status: 1 })
      unpaidFineCount.value = Number((res.data && res.data.total) || 0)
    } catch (e) {
      // 静默失败：提示消失不影响主流程
      unpaidFineCount.value = 0
    }
  }

  // —— 读者待取预约数：到书后即登录即见（站内通知的最小实现）——
  const reservationReadyCount = ref(0)
  async function refreshReservationReady() {
    if (!token.value || userInfo.value.role !== 'READER') {
      reservationReadyCount.value = 0
      return
    }
    try {
      const res = await listMyReservations({ page: 1, size: 1, status: 2 })
      reservationReadyCount.value = Number((res.data && res.data.total) || 0)
    } catch (e) {
      reservationReadyCount.value = 0
    }
  }

  // —— getters ——
  const role = computed(() => userInfo.value.role || '')
  const roleLabel = computed(() => roleText(userInfo.value.role))
  const isLoggedIn = computed(() => !!token.value)

  // —— actions ——
  /** 登录/注册成功后的统一落库 */
  function applyAuth(data) {
    token.value = data.token
    userInfo.value = data.user
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(data.user))
    return data.user
  }

  /** 登录：调用接口，成功后写入 token 与用户信息 */
  async function login(form) {
    const res = await loginApi(form)
    return applyAuth(res.data)
  }

  /** 读者自助注册：后端返回与登录相同的令牌结构，注册即登录 */
  async function register(form) {
    const res = await registerApi(form)
    return applyAuth(res.data)
  }

  /** 退出登录：清空本地与内存状态 */
  function logout() {
    token.value = ''
    userInfo.value = {}
    unpaidFineCount.value = 0
    reservationReadyCount.value = 0
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  /** 更新当前用户信息（个人中心修改资料后同步导航栏展示） */
  function updateInfo(partial) {
    userInfo.value = { ...userInfo.value, ...partial }
    localStorage.setItem('user', JSON.stringify(userInfo.value))
  }

  return {
    token,
    userInfo,
    role,
    roleLabel,
    isLoggedIn,
    unpaidFineCount,
    refreshUnpaidFines,
    reservationReadyCount,
    refreshReservationReady,
    login,
    register,
    logout,
    updateInfo
  }
})
