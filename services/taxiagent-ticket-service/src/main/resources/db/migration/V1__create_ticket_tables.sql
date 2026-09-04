CREATE TABLE `sys_ticket` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ticket_id` VARCHAR(16) NOT NULL COMMENT '工单对外编号(业务唯一标识)',
    `user_id` BIGINT NOT NULL COMMENT '发起人ID',
    `user_type` TINYINT DEFAULT 1 COMMENT '用户类型: 1-乘客, 2-司机',
    `order_id` BIGINT DEFAULT NULL COMMENT '关联订单ID',
    `ticket_type` TINYINT NOT NULL COMMENT '工单类型: 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他',
    `priority` TINYINT DEFAULT 1 COMMENT '优先级: 1-普通, 2-紧急, 3-特急',
    `ticket_status` TINYINT DEFAULT 0 COMMENT '状态: 0-待分配, 1-处理中, 2-待用户确认, 3-已完成, 4-已关闭',
    `handler_id` BIGINT DEFAULT NULL COMMENT '处理客服ID',
    `title` VARCHAR(128) DEFAULT NULL COMMENT '标题',
    `content` VARCHAR(1536) DEFAULT NULL COMMENT '详情描述',
    `process_result` VARCHAR(512) DEFAULT NULL COMMENT '处理结果摘要',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_id` (`ticket_id`),
    KEY `idx_user` (`user_id`, `user_type`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_handler_status` (`handler_id`, `ticket_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单主表';

CREATE TABLE `sys_ticket_chat` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `ticket_id` VARCHAR(16) NOT NULL COMMENT '关联工单编号',
    `sender_id` BIGINT NOT NULL COMMENT '发送者ID (0=系统)',
    `sender_role` TINYINT NOT NULL COMMENT '发送者角色: 0-系统, 1-乘客, 2-司机, 3-客服',
    `content` VARCHAR(1536) DEFAULT NULL COMMENT '消息内容',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_ticket_id` (`ticket_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单沟通记录表';
