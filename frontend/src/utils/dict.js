/**
 * 前端字典映射：后端返回的是枚举原始值（字符串/整数），
 * 前端通过本模块统一转换为中文展示文案，避免硬编码。
 * 注意：与后端枚举值保持一致（详见 docs/05_设计文档总览.md）
 */

/** 角色 */
export const ROLE_MAP = { ADMIN: '系统管理员', LIBRARIAN: '图书管理员', READER: '读者' }

/** 性别 */
export const GENDER_MAP = { 0: '未知', 1: '男', 2: '女' }

/** 账号状态 */
export const USER_STATUS_MAP = { 0: '冻结', 1: '正常' }

/** 借阅状态 */
export const BORROW_STATUS_MAP = { 1: '在借', 2: '已还', 3: '已赔偿' }

/** 罚金类型 */
export const FINE_TYPE_MAP = { 1: '逾期罚金', 2: '破损赔偿', 3: '遗失赔偿' }

/** 罚金缴纳状态 */
export const FINE_STATUS_MAP = { 1: '未缴', 2: '已缴' }

/** 公告状态 */
export const NOTICE_STATUS_MAP = { 0: '已撤回', 1: '已发布' }

/** 预约状态 */
export const RESERVATION_STATUS_MAP = {
  1: '排队中',
  2: '待取书',
  3: '已完成',
  4: '已取消',
  5: '已过期'
}

/** 站内通知类型 */
export const NOTIFICATION_TYPE_MAP = {
  1: '预约到书',
  2: '到期提醒',
  3: '逾期催还',
  4: '罚金提醒',
  5: '证件到期',
  6: '系统欢迎'
}

/** 通知类型 → Element Plus tag 类型 */
export const NOTIFICATION_TAG_MAP = {
  1: 'success',
  2: 'warning',
  3: 'danger',
  4: 'danger',
  5: 'warning',
  6: 'info'
}

/** 通知类型 → 点击后跳转（书架页签 / 个人中心 / 找书） */
export const NOTIFICATION_LINK_MAP = {
  1: { path: '/my-borrow', query: { tab: 'reservations' } },
  2: { path: '/my-borrow', query: { tab: 'borrowing' } },
  3: { path: '/my-borrow', query: { tab: 'borrowing' } },
  4: { path: '/my-borrow', query: { tab: 'fines' } },
  5: { path: '/profile' },
  6: { path: '/book-search' }
}

/** 角色 → 中文 */
export const roleText = (r) => ROLE_MAP[r] || r || ''

/** 性别 → 中文 */
export const genderText = (g) => GENDER_MAP[g] ?? '未知'

/** 账号状态 → 中文 */
export const userStatusText = (s) => USER_STATUS_MAP[s] ?? ''

/** 借阅状态 → 中文 */
export const borrowStatusText = (s) => BORROW_STATUS_MAP[s] ?? ''

/** 罚金类型 → 中文 */
export const fineTypeText = (t) => FINE_TYPE_MAP[t] ?? ''

/** 罚金状态 → 中文 */
export const fineStatusText = (s) => FINE_STATUS_MAP[s] ?? ''

/** 公告状态 → 中文 */
export const noticeStatusText = (s) => NOTICE_STATUS_MAP[s] ?? ''

/** 预约状态 → 中文 */
export const reservationStatusText = (s) => RESERVATION_STATUS_MAP[s] ?? ''
