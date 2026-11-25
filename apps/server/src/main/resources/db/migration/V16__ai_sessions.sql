CREATE TABLE xl_ai_session (
 id CHAR(36) NOT NULL PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(80) NOT NULL,
 context_json MEDIUMTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 deleted_at DATETIME(3) NULL,
 KEY idx_ai_session_user (user_id,deleted_at,updated_at),
 CONSTRAINT fk_ai_session_user FOREIGN KEY (user_id) REFERENCES xl_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE xl_ai_request (
 id CHAR(36) NOT NULL PRIMARY KEY,
 session_id CHAR(36) NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 question TEXT NOT NULL,
 answer TEXT NULL,
 status VARCHAR(16) NOT NULL,
 sources_json MEDIUMTEXT NOT NULL,
 post_ids_json TEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_ai_request (session_id,request_id),
 CONSTRAINT fk_ai_request_session FOREIGN KEY (session_id) REFERENCES xl_ai_session(id),
 CONSTRAINT ck_ai_request_status CHECK (status IN ('PENDING','DONE','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
