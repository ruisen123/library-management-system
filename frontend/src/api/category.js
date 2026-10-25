import request from '@/utils/request'

/**
 * 图书分类模块接口
 */
export function listCategories() {
  return request.get('/categories')
}

export function addCategory(data) {
  return request.post('/categories', data)
}

export function updateCategory(id, data) {
  return request.put(`/categories/${id}`, data)
}

export function deleteCategory(id) {
  return request.delete(`/categories/${id}`)
}
