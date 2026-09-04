CREATE TABLE user_poi (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL COMMENT '所属用户ID',
    poi_tag      VARCHAR(64)  NOT NULL COMMENT '标签：家/公司/其他',
    poi_name     VARCHAR(255) NOT NULL COMMENT '地点名称',
    poi_address  VARCHAR(500) NULL COMMENT '详细地址',
    longitude    DECIMAL(10, 7) NOT NULL COMMENT '经度',
    latitude     DECIMAL(10, 7) NOT NULL COMMENT '纬度',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除, 1-已删除',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户常用地点';
