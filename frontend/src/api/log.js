import request from '@/utils/request'

/**
 * 操作日志模块接口
 * @param {Object} params { page, size, keyword }
 */
export function listLogs(params) {
  return request.get('/logs', { params })
}
