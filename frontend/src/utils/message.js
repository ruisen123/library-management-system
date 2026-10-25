import { ElMessage } from 'element-plus'

/**
 * 全局消息提示统一封装
 * 统一收敛 Element Plus 消息 API，避免页面间样式/参数不一致
 */
export const msg = {
  success(text) {
    ElMessage({ message: text, type: 'success', duration: 3000 })
  },
  error(text) {
    ElMessage({ message: text, type: 'error', duration: 3000 })
  },
  warning(text) {
    ElMessage({ message: text, type: 'warning', duration: 3000 })
  },
  info(text) {
    ElMessage({ message: text, type: 'info', duration: 3000 })
  }
}
