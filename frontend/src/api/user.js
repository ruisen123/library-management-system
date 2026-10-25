import request from '@/utils/request'

/**
 * 用户管理模块接口（读者 + 管理员账号）
 * @param {Object} params { page, size, role, keyword, status }
 */
export function listUsers(params) {
  return request.get('/users', { params })
}

export function addUser(data) {
  return request.post('/users', data)
}

export function updateUser(id, data) {
  return request.put(`/users/${id}`, data)
}

/** 启用/冻结切换 */
export function toggleUserStatus(id) {
  return request.put(`/users/${id}/status`)
}

export function resetPassword(id) {
  return request.put(`/users/${id}/password`)
}

// 删除用户为 API 级能力：后端端点存在且有护栏（自删除/在借/欠费三重防护），
// 但管理界面按 docs/03 用例清单以「冻结/启用」替代删除，故本函数当前仅供脚本/接口调用使用，页面未提供入口
export function deleteUser(id) {
  return request.delete(`/users/${id}`)
}
