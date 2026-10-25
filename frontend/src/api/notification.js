import request from '@/utils/request'

/**
 * 站内通知接口（读者本人语义，后端只认令牌不认 userId）
 */
export function listNotifications(params) {
  return request.get('/notifications', { params })
}

export function getUnreadCount() {
  return request.get('/notifications/unread-count')
}

export function markNotificationRead(id) {
  return request.put(`/notifications/${id}/read`)
}

export function markAllNotificationsRead() {
  return request.put('/notifications/read-all')
}

/**
 * 管理员手动补扫通知（仅 ADMIN；后端 NotificationServiceImpl.scan()，按 dedup_key 幂等补齐）。
 * 2026-10-24 补齐：此前它是全部端点中唯一没有任何前端调用的一个（当时 58 个）。
 */
export function scanNotifications() {
  return request.post('/notifications/scan')
}
