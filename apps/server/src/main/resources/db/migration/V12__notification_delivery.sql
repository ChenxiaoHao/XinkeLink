-- Delivery metadata is independent of user/content FK lifetimes. Never store content or identity projections here.
CREATE TABLE xl_notification_stream (
    natural_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL DEFAULT 0,
    applied_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (natural_key),
    CONSTRAINT ck_notice_stream_sequence CHECK (applied_sequence <= sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Natural interaction sequence and lifetime cancellation tombstone';

CREATE TABLE xl_notification_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    natural_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    payload VARCHAR(4096) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    last_result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    broker_message_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_notice_outbox_sequence (natural_key,sequence_no),
    KEY idx_notice_outbox_due (state,next_attempt_at,event_id),
    KEY idx_notice_outbox_lease (state,lease_until,event_id),
    KEY idx_notice_outbox_age (state,created_at),
    KEY idx_notice_outbox_post (post_id),
    CONSTRAINT fk_notice_outbox_stream FOREIGN KEY (natural_key) REFERENCES xl_notification_stream(natural_key),
    CONSTRAINT ck_notice_outbox_state CHECK (state IN ('READY','LEASED','SENT','PAUSED')),
    CONSTRAINT ck_notice_outbox_sequence CHECK (sequence_no > 0 AND post_id > 0),
    CONSTRAINT ck_notice_outbox_lease CHECK ((state='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (state<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_notice_outbox_payload CHECK (JSON_VALID(payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Immutable notification intent and fenced publisher state';

CREATE TABLE xl_notification_inbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash BINARY(32) NOT NULL,
    outcome VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_notice_inbox_processed (processed_at),
    CONSTRAINT ck_notice_inbox_outcome CHECK (outcome IN ('APPLIED','STALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Committed notification effects only; no in-progress success rows';

CREATE TABLE xl_notification_failure (
    message_hash BINARY(32) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    body MEDIUMBLOB NOT NULL,
    reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 1,
    broker_retries INT UNSIGNED NOT NULL DEFAULT 0,
    state VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RETRY',
    first_failed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_failed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at DATETIME(3) NULL,
    PRIMARY KEY (message_hash),
    KEY idx_notice_failure_state (state,last_failed_at),
    CONSTRAINT ck_notice_failure_state CHECK (state IN ('RETRY','QUARANTINED','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Restricted poison-message repair evidence; no public management API';
