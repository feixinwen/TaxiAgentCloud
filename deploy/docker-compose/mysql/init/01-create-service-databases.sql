CREATE DATABASE IF NOT EXISTS taxiagent_user
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS taxiagent_auth
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS taxiagent_order
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS taxiagent_ticket
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS taxiagent_rag
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS taxiagent_agent
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

GRANT ALL PRIVILEGES ON taxiagent_user.* TO 'taxiagent'@'%';
GRANT ALL PRIVILEGES ON taxiagent_auth.* TO 'taxiagent'@'%';
GRANT ALL PRIVILEGES ON taxiagent_order.* TO 'taxiagent'@'%';
GRANT ALL PRIVILEGES ON taxiagent_ticket.* TO 'taxiagent'@'%';
GRANT ALL PRIVILEGES ON taxiagent_rag.* TO 'taxiagent'@'%';
GRANT ALL PRIVILEGES ON taxiagent_agent.* TO 'taxiagent'@'%';
