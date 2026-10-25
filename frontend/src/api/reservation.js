import request from '@/utils/request'

/** 预约图书（读者本人）：body { bookId } */
export function reserveBook(data) {
  return request.post('/reservations', data)
}

/** 取消预约（读者仅限本人，馆员/管理员可取消） */
export function cancelReservation(id) {
  return request.delete(`/reservations/${id}`)
}

/** 我的预约：params { page, size, status } */
export function listMyReservations(params) {
  return request.get('/reservations/my', { params })
}

/** 预约队列（馆员/管理员）：params { page, size, keyword, status, bookId } */
export function listReservations(params) {
  return request.get('/reservations', { params })
}

/** 手动回收过期预约（管理员） */
export function expireReservations() {
  return request.post('/reservations/expire')
}
