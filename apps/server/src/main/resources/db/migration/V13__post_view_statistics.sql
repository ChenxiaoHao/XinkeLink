-- Independent reliable browser-event receipt, exact aggregate and repair metadata. No public identity projections.
CREATE TABLE xl_view_receipt (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    visitor_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    payload VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    last_error VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_view_receipt_due (state,next_attempt_at,event_id),
    KEY idx_view_receipt_lease (state,lease_until,event_id),
    KEY idx_view_receipt_age (state,occurred_at),
    CONSTRAINT ck_view_receipt_post CHECK (post_id > 0),
    CONSTRAINT ck_view_receipt_state CHECK (state IN ('READY','LEASED','SENT')),
    CONSTRAINT ck_view_receipt_lease CHECK ((state='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (state<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_view_receipt_payload CHECK (JSON_VALID(payload) AND OCTET_LENGTH(payload) <= 2048)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Immutable first accepted event plus fenced Kafka publisher state';

CREATE TABLE xl_view_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    visitor_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_view_event_age (occurred_at),
    KEY idx_view_event_post (post_id,occurred_at),
    CONSTRAINT ck_view_event_post CHECK (post_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Unique committed event effects retained across Kafka replays';

CREATE TABLE xl_view_daily (
    post_id BIGINT UNSIGNED NOT NULL,
    view_date DATE NOT NULL,
    pv BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (post_id,view_date),
    KEY idx_view_daily_window (view_date,post_id),
    CONSTRAINT ck_view_daily_post CHECK (post_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='PV per post and Beijing event calendar date';

CREATE TABLE xl_view_visitor_day (
    post_id BIGINT UNSIGNED NOT NULL,
    view_date DATE NOT NULL,
    visitor_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (post_id,view_date,visitor_key),
    KEY idx_view_visitor_window (view_date,post_id,visitor_key),
    CONSTRAINT ck_view_visitor_post CHECK (post_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Exact daily membership; seven-day UV uses cross-day DISTINCT';

CREATE TABLE xl_view_projection (
    id TINYINT UNSIGNED NOT NULL,
    generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT ck_view_projection_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Durable monotonic fence for disposable Redis ranking snapshots';

CREATE TABLE xl_view_failure (
    topic VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_id INT UNSIGNED NOT NULL,
    record_offset BIGINT UNSIGNED NOT NULL,
    digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 1,
    state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
    payload VARBINARY(2048) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (topic,partition_id,record_offset),
    KEY idx_view_failure_state (state,updated_at),
    CONSTRAINT ck_view_failure_state CHECK (state IN ('OPEN','RESOLVED')),
    CONSTRAINT ck_view_failure_attempts CHECK (attempts > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Durable blocked partition evidence; original event repair keeps event identity';
