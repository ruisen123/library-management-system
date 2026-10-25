package com.library.event;

/**
 * 站内通知事件（业务事务提交后由监听器落库，失败由定时扫描按 dedup_key 补齐）
 *
 * @param dedupKey 幂等键，同一业务事件重复投递最多落一条
 */
public record NotificationEvent(Long userId, Integer type, String title, String content,
                                String bizType, Long bizId, String dedupKey) {
}
