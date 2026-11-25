CREATE TABLE xl_ai_knowledge (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(200) NOT NULL, source VARCHAR(1000) NOT NULL, document_version VARCHAR(120) NOT NULL,
 published_date VARCHAR(10) NOT NULL DEFAULT '', scope VARCHAR(120) NOT NULL, validity VARCHAR(1000) NOT NULL,
 body MEDIUMTEXT NOT NULL, reviewed BOOLEAN NOT NULL DEFAULT FALSE,
 filename VARCHAR(255) NOT NULL DEFAULT '', original_file MEDIUMBLOB NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'draft', active_version_id VARCHAR(36) NULL,
 created_by BIGINT UNSIGNED NOT NULL, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT ck_ai_knowledge_status CHECK(status IN ('draft','published','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE xl_ai_knowledge_version (
 id VARCHAR(36) NOT NULL PRIMARY KEY, document_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(200) NOT NULL, source VARCHAR(1000) NOT NULL, document_version VARCHAR(120) NOT NULL,
 published_date VARCHAR(10) NOT NULL, scope VARCHAR(120) NOT NULL, validity VARCHAR(1000) NOT NULL,
 body MEDIUMTEXT NOT NULL, filename VARCHAR(255) NOT NULL, original_file MEDIUMBLOB NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_ai_knowledge_version_doc(document_id),
 CONSTRAINT fk_ai_knowledge_version_doc FOREIGN KEY(document_id) REFERENCES xl_ai_knowledge(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE xl_ai_knowledge_chunk (
 id VARCHAR(36) NOT NULL PRIMARY KEY, version_id VARCHAR(36) NOT NULL,
 ordinal INT NOT NULL, body TEXT NOT NULL, section_title VARCHAR(250) NOT NULL,
 page VARCHAR(30) NOT NULL DEFAULT '', embedding JSON NOT NULL,
 UNIQUE KEY uk_ai_knowledge_chunk(version_id,ordinal),
 CONSTRAINT fk_ai_knowledge_chunk_version FOREIGN KEY(version_id) REFERENCES xl_ai_knowledge_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE xl_ai_knowledge_index (
 id TINYINT NOT NULL PRIMARY KEY, collection_id VARCHAR(100) NOT NULL,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT ck_ai_knowledge_index_singleton CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
