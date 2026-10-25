import request from '@/utils/request'

/**
 * 罚金模块接口
 * @param {Object} params { page, size, status, userId }
 */
export function listFines(params) {
  return request.get('/fines', { params })
}

/** 线下收取后标记已缴 */
export function payFine(id) {
  return request.put(`/fines/${id}/pay`)
}

/** 破损/遗失赔偿登记：body { recordId, amount, type(2破损/3遗失), remark } */
export function compensateFine(data) {
  return request.post('/fines/compensate', data)
}
