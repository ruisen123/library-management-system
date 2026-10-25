import request from '@/utils/request'

/**
 * 个人中心模块接口（所有已登录角色可访问）
 */
export function getProfile() {
  return request.get('/profile')
}

/** 修改个人资料：body { realName, gender, phone } */
export function updateProfile(data) {
  return request.put('/profile', data)
}

/** 修改密码：body { oldPassword, newPassword } */
export function updatePassword(data) {
  return request.put('/profile/password', data)
}
