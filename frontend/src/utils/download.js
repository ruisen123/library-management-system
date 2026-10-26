/**
 * 保存后端返回的 Blob 下载响应（Excel 模板/报表）。
 * 文件名优先取 Content-Disposition 的 RFC 5987 filename*，取不到时用兜底名。
 */
export function saveBlobResponse(response, fallbackName) {
  const blob = response && response.data
  if (!blob || typeof blob.size !== 'number' || blob.size === 0) {
    throw new Error('下载失败：服务器返回空文件')
  }
  if (blob.type && /json|text\/html/i.test(blob.type)) {
    throw new Error('下载失败：服务器返回的不是 Excel 文件')
  }
  let filename = fallbackName
  const headers = (response && response.headers) || {}
  const disposition = headers['content-disposition'] || headers['Content-Disposition'] || ''
  if (disposition) {
    const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
    const plain = /filename="?([^";]+)"?/i.exec(disposition)
    if (star) {
      try {
        filename = decodeURIComponent(star[1])
      } catch (e) {
        if (plain) filename = plain[1]
      }
    } else if (plain) {
      filename = plain[1]
    }
  }
  if (!filename) filename = fallbackName
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 延后释放，避免部分浏览器下载尚未开始就被回收
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
