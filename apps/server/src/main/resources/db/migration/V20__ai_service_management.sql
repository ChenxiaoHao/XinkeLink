CREATE TABLE xl_ai_runtime (
    id TINYINT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    model_settings JSON NOT NULL,
    CONSTRAINT ck_ai_runtime_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO xl_ai_runtime (id, model_settings) VALUES (1, JSON_OBJECT());

CREATE TABLE xl_ai_usage (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    usage_day DATE NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    action VARCHAR(16) NOT NULL,
    stage VARCHAR(12) NULL,
    model VARCHAR(120) NULL,
    success BOOLEAN NOT NULL,
    tokens BIGINT UNSIGNED NULL,
    elapsed_ms INT UNSIGNED NULL,
    KEY idx_ai_usage_day (usage_day, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE xl_ai_reindexed_vector (
    chunk_id VARCHAR(36) NOT NULL PRIMARY KEY,
    embedding JSON NOT NULL,
    CONSTRAINT fk_ai_reindexed_chunk FOREIGN KEY (chunk_id) REFERENCES xl_ai_knowledge_chunk(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
