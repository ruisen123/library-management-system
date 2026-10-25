import request from '@/utils/request'

/**
 * 出版社模块接口
 */
export function listPublishers() {
  return request.get('/publishers')
}

export function addPublisher(data) {
  return request.post('/publishers', data)
}

export function updatePublisher(id, data) {
  return request.put(`/publishers/${id}`, data)
}

export function deletePublisher(id) {
  return request.delete(`/publishers/${id}`)
}
