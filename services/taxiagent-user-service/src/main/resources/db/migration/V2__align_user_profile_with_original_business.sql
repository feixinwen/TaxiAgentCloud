ALTER TABLE user_profile
    DROP INDEX uk_user_profile_username,
    DROP COLUMN display_name,
    ADD COLUMN active_username VARCHAR(50)
        GENERATED ALWAYS AS (IF(is_deleted = 0, username, NULL)) STORED
        COMMENT '仅为未删除用户生成的唯一性辅助字段',
    ADD UNIQUE KEY uk_user_profile_active_username_role (active_username, role);
