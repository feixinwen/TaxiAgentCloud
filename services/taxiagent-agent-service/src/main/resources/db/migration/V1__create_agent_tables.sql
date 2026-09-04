CREATE TABLE agent_conversation
(
    id              BIGINT       NOT NULL,
    conversation_id VARCHAR(36)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(200) NULL,
    status          VARCHAR(16)  NOT NULL,
    version         INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_id (conversation_id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_message
(
    id                 BIGINT       NOT NULL,
    conversation_db_id BIGINT       NOT NULL,
    run_db_id          BIGINT       NOT NULL,
    client_message_id  VARCHAR(36)  NULL,
    role               VARCHAR(16)  NOT NULL,
    content            TEXT         NOT NULL,
    sequence_no        BIGINT       NOT NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_message (conversation_db_id, client_message_id),
    UNIQUE KEY uk_message_sequence (conversation_db_id, sequence_no),
    KEY idx_message_conversation_time (conversation_db_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE agent_run
(
    id                   BIGINT       NOT NULL,
    run_id               VARCHAR(36)  NOT NULL,
    conversation_db_id   BIGINT       NOT NULL,
    user_message_id      BIGINT       NOT NULL,
    assistant_message_id BIGINT       NULL,
    agent_type           VARCHAR(16)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    model_name           VARCHAR(100) NOT NULL,
    prompt_version       VARCHAR(50)  NOT NULL,
    error_code           VARCHAR(64)  NULL,
    started_at           DATETIME(3)  NOT NULL,
    completed_at         DATETIME(3)  NULL,
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_id (run_id),
    UNIQUE KEY uk_run_user_message (user_message_id),
    KEY idx_run_conversation_time (conversation_db_id, started_at),
    KEY idx_run_status_time (status, started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
