ALTER TABLE auth_account
    ADD COLUMN active_email VARCHAR(254)
        GENERATED ALWAYS AS (IF(is_deleted = 0, email, NULL)) STORED
        COMMENT '仅为未删除账号生成的邮箱唯一性辅助字段',
    ADD UNIQUE KEY uk_auth_account_active_email (active_email);
