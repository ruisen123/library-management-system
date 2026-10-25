import request from '@/utils/request'

/**
 * 借阅模块接口（借书 / 还书 / 续借 / 记录查询）
 * @param {Object} params { page, size, keyword, status, userId, startDate, endDate }
 */
export function listBorrowRecords(params) {
  return request.get('/borrow-records', { params })
}

/** 借书：body { userId, bookId } */
export function borrowBook(data) {
  return request.post('/borrow', data)
}

/** 还书：body { recordId } */
export function returnBook(data) {
  return request.post('/return', data)
}

/** 续借：body { recordId } */
export function renewBook(data) {
  return request.post('/renew', data)
}

/** 借书办理-检索读者（LIBRARIAN）：按证号/姓名模糊查询读者列表 */
export function searchReaders(keyword) {
  return request.get('/readers', { params: { keyword } })
}

/** 我的借阅（READER 本人记录，后端按 Token 强制过滤）：params { status } 可选 */
export function listMyBorrows(params) {
  return request.get('/my-borrows', { params })
}
