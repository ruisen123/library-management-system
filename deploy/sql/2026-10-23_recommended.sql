-- ============================================================
-- LibMS 推荐项扩展迁移（2026-10-23）
-- 适用：已有 library_db（9 表 / 13 CHECK）；新库请直接执行 docs/04_数据库设计.sql
-- 内容：sys_user 额度/邮箱 5 列 + notification 第 10 表 + 6 个 CHECK
-- 特性：可重复执行；每一步先查 information_schema，已存在则跳过
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < deploy/sql/2026-10-23_recommended.sql
-- 执行前务必备份：mysqldump -uroot -p --databases library_db > library_db_before_recommended.sql
-- ============================================================

USE `library_db`;

DROP PROCEDURE IF EXISTS `libms_apply_recommended`;
DELIMITER $$
CREATE PROCEDURE `libms_apply_recommended`()
BEGIN
    -- ---------- 1. sys_user：读者额度 + 邮箱偏好 ----------
    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'borrow_limit') = 0 THEN
        ALTER TABLE `sys_user`
            ADD COLUMN `borrow_limit` INT NOT NULL DEFAULT 5
                COMMENT '可借上限（默认 5，按读者覆盖；0 表示不可借）' AFTER `borrowed_count`;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'max_unpaid') = 0 THEN
        ALTER TABLE `sys_user`
            ADD COLUMN `max_unpaid` DECIMAL(8,2) NOT NULL DEFAULT 0.00
                COMMENT '允许的最大未缴罚金（0 表示有欠费即禁借）' AFTER `borrow_limit`;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'valid_until') = 0 THEN
        ALTER TABLE `sys_user`
            ADD COLUMN `valid_until` DATE NULL
                COMMENT '借阅证有效期（NULL 表示长期有效）' AFTER `max_unpaid`;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'email') = 0 THEN
        ALTER TABLE `sys_user`
            ADD COLUMN `email` VARCHAR(100) NULL
                COMMENT '通知邮箱（可空，不用于登录）' AFTER `valid_until`;
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'email_notify') = 0 THEN
        ALTER TABLE `sys_user`
            ADD COLUMN `email_notify` TINYINT NOT NULL DEFAULT 1
                COMMENT '接收邮件通知：1是 0否' AFTER `email`;
    END IF;

    -- ---------- 2. notification 第 10 表 ----------
    IF (SELECT COUNT(*) FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification') = 0 THEN
        CREATE TABLE `notification` (
          `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
          `user_id`           BIGINT UNSIGNED NOT NULL             COMMENT '接收读者外键 → sys_user.id',
          `type`              TINYINT       NOT NULL               COMMENT '类型：1预约到书 2临近到期 3逾期 4未缴罚金 5借阅证将到期 6欢迎',
          `title`             VARCHAR(120)  NOT NULL               COMMENT '标题',
          `content`           VARCHAR(500)  NOT NULL               COMMENT '正文（创建时快照）',
          `biz_type`          VARCHAR(30)   NULL                   COMMENT '业务类型：RESERVATION/BORROW/FINE/USER/SYSTEM',
          `biz_id`            BIGINT UNSIGNED NULL                 COMMENT '业务主键（用于前端跳转）',
          `dedup_key`         VARCHAR(160)  NOT NULL               COMMENT '幂等去重键（业务事件唯一标识）',
          `read_flag`         TINYINT       NOT NULL DEFAULT 0     COMMENT '已读：0未读 1已读',
          `read_time`         DATETIME      NULL                   COMMENT '已读时间',
          `email_status`      TINYINT       NOT NULL DEFAULT 0     COMMENT '邮件：0待发 1已发 2重试 3跳过 4失败 5发送中',
          `email_attempts`    INT           NOT NULL DEFAULT 0     COMMENT '邮件尝试次数',
          `next_attempt_time` DATETIME      NULL                   COMMENT '下次邮件尝试时间',
          `email_sent_time`   DATETIME      NULL                   COMMENT '邮件发送成功时间',
          `email_error`       VARCHAR(500)  NULL                   COMMENT '最近一次邮件错误（截断保存）',
          `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
          `update_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
          PRIMARY KEY (`id`),
          UNIQUE KEY `uk_notification_dedup` (`dedup_key`),
          KEY `idx_notification_user_read_time` (`user_id`, `read_flag`, `create_time`),
          KEY `idx_notification_email` (`email_status`, `next_attempt_time`),
          CONSTRAINT `fk_notification_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
        ) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '站内通知与邮件投递表';
    END IF;

    -- ---------- 3. sys_user CHECK ----------
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
          AND CONSTRAINT_NAME = 'chk_sys_user_borrow_limit') = 0 THEN
        ALTER TABLE `sys_user`
            ADD CONSTRAINT `chk_sys_user_borrow_limit` CHECK (`borrow_limit` BETWEEN 0 AND 50);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
          AND CONSTRAINT_NAME = 'chk_sys_user_max_unpaid') = 0 THEN
        ALTER TABLE `sys_user`
            ADD CONSTRAINT `chk_sys_user_max_unpaid` CHECK (`max_unpaid` >= 0);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
          AND CONSTRAINT_NAME = 'chk_sys_user_email_notify') = 0 THEN
        ALTER TABLE `sys_user`
            ADD CONSTRAINT `chk_sys_user_email_notify` CHECK (`email_notify` IN (0, 1));
    END IF;

    -- ---------- 4. notification CHECK ----------
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
          AND CONSTRAINT_NAME = 'chk_notification_type') = 0 THEN
        ALTER TABLE `notification`
            ADD CONSTRAINT `chk_notification_type` CHECK (`type` BETWEEN 1 AND 6);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
          AND CONSTRAINT_NAME = 'chk_notification_read_flag') = 0 THEN
        ALTER TABLE `notification`
            ADD CONSTRAINT `chk_notification_read_flag` CHECK (`read_flag` IN (0, 1));
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'
          AND CONSTRAINT_NAME = 'chk_notification_email_status') = 0 THEN
        ALTER TABLE `notification`
            ADD CONSTRAINT `chk_notification_email_status` CHECK (`email_status` BETWEEN 0 AND 5);
    END IF;
END$$
DELIMITER ;

CALL `libms_apply_recommended`();
DROP PROCEDURE `libms_apply_recommended`;

-- ---------- 5. 迁移后自检（预期：10 张表 / 19 个 CHECK） ----------
SELECT COUNT(*) AS table_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE();

SELECT COUNT(*) AS check_count
FROM information_schema.TABLE_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'CHECK';

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
  AND COLUMN_NAME IN ('borrow_limit', 'max_unpaid', 'valid_until', 'email', 'email_notify')
ORDER BY ORDINAL_POSITION;
