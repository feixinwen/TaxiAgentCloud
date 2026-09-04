ALTER TABLE auth_account
    MODIFY COLUMN email VARCHAR(254) NULL COMMENT '登录邮箱，管理员创建账户可为空';
