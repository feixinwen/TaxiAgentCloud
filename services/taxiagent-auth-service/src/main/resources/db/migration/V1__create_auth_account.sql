CREATE TABLE auth_account
(
    user_id            BIGINT       NOT NULL COMMENT 'User Service 用户标识，仅作逻辑关联，不创建跨库外键',
    email              VARCHAR(254) NOT NULL COMMENT '规范化为小写的登录邮箱',
    password_hash      VARCHAR(255) NOT NULL COMMENT '不可逆密码哈希，禁止保存或记录明文密码',
    credential_status  VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '认证凭证状态：ACTIVE、LOCKED、PASSWORD_RESET_REQUIRED',
    token_version      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '全量撤销用户令牌时递增的版本号',
    failed_login_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '连续密码校验失败次数',
    locked_until       DATETIME(3)  NULL COMMENT '凭证锁定截止时间',
    last_login_at      DATETIME(3)  NULL COMMENT '最后一次成功登录时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (user_id),
    KEY idx_auth_account_email_deleted (email, is_deleted),
    KEY idx_auth_account_status_lock (credential_status, locked_until)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '认证账号与安全凭证';
