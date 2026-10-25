-- ============================================================
-- 已有库升级：预约排队表（2026-10-22）
-- 新库直接执行 docs/04_数据库设计.sql 即可，无需本脚本。
-- 本脚本幂等：表已存在时不会重复创建。
-- ============================================================
USE `library_db`;

CREATE TABLE IF NOT EXISTS `reservation` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `book_id`     BIGINT UNSIGNED NOT NULL             COMMENT '预约图书外键 → book.id',
  `user_id`     BIGINT UNSIGNED NOT NULL             COMMENT '预约读者外键 → sys_user.id',
  `status`      TINYINT       NOT NULL DEFAULT 1     COMMENT '状态：1排队中 2待取书 3已完成 4已取消 5已过期',
  `queue_no`    INT           NOT NULL               COMMENT '该书预约序号（申请时递增分配，用于排队顺序）',
  `ready_time`  DATETIME      NULL                   COMMENT '到书时间（队首待取时写入）',
  `expire_time` DATETIME      NULL                   COMMENT '待取截止时间（到书后 7 天内有效）',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预约时间',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_book_queue` (`book_id`, `queue_no`),
  KEY `idx_reservation_book_status` (`book_id`, `status`, `queue_no`),
  KEY `idx_reservation_user_status` (`user_id`, `status`),
  KEY `idx_reservation_expire` (`status`, `expire_time`),
  CONSTRAINT `fk_reservation_book` FOREIGN KEY (`book_id`) REFERENCES `book` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_reservation_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `chk_reservation_status` CHECK (`status` IN (1, 2, 3, 4, 5)),
  CONSTRAINT `chk_reservation_queue_no` CHECK (`queue_no` > 0)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '预约排队表';
