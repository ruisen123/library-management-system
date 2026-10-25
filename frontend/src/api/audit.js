import request from '@/utils/request'

/**
 * 数据一致性诊断（巡检）接口 —— 仅系统管理员可用
 * 说明：规则由后端固定定义，前端只发起"运行巡检"，不传任何 SQL 或表名
 */
export function runAudit() {
  return request.post('/audit/run')
}

/** 巡检规则清单（规则说明，不含 SQL） */
export function getAuditRules() {
  return request.get('/audit/rules')
}
