import request from '@/utils/request'

/**
 * 图书模块接口
 * @param {Object} params { page, size, keyword, categoryId, onlyStock, sort }
 *   sort 仅接受后端白名单值（当前为 popular = 按累计借阅倒序），传其他值后端不生效
 */
export function listBooks(params) {
  return request.get('/books', { params })
}

export function getBook(id) {
  return request.get(`/books/${id}`)
}

export function addBook(data) {
  return request.post('/books', data)
}

export function updateBook(id, data) {
  return request.put(`/books/${id}`, data)
}

export function deleteBook(id) {
  return request.delete(`/books/${id}`)
}

/** 下载图书批量导入模板（xlsx 二进制） */
export function downloadImportTemplate() {
  return request.get('/books/import-template', { responseType: 'blob' })
}

/** 上传图书封面图片（FormData，字段名 file）：返回 /upload/xxx.jpg 形式的可访问路径，
 *  调用方约定与 importBooks 一致——不要手动设 Content-Type，交给浏览器带 boundary。 */
export function uploadBookCover(formData) {
  return request.post('/books/cover', formData, { timeout: 60000 })
}

/** Excel 批量导入（FormData，字段名 file） */
export function importBooks(formData) {
  return request.post('/books/import', formData, { timeout: 60000 })
}
