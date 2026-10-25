import request from '@/utils/request'

/**
 * 统计模块接口
 */
export function getOverview() {
  return request.get('/stats/overview')
}

export function getTrend() {
  return request.get('/stats/trend')
}

export function getPie() {
  return request.get('/stats/pie')
}

export function getTop() {
  return request.get('/stats/top')
}

/** 导出 Excel 报表：params { startDate?, endDate? }，返回 Blob 响应 */
export function exportReport(params) {
  return request.get('/stats/export', { params, responseType: 'blob', timeout: 60000 })
}
