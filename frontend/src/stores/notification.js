import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUnreadCount } from '@/api/notification'

/**
 * 顶栏未读数状态（仅读者角色轮询，页面隐藏时暂停，避免无意义请求）
 */
export const useNotificationStore = defineStore('notification', () => {
  const unread = ref(0)
  let timer = null

  async function refresh(role) {
    if (role && role !== 'READER') {
      unread.value = 0
      return
    }
    try {
      const res = await getUnreadCount()
      unread.value = Number(res.data || 0)
    } catch (e) {
      // 静默失败：红点消失不影响主流程
    }
  }

  function setUnread(count) {
    unread.value = Number(count || 0)
  }

  function startPolling(role) {
    stopPolling()
    if (role !== 'READER') return
    refresh(role)
    timer = setInterval(() => {
      if (document.visibilityState === 'visible') refresh(role)
    }, 60000)
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function reset() {
    unread.value = 0
    stopPolling()
  }

  return { unread, refresh, setUnread, startPolling, stopPolling, reset }
})
