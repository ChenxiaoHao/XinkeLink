-- 用户意见反馈，仅管理员可查看。
CREATE TABLE xl_feedback (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 content VARCHAR(1000) NOT NULL,
 client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_feedback_request (user_id, client_request_id),
 KEY idx_feedback_created (created_at, id),
 CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES xl_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
