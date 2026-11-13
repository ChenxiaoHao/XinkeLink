-- 初始阶段由缺省open表达；批次仅由管理员显式初始化，迁移不填充业务数据。
CREATE TABLE xl_beta_stage (
    id TINYINT UNSIGNED NOT NULL,
    phase VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'open',
    initialized TINYINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_beta_stage_singleton CHECK (id = 1),
    CONSTRAINT ck_beta_stage_phase CHECK (phase IN ('open', 'beta')),
    CONSTRAINT ck_beta_stage_initialized CHECK (initialized IN (0, 1) AND (phase = 'open' OR initialized = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE xl_beta_code (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT UNSIGNED NULL,
    used_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_beta_code_code (code),
    UNIQUE KEY uk_beta_code_user (user_id),
    CONSTRAINT fk_beta_code_user FOREIGN KEY (user_id) REFERENCES xl_user(id) ON DELETE RESTRICT,
    CONSTRAINT ck_beta_code_binding CHECK ((user_id IS NULL AND used_at IS NULL) OR (user_id IS NOT NULL AND used_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
