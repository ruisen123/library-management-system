import request from '@/utils/request'

/**
 * 借阅规则模块接口（只读展示）
 */
export function getRuleConfig() {
  return request.get('/rule/config')
}
