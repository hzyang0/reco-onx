CREATE TABLE agent_conversations (
    message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    role_name VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_agent_conversation_user FOREIGN KEY (user_id) REFERENCES user_profiles(user_id),
    INDEX idx_agent_conversation_session_time (session_id, created_at DESC),
    INDEX idx_agent_conversation_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_long_term_memories (
    user_id BIGINT NOT NULL,
    memory_key VARCHAR(64) NOT NULL,
    memory_value VARCHAR(255) NOT NULL,
    confidence DOUBLE NOT NULL DEFAULT 1.0,
    source_name VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    PRIMARY KEY (user_id, memory_key),
    CONSTRAINT fk_agent_memory_user FOREIGN KEY (user_id) REFERENCES user_profiles(user_id),
    INDEX idx_agent_memory_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
