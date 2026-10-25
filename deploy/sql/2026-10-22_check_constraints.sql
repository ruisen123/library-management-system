-- ============================================================
-- LibMS 数据约束补丁（2026-10-22，2026-10-23 改为可重复执行）
-- 适用：已有 library_db（新库直接执行 docs/04_数据库设计.sql 即可，已含这些约束）
-- 作用：把非法状态、负金额、非法角色、负库存挡在数据库层，避免绕过应用写入脏数据。
-- 特性：可重复执行；每个约束先查 information_schema，已存在则跳过。
-- 使用前请备份；执行：
--   mysql -u root -p --default-character-set=utf8mb4 library_db < deploy/sql/2026-10-22_check_constraints.sql
-- ============================================================
SET NAMES utf8mb4;

USE `library_db`;

DROP PROCEDURE IF EXISTS `libms_apply_check_constraints`;
DELIMITER $$
CREATE PROCEDURE `libms_apply_check_constraints`()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
          AND CONSTRAINT_NAME = 'chk_sys_user_role' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `sys_user` ADD CONSTRAINT `chk_sys_user_role`
            CHECK (`role` IN ('ADMIN', 'LIBRARIAN', 'READER'));
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
          AND CONSTRAINT_NAME = 'chk_sys_user_status' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `sys_user` ADD CONSTRAINT `chk_sys_user_status`
            CHECK (`status` IN (0, 1));
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
          AND CONSTRAINT_NAME = 'chk_sys_user_borrowed_nonneg' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `sys_user` ADD CONSTRAINT `chk_sys_user_borrowed_nonneg`
            CHECK (`borrowed_count` >= 0);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'book'
          AND CONSTRAINT_NAME = 'chk_book_stock' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `book` ADD CONSTRAINT `chk_book_stock`
            CHECK (`total_stock` >= 0 AND `stock` >= 0 AND `stock` <= `total_stock`);
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'book'
          AND CONSTRAINT_NAME = 'chk_book_price_nonneg' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `book` ADD CONSTRAINT `chk_book_price_nonneg`
            CHECK (`price` IS NULL OR `price` >= 0);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'borrow_record'
          AND CONSTRAINT_NAME = 'chk_borrow_status' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `borrow_record` ADD CONSTRAINT `chk_borrow_status`
            CHECK (`status` IN (1, 2, 3));
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'borrow_record'
          AND CONSTRAINT_NAME = 'chk_borrow_due_date' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `borrow_record` ADD CONSTRAINT `chk_borrow_due_date`
            CHECK (`due_date` >= `borrow_date`);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'fine_record'
          AND CONSTRAINT_NAME = 'chk_fine_type' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `fine_record` ADD CONSTRAINT `chk_fine_type`
            CHECK (`type` IN (1, 2, 3));
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'fine_record'
          AND CONSTRAINT_NAME = 'chk_fine_status' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `fine_record` ADD CONSTRAINT `chk_fine_status`
            CHECK (`status` IN (1, 2));
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'fine_record'
          AND CONSTRAINT_NAME = 'chk_fine_amount_nonneg' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `fine_record` ADD CONSTRAINT `chk_fine_amount_nonneg`
            CHECK (`amount` >= 0);
    END IF;

    IF (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'notice'
          AND CONSTRAINT_NAME = 'chk_notice_status' AND CONSTRAINT_TYPE = 'CHECK') = 0 THEN
        ALTER TABLE `notice` ADD CONSTRAINT `chk_notice_status`
            CHECK (`status` IN (0, 1));
    END IF;
END$$
DELIMITER ;

CALL `libms_apply_check_constraints`();
DROP PROCEDURE IF EXISTS `libms_apply_check_constraints`;
