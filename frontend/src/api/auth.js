import request from '@/utils/request'

/**
 * 认证模块接口
 */
export function login(data) {
  return request.post('/auth/login', data)
}

/** 读者自助注册：成功后直接返回 token 与用户信息（注册即登录） */
export function register(data) {
  return request.post('/auth/register', data)
}
