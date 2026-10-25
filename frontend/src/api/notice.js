import request from '@/utils/request'

/**
 * 公告模块接口
 * @param {Object} params { page, size, onlyPublished }
 */
export function listNotices(params) {
  return request.get('/notices', { params })
}

export function addNotice(data) {
  return request.post('/notices', data)
}

export function updateNotice(id, data) {
  return request.put(`/notices/${id}`, data)
}

export function deleteNotice(id) {
  return request.delete(`/notices/${id}`)
}
