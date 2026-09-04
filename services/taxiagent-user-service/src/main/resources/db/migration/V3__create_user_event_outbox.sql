CREATE TABLE user_event_outbox (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    payload      VARCHAR(1000) NULL,
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待发布, 1-已发布',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at DATETIME(3)  NULL,
    UNIQUE KEY uk_event_id (event_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '领域事件待发布表';
