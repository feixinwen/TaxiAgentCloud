CREATE TABLE user_profile
(
    id           BIGINT       NOT NULL COMMENT '跨服务用户唯一标识',
    username     VARCHAR(50)  NOT NULL COMMENT '用户唯一名称',
    display_name VARCHAR(100) NOT NULL COMMENT '用户展示名称',
    role         VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '用户角色',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '账号状态: 0-禁用, 1-启用',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_profile_username (username),
    KEY idx_user_profile_status_deleted (status, is_deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户资料';
