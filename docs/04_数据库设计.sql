-- ============================================================
-- 图书借阅管理系统（LibMS）数据库建表脚本
-- 数据库：MySQL 8.0　引擎：InnoDB　字符集：utf8mb4
-- 编制日期：2026-09-09　配套文档：docs/04_数据库设计.md
-- 执行顺序：本脚本自上而下整体执行即可（含建表/测试数据/索引/核对）
--
-- ⚠️ 本脚本是「初始化/重置」脚本：会 DROP 并重建 library_db 的全部 10 张表，原有业务数据会被清空。
--    已有库升级请勿执行本脚本，请按顺序执行 deploy/sql/ 下的增量迁移。
-- ============================================================

SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION';

-- 显式声明连接字符集：MySQL 官方镜像的 docker-entrypoint 执行初始化脚本时不带
-- --default-character-set 参数，默认按 latin1 解析会让中文种子数据变成乱码并中断脚本
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `library_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `library_db`;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS `operation_log`;
DROP TABLE IF EXISTS `notice`;
DROP TABLE IF EXISTS `notification`;
DROP TABLE IF EXISTS `reservation`;
DROP TABLE IF EXISTS `fine_record`;
DROP TABLE IF EXISTS `borrow_record`;
DROP TABLE IF EXISTS `book`;
DROP TABLE IF EXISTS `publisher`;
DROP TABLE IF EXISTS `category`;
DROP TABLE IF EXISTS `sys_user`;
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 第一部分：建表语句（按依赖顺序：先父表后子表）
-- ============================================================

-- ------------------------------------------------------------
-- 1. sys_user 用户表（读者与管理员统一账号表）
-- ------------------------------------------------------------
CREATE TABLE `sys_user` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`       VARCHAR(30)  NOT NULL                COMMENT '登录名（唯一）',
  `password`       VARCHAR(100) NOT NULL                COMMENT '密码（BCrypt 加密存储，禁止明文）',
  `real_name`      VARCHAR(30)  NOT NULL                COMMENT '真实姓名',
  `role`           VARCHAR(20)  NOT NULL DEFAULT 'READER' COMMENT '角色：ADMIN 系统管理员 / LIBRARIAN 图书管理员 / READER 读者',
  `reader_no`      VARCHAR(30)  NULL                    COMMENT '读者证号（仅读者角色填写，唯一；管理员为 NULL）',
  `gender`         TINYINT      NOT NULL DEFAULT 0      COMMENT '性别：0未知 1男 2女',
  `phone`          VARCHAR(20)  NULL                    COMMENT '联系电话',
  `status`         TINYINT      NOT NULL DEFAULT 1      COMMENT '账号状态：1正常 0冻结',
  `borrowed_count` INT          NOT NULL DEFAULT 0      COMMENT '当前在借册数（冗余字段，借书上限校验用）',
  `borrow_limit`   INT          NOT NULL DEFAULT 5      COMMENT '可借上限（默认 5，按读者覆盖；0 表示不可借）',
  `max_unpaid`     DECIMAL(8,2) NOT NULL DEFAULT 0.00   COMMENT '允许的最大未缴罚金（0 表示有欠费即禁借）',
  `valid_until`    DATE         NULL                    COMMENT '借阅证有效期（NULL 表示长期有效）',
  `email`          VARCHAR(100) NULL                    COMMENT '通知邮箱（可空，不用于登录）',
  `email_notify`   TINYINT      NOT NULL DEFAULT 1      COMMENT '接收邮件通知：1是 0否',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`        TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_username`  (`username`),
  KEY `idx_reader_no` (`reader_no`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户表（读者与管理员统一账号）';

-- ------------------------------------------------------------
-- 2. category 图书分类表
-- ------------------------------------------------------------
CREATE TABLE `category` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(50) NOT NULL                COMMENT '分类名称（唯一）',
  `sort`        INT         NOT NULL DEFAULT 0      COMMENT '显示排序号（越小越靠前）',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_category_name` (`name`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '图书分类表';

-- ------------------------------------------------------------
-- 3. publisher 出版社表
-- ------------------------------------------------------------
CREATE TABLE `publisher` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(100) NOT NULL               COMMENT '出版社名称（唯一）',
  `contact`     VARCHAR(50)  NULL                   COMMENT '联系方式',
  `address`     VARCHAR(200) NULL                   COMMENT '出版社地址',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT     NOT NULL DEFAULT 0     COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `idx_publisher_name` (`name`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '出版社表';

-- ------------------------------------------------------------
-- 4. book 图书表（依赖 category、publisher）
-- ------------------------------------------------------------
CREATE TABLE `book` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `isbn`         VARCHAR(20)   NOT NULL               COMMENT 'ISBN 书号（同书多册合并为一条记录）',
  `title`        VARCHAR(100)  NOT NULL               COMMENT '书名',
  `author`       VARCHAR(100)  NOT NULL               COMMENT '作者（多人以逗号分隔）',
  `category_id`  BIGINT UNSIGNED NOT NULL             COMMENT '分类外键 → category.id',
  `publisher_id` BIGINT UNSIGNED NOT NULL             COMMENT '出版社外键 → publisher.id',
  `publish_date` DATE          NULL                   COMMENT '出版日期',
  `price`        DECIMAL(10,2) NULL                   COMMENT '定价（破损/遗失赔偿基准）',
  `total_stock`  INT           NOT NULL DEFAULT 0     COMMENT '馆藏总册数',
  `stock`        INT           NOT NULL DEFAULT 0     COMMENT '当前可借册数（借出减1，归还加1）',
  `borrow_count` INT           NOT NULL DEFAULT 0     COMMENT '累计借阅次数（借出时加1，热度排行用）',
  `location`     VARCHAR(50)   NULL                   COMMENT '馆藏位置（书架号）',
  `cover`        VARCHAR(255)  NULL                   COMMENT '封面图片地址（扩展功能预留）',
  `description`  TEXT          NULL                   COMMENT '内容简介',
  `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT       NOT NULL DEFAULT 0     COMMENT '逻辑删除：0未删 1已删（在借中的图书禁止删除）',
  PRIMARY KEY (`id`),
  KEY `fk_book_category`  (`category_id`),
  KEY `fk_book_publisher` (`publisher_id`),
  CONSTRAINT `fk_book_category`  FOREIGN KEY (`category_id`)  REFERENCES `category` (`id`)  ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_book_publisher` FOREIGN KEY (`publisher_id`) REFERENCES `publisher` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '图书表';

-- ------------------------------------------------------------
-- 5. borrow_record 借阅记录表（依赖 sys_user、book）
-- ------------------------------------------------------------
CREATE TABLE `borrow_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL             COMMENT '借阅读者外键 → sys_user.id',
  `book_id`     BIGINT UNSIGNED NOT NULL             COMMENT '图书外键 → book.id',
  `borrow_date` DATE          NOT NULL               COMMENT '借出日期',
  `due_date`    DATE          NOT NULL               COMMENT '应还日期（借出日+30天，续借再+30天）',
  `return_date` DATE          NULL                  COMMENT '实际归还日期（NULL 表示未归还）',
  `status`      TINYINT       NOT NULL DEFAULT 1     COMMENT '状态：1在借 2已还 3已赔偿',
  `renew_count` TINYINT       NOT NULL DEFAULT 0     COMMENT '已续借次数（0或1，每册限续借一次）',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 复合索引在表内声明：外键可复用以其首列为最左前缀的现有索引，避免 MySQL 为外键再自动建一个
  -- 单列索引（否则 user_id 上会出现 fk_borrow_user 与 idx_user_status 两份等价索引，形成写放大）
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_due_status`  (`due_date`, `status`),
  CONSTRAINT `fk_borrow_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_borrow_book` FOREIGN KEY (`book_id`) REFERENCES `book` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '借阅记录表';

-- ------------------------------------------------------------
-- 6. fine_record 罚金记录表（依赖 sys_user、borrow_record）
-- ------------------------------------------------------------
CREATE TABLE `fine_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NOT NULL             COMMENT '应缴读者外键 → sys_user.id',
  `borrow_id`   BIGINT UNSIGNED NOT NULL             COMMENT '关联借阅记录外键 → borrow_record.id',
  `amount`      DECIMAL(10,2) NOT NULL               COMMENT '应缴金额（元）',
  `type`        TINYINT       NOT NULL DEFAULT 1     COMMENT '类型：1逾期罚金 2破损赔偿 3遗失赔偿',
  `status`      TINYINT       NOT NULL DEFAULT 1     COMMENT '缴纳状态：1未缴 2已缴',
  `paid_time`   DATETIME      NULL                   COMMENT '实际缴纳时间',
  `remark`      VARCHAR(200)  NULL                   COMMENT '备注（赔偿原因、罚金天数说明等）',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 同上：外键复用复合索引的首列，避免重复单列索引
  KEY `idx_fine_user_status` (`user_id`, `status`),
  KEY `fk_fine_borrow`       (`borrow_id`),
  CONSTRAINT `fk_fine_user`   FOREIGN KEY (`user_id`)   REFERENCES `sys_user` (`id`)         ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_fine_borrow` FOREIGN KEY (`borrow_id`) REFERENCES `borrow_record` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '罚金记录表';

-- ------------------------------------------------------------
-- 7. notice 公告表（依赖 sys_user）
-- ------------------------------------------------------------
CREATE TABLE `notice` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title`        VARCHAR(100) NOT NULL               COMMENT '公告标题',
  `content`      TEXT         NOT NULL               COMMENT '公告正文',
  `publisher_id` BIGINT UNSIGNED NOT NULL            COMMENT '发布管理员外键 → sys_user.id',
  `status`       TINYINT      NOT NULL DEFAULT 1     COMMENT '状态：1已发布 0已撤回',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT     NOT NULL DEFAULT 0     COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (`id`),
  KEY `fk_notice_publisher` (`publisher_id`),
  CONSTRAINT `fk_notice_publisher` FOREIGN KEY (`publisher_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统公告表';

-- ------------------------------------------------------------
-- 8. operation_log 操作日志表（依赖 sys_user，允许空操作人）
-- ------------------------------------------------------------
CREATE TABLE `operation_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     BIGINT UNSIGNED NULL                    COMMENT '操作人外键 → sys_user.id（无登录场景为 NULL）',
  `operation`   VARCHAR(50)   NOT NULL                  COMMENT '操作类型（如 LOGIN / BORROW / RETURN / RENEW）',
  `detail`      VARCHAR(500)  NULL                      COMMENT '操作详情（含关键参数与结果）',
  `ip`          VARCHAR(50)   NULL                      COMMENT '操作来源 IP',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间（日志只增不改）',
  PRIMARY KEY (`id`),
  KEY `fk_log_user` (`user_id`),
  CONSTRAINT `fk_log_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '操作日志表';

-- ------------------------------------------------------------
-- 9. reservation 预约排队表（依赖 book、sys_user）
--    状态：1排队中 2待取书 3已完成 4已取消 5已过期
--    说明：预约表为扩展功能，种子数据默认空（避免影响既有借还基线），
--          由读者在有馆藏图书上发起（未被占用副本且无人排队时直接预留，否则排队）、
--          还书时触发队首待取、定时任务回收过期预约。
-- ------------------------------------------------------------
CREATE TABLE `reservation` (
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
  CONSTRAINT `fk_reservation_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '预约排队表';

-- ------------------------------------------------------------
-- 10. notification 站内通知与邮件投递表（2026-10-23 推荐项扩展）
--     一条通知最多一封邮件，表本身兼作邮件 outbox；
--     dedup_key 唯一保证业务后事件与定时扫描重复执行时只插入一次。
-- ------------------------------------------------------------
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

-- ============================================================
-- 第二部分：测试数据（除 reservation / notification 扩展表外，每表至少 3 条，便于开发调试）
-- 说明：测试账号密码统一为 123456，password 字段为 BCrypt 密文（由 Hutool BCrypt.hashpw("123456") 生成，
--      可直接用于登录，无需再生成）。
-- ============================================================

-- 用户：1管理员 + 1图书管理员 + 4读者（覆盖冻结/欠费/逾期测试场景）
INSERT INTO `sys_user` (`id`, `username`, `password`, `real_name`, `role`, `reader_no`, `gender`, `phone`, `status`, `borrowed_count`) VALUES
(1, 'admin',       '$2a$10$.BJ/jhuBGR0rVl.cUj0.HeqKCuzjeEe.iRdB6CAikH3A1fbkUS.iC', '系统管理员', 'ADMIN',     NULL,          0, '13800000001', 1, 0),
(2, 'librarian01', '$2a$10$.BJ/jhuBGR0rVl.cUj0.HeqKCuzjeEe.iRdB6CAikH3A1fbkUS.iC', '王采编',     'LIBRARIAN', NULL,          1, '13800000002', 1, 0),
(3, 'reader001',   '$2a$10$.BJ/jhuBGR0rVl.cUj0.HeqKCuzjeEe.iRdB6CAikH3A1fbkUS.iC', '张三',       'READER',    'R20260001',   1, '13800000003', 1, 2),
(4, 'reader002',   '$2a$10$.BJ/jhuBGR0rVl.cUj0.HeqKCuzjeEe.iRdB6CAikH3A1fbkUS.iC', '李四',       'READER',    'R20260002',   2, '13800000004', 0, 1),
(5, 'reader003',   '$2a$10$.BJ/jhuBGR0rVl.cUj0.HeqKCuzjeEe.iRdB6CAikH3A1fbkUS.iC', '王五',       'READER',    'R20260003',   1, '13800000005', 1, 0),
(6, 'reader004',   '$2a$10$.BJ/jhuBGR0rVl.cUj0.HeqKCuzjeEe.iRdB6CAikH3A1fbkUS.iC', '赵六',       'READER',    'R20260004',   2, '13800000006', 1, 1);

-- 分类：4 个
INSERT INTO `category` (`id`, `name`, `sort`) VALUES
(1, '计算机技术', 1),
(2, '文学',       2),
(3, '历史',       3),
(4, '经济管理',   4);

-- 出版社：4 家
INSERT INTO `publisher` (`id`, `name`, `contact`, `address`) VALUES
(1, '人民邮电出版社',   '010-81055000', '北京市丰台区成寿寺路11号'),
(2, '机械工业出版社',   '010-88379000', '北京市西城区百万庄大街22号'),
(3, '中华书局',         '010-63401767', '北京市丰台区太平桥西里38号'),
(4, '人民文学出版社',   '010-65241325', '北京市朝阳区农展馆南里10号');

-- 图书：6 本（其中 id=3 的 stock=0 用于测试"库存不足"）
-- 库存自洽原则：total_stock = stock + 在借(status=1) + 已赔偿(status=3)
INSERT INTO `book` (`id`, `isbn`, `title`, `author`, `category_id`, `publisher_id`, `publish_date`, `price`, `total_stock`, `stock`, `borrow_count`, `location`, `description`) VALUES
(1, '9787111544937', '深入理解计算机系统（第3版）', 'Randal E. Bryant',   1, 1, '2016-11-01', 139.00, 8, 7, 120, 'A区-3排-01', '计算机系统基础经典教材，从程序员视角理解计算机底层原理。'),
(2, '9787111407010', '算法导论（原书第3版）',       'Thomas H. Cormen',  1, 2, '2013-01-01', 128.00, 3, 3, 80,  'A区-3排-02', '算法领域标准教材，全面阐述算法设计与分析。'),
(3, '9787111641247', '深入理解Java虚拟机（第3版）', '周志明',             1, 2, '2019-12-01', 129.00, 1, 0, 200, 'A区-3排-03', 'Java 虚拟机领域权威著作。'),
(4, '9787020002207', '红楼梦',                     '曹雪芹',             2, 4, '1996-12-01', 59.70,  6, 5, 60,  'B区-1排-01', '中国古典四大名著之一。'),
(5, '9787101052039', '万历十五年',                 '黄仁宇',             3, 3, '2007-01-01', 26.00,  3, 1, 45,  'B区-2排-03', '明史研究经典之作，"大历史观"的代表作。'),
(6, '9787020001392', '平凡的世界（全三册）',       '路遥',               2, 4, '2004-05-01', 64.00,  6, 6, 90,  'B区-1排-02', '茅盾文学奖获奖作品，全景式表现中国当代城乡生活。');

-- 借阅记录：8 条（覆盖在借/已还/已赔偿、逾期、续借过一次等场景）
-- 日期一律用 CURDATE() 相对表达（N18-1）：种子导入当天即为"张三借书 11 天/到期还有 19 天"等
-- 稳定业务图景，避免固定日期随时间漂移（如"未逾期"记录到期后变逾期，导致回归脚本假失败）
INSERT INTO `borrow_record` (`id`, `user_id`, `book_id`, `borrow_date`, `due_date`, `return_date`, `status`, `renew_count`) VALUES
(1, 3, 1, DATE_SUB(CURDATE(), INTERVAL 11 DAY), DATE_ADD(CURDATE(), INTERVAL 19 DAY), NULL,                                   1, 0),  -- 张三在借《深入理解计算机系统》，借期 30 天未逾期
(2, 3, 4, DATE_SUB(CURDATE(), INTERVAL 28 DAY), DATE_ADD(CURDATE(), INTERVAL 2 DAY),  NULL,                                   1, 0),  -- 张三在借《红楼梦》，借期 30 天未逾期
(3, 3, 6, DATE_SUB(CURDATE(), INTERVAL 42 DAY), DATE_SUB(CURDATE(), INTERVAL 12 DAY), DATE_SUB(CURDATE(), INTERVAL 15 DAY), 2, 1),  -- 张三已还《平凡的世界》，续借过 1 次（提前 3 天归还）
(4, 5, 5, DATE_SUB(CURDATE(), INTERVAL 42 DAY), DATE_SUB(CURDATE(), INTERVAL 12 DAY), DATE_SUB(CURDATE(), INTERVAL 7 DAY),  2, 0),  -- 王五归还《万历十五年》逾期 5 天 → 产生未缴罚金
(5, 6, 5, DATE_SUB(CURDATE(), INTERVAL 54 DAY), DATE_SUB(CURDATE(), INTERVAL 24 DAY), NULL,                                   1, 0),  -- 赵六在借《万历十五年》已逾期 → 逾期禁借/禁续借场景
(6, 4, 5, DATE_SUB(CURDATE(), INTERVAL 125 DAY), DATE_SUB(CURDATE(), INTERVAL 95 DAY), DATE_SUB(CURDATE(), INTERVAL 99 DAY), 3, 0),  -- 李四借阅《万历十五年》遗失赔偿结案（馆藏 3 册 = 1 在架 + 1 在借 + 1 已赔偿）
(7, 5, 4, DATE_SUB(CURDATE(), INTERVAL 73 DAY), DATE_SUB(CURDATE(), INTERVAL 43 DAY), DATE_SUB(CURDATE(), INTERVAL 42 DAY), 2, 0),  -- 王五曾逾期 1 天归还《红楼梦》，罚金已缴清
(8, 4, 3, DATE_SUB(CURDATE(), INTERVAL 28 DAY), DATE_ADD(CURDATE(), INTERVAL 2 DAY),  NULL,                                   1, 0);  -- 李四（账号冻结）在借《深入理解Java虚拟机》，冻结账号可还不可借

-- 罚金记录：3 条（未缴逾期罚金 / 已缴逾期罚金 / 已缴遗失赔偿）
INSERT INTO `fine_record` (`id`, `user_id`, `borrow_id`, `amount`, `type`, `status`, `paid_time`, `remark`) VALUES
(1, 5, 4, 2.50,  1, 1, NULL,                                                                                                     '逾期5天 × 0.5元/天'),
(2, 5, 7, 0.50,  1, 2, CONCAT(DATE_SUB(CURDATE(), INTERVAL 41 DAY), ' 10:30:00'),                                                '逾期1天 × 0.5元/天，当场缴清'),
(3, 4, 6, 26.00, 3, 2, CONCAT(DATE_SUB(CURDATE(), INTERVAL 98 DAY), ' 15:20:00'),                                                '图书遗失，按定价赔偿（记录状态改为已赔偿）');

-- 公告：3 条（2 已发布 1 已撤回）
INSERT INTO `notice` (`id`, `title`, `content`, `publisher_id`, `status`) VALUES
(1, '2026年秋季学期开馆时间公告', '本学期图书馆开放时间为每日 8:00—22:00，法定节假日另行通知。', 1, 1),
(2, '计算机类新书到馆通知',       '新到《深入理解计算机系统（第3版）》等计算机类图书 8 种，欢迎借阅。',   2, 1),
(3, '系统升级维护通知（已撤回）', '系统将于本周末进行升级维护。（本公告已过期撤回）',                    1, 0);

-- 操作日志：3 条
INSERT INTO `operation_log` (`id`, `user_id`, `operation`, `detail`, `ip`) VALUES
(1, 2, 'BORROW', CONCAT('为读者[R20260001 张三]办理借书：《深入理解计算机系统（第3版）》，应还日期 ', DATE_ADD(CURDATE(), INTERVAL 19 DAY)), '192.168.1.10'),
(2, 2, 'RETURN', '办理还书：读者[R20260003 王五]归还《万历十五年》，逾期5天，登记罚金2.50元',           '192.168.1.10'),
(3, 1, 'LOGIN',  '系统管理员登录成功', '192.168.1.2');

-- ============================================================
-- 第三部分：索引创建语句（物理外键列已由 MySQL 自动建索引，
--           此处仅补充唯一索引与业务查询索引）
-- ============================================================

-- 图书：ISBN 普通索引（检索高频；"未删除唯一"由下方生成列唯一索引 uk_book_isbn 保证）
CREATE INDEX `idx_book_isbn` ON `book` (`isbn`);

-- 图书：书名前缀索引（书名模糊检索）
CREATE INDEX `idx_book_title` ON `book` (`title`(20));

-- 图书：累计借阅次数索引（借阅热度 TOP10 按 borrow_count 排序，避免全表扫描 + filesort）
CREATE INDEX `idx_book_borrow_count` ON `book` (`borrow_count`);

-- 用户：角色+状态+创建时间复合索引（用户列表按角色/状态筛选、注册读者数统计；
-- 末位列入 create_time 使列表的 ORDER BY create_time DESC 可直接借力索引，避免规模下的 filesort）
CREATE INDEX `idx_user_role_status` ON `sys_user` (`role`, `status`, `create_time`);

-- 用户：删除标记+创建时间复合索引（R19-3：用户列表"无 role 过滤"的默认路径
-- ORDER BY create_time DESC 在规模化数据下走 filesort——本索引以 deleted 等值 + create_time 排序消除之；
-- 带 role 条件的路径继续由 idx_user_role_status 承担）
CREATE INDEX `idx_user_deleted_create_time` ON `sys_user` (`deleted`, `create_time`);

-- 借阅记录：借出日期索引（借阅记录按借出日期范围筛选、今日借书统计、近12个月趋势聚合）
CREATE INDEX `idx_borrow_date` ON `borrow_record` (`borrow_date`);

-- 公告：状态索引（前台仅展示已发布公告）
CREATE INDEX `idx_notice_status` ON `notice` (`status`);

-- 操作日志：时间索引（按时间区间查询审计日志）
CREATE INDEX `idx_log_create_time` ON `operation_log` (`create_time`);

-- 说明：borrow_record.idx_user_status / idx_due_status 与 fine_record.idx_fine_user_status
--       已移入建表语句内声明——外键可复用以其首列为最左前缀的现有索引，
--       从而消除"外键自动索引 + 手工索引"在同列上的重复（原先 borrow_record 上 book_id 有两份等价索引）。
--       idx_borrow_book(book_id) 已删除：由外键自动生成的 fk_borrow_book 承担同等职责。

-- ------------------------------------------------------------
-- 唯一性兜底（生成列 + 唯一索引）：未删除行参与唯一约束，逻辑删除后自动释放、可复用同名
-- 原理：MySQL 8.0 生成列 IF(deleted=0, 业务列, NULL)，NULL 不参与唯一约束，
--       故 deleted=1 的历史行不占用唯一名额，删除后可重建同名；
--       应用层“先查后插”仍保留（友好提示），此约束为并发竞态的原子兜底。
-- ------------------------------------------------------------
ALTER TABLE `sys_user`
  ADD COLUMN `uk_username`  VARCHAR(30) GENERATED ALWAYS AS (IF(`deleted` = 0, `username`, NULL)) STORED,
  ADD COLUMN `uk_reader_no` VARCHAR(30) GENERATED ALWAYS AS (IF(`deleted` = 0, `reader_no`, NULL)) STORED;
CREATE UNIQUE INDEX `uk_sys_user_username`  ON `sys_user` (`uk_username`);
CREATE UNIQUE INDEX `uk_sys_user_reader_no` ON `sys_user` (`uk_reader_no`);

ALTER TABLE `book`
  ADD COLUMN `uk_isbn` VARCHAR(20) GENERATED ALWAYS AS (IF(`deleted` = 0, `isbn`, NULL)) STORED;
CREATE UNIQUE INDEX `uk_book_isbn` ON `book` (`uk_isbn`);

ALTER TABLE `category`
  ADD COLUMN `uk_name` VARCHAR(50) GENERATED ALWAYS AS (IF(`deleted` = 0, `name`, NULL)) STORED;
CREATE UNIQUE INDEX `uk_category_name` ON `category` (`uk_name`);

ALTER TABLE `publisher`
  ADD COLUMN `uk_name` VARCHAR(100) GENERATED ALWAYS AS (IF(`deleted` = 0, `name`, NULL)) STORED;
CREATE UNIQUE INDEX `uk_publisher_name` ON `publisher` (`uk_name`);

-- ============================================================
-- 第四部分：数据核对查询（执行后逐条核对）
-- ============================================================

-- 1. 核对表与数据量（预期：10 张表，行数 6/4/4/6/8/3/3/3/0/0；reservation/notification 为扩展表，种子默认空）
SELECT 'sys_user' AS 表名, COUNT(*) AS 行数 FROM sys_user
UNION ALL SELECT 'category',       COUNT(*) FROM category
UNION ALL SELECT 'publisher',      COUNT(*) FROM publisher
UNION ALL SELECT 'book',           COUNT(*) FROM book
UNION ALL SELECT 'borrow_record',  COUNT(*) FROM borrow_record
UNION ALL SELECT 'fine_record',    COUNT(*) FROM fine_record
UNION ALL SELECT 'notice',         COUNT(*) FROM notice
UNION ALL SELECT 'operation_log',  COUNT(*) FROM operation_log
UNION ALL SELECT 'reservation',    COUNT(*) FROM reservation
UNION ALL SELECT 'notification',   COUNT(*) FROM notification;

-- 2. 核对外键关联：张三（reader001）的借阅明细（预期 3 条，含 2 在借 1 已还）
SELECT u.real_name, b.title, br.borrow_date, br.due_date,
       (CASE br.status WHEN 1 THEN '在借' WHEN 2 THEN '已还' WHEN 3 THEN '已赔偿' END) AS 状态
FROM borrow_record br
JOIN sys_user u ON u.id = br.user_id
JOIN book b     ON b.id = br.book_id
WHERE br.user_id = 3;

-- 3. 核对罚金与借阅关联：未缴罚金读者（预期：王五 reader003 一条 2.50 元）
SELECT u.real_name, f.amount, f.remark
FROM fine_record f JOIN sys_user u ON u.id = f.user_id
WHERE f.status = 1;

-- 4. 核对库存一致性：馆藏总数 = 可借册数 + 在借册数 + 已赔偿册数
SELECT b.title, b.total_stock AS 馆藏, b.stock AS 可借,
       (SELECT COUNT(*) FROM borrow_record br WHERE br.book_id = b.id AND br.status = 1) AS 在借册数,
       (SELECT COUNT(*) FROM borrow_record br WHERE br.book_id = b.id AND br.status = 3) AS 已赔偿册数,
       (b.stock
        + (SELECT COUNT(*) FROM borrow_record br WHERE br.book_id = b.id AND br.status = 1)
        + (SELECT COUNT(*) FROM borrow_record br WHERE br.book_id = b.id AND br.status = 3)) AS 合计
FROM book b;
-- 预期：每行"合计"与"馆藏"相等，说明冗余库存字段与借阅流水完全一致

-- ============================================================
-- 第五部分：数据库级兜底约束（2026-10-22）
-- 应用层已校验，但直接改库/导入脏数据仍可能写入非法状态、负金额或非法角色；
-- 巡检只能事后定位，故在数据库层加 CHECK，保证"写不进去"而不只是"事后能发现"。
-- 已有库升级请执行 deploy/sql/2026-10-22_check_constraints.sql。
-- ============================================================
ALTER TABLE `sys_user`
  ADD CONSTRAINT `chk_sys_user_role` CHECK (`role` IN ('ADMIN', 'LIBRARIAN', 'READER')),
  ADD CONSTRAINT `chk_sys_user_status` CHECK (`status` IN (0, 1)),
  ADD CONSTRAINT `chk_sys_user_borrowed_nonneg` CHECK (`borrowed_count` >= 0),
  ADD CONSTRAINT `chk_sys_user_borrow_limit` CHECK (`borrow_limit` BETWEEN 0 AND 50),
  ADD CONSTRAINT `chk_sys_user_max_unpaid` CHECK (`max_unpaid` >= 0),
  ADD CONSTRAINT `chk_sys_user_email_notify` CHECK (`email_notify` IN (0, 1));

ALTER TABLE `book`
  ADD CONSTRAINT `chk_book_stock` CHECK (`total_stock` >= 0 AND `stock` >= 0 AND `stock` <= `total_stock`),
  ADD CONSTRAINT `chk_book_price_nonneg` CHECK (`price` IS NULL OR `price` >= 0);

ALTER TABLE `borrow_record`
  ADD CONSTRAINT `chk_borrow_status` CHECK (`status` IN (1, 2, 3)),
  ADD CONSTRAINT `chk_borrow_due_date` CHECK (`due_date` >= `borrow_date`);

ALTER TABLE `fine_record`
  ADD CONSTRAINT `chk_fine_type` CHECK (`type` IN (1, 2, 3)),
  ADD CONSTRAINT `chk_fine_status` CHECK (`status` IN (1, 2)),
  ADD CONSTRAINT `chk_fine_amount_nonneg` CHECK (`amount` >= 0);

ALTER TABLE `notice`
  ADD CONSTRAINT `chk_notice_status` CHECK (`status` IN (0, 1));

ALTER TABLE `reservation`
  ADD CONSTRAINT `chk_reservation_status` CHECK (`status` IN (1, 2, 3, 4, 5)),
  ADD CONSTRAINT `chk_reservation_queue_no` CHECK (`queue_no` > 0);

ALTER TABLE `notification`
  ADD CONSTRAINT `chk_notification_type` CHECK (`type` BETWEEN 1 AND 6),
  ADD CONSTRAINT `chk_notification_read_flag` CHECK (`read_flag` IN (0, 1)),
  ADD CONSTRAINT `chk_notification_email_status` CHECK (`email_status` BETWEEN 0 AND 5);
