-- Free scheduled lucky bags. UTC DATETIME(3), fixed 1000 cents, no payment integration.
CREATE TABLE xl_lucky_bag (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 name VARCHAR(80) NOT NULL,
 prize_cents INT UNSIGNED NOT NULL DEFAULT 1000,
 draw_at DATETIME(3) NOT NULL,
 instructions VARCHAR(500) NOT NULL,
 wechat VARCHAR(128) NOT NULL,
 status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'draft',
 participant_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
 cancel_reason VARCHAR(300) NULL,
 created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 published_at DATETIME(3) NULL,
 version INT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 KEY idx_lucky_bag_due(status,draw_at,id),
 CONSTRAINT ck_lucky_bag_status CHECK(status IN ('draft','published','drawn','cancelled')),
 CONSTRAINT ck_lucky_bag_prize CHECK(prize_cents=1000),
 CONSTRAINT ck_lucky_bag_text CHECK(CHAR_LENGTH(TRIM(name))>0 AND CHAR_LENGTH(TRIM(instructions))>0 AND CHAR_LENGTH(TRIM(wechat))>0),
 CONSTRAINT ck_lucky_bag_cancel CHECK(status<>'cancelled' OR (cancel_reason IS NOT NULL AND CHAR_LENGTH(TRIM(cancel_reason))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Lock this singleton before any activity lock when publishing/changing current display.
CREATE TABLE xl_lucky_display (
 id TINYINT UNSIGNED NOT NULL,
 activity_id BIGINT UNSIGNED NULL,
 PRIMARY KEY(id),
 CONSTRAINT ck_lucky_display_singleton CHECK(id=1),
 CONSTRAINT fk_lucky_display_activity FOREIGN KEY(activity_id) REFERENCES xl_lucky_bag(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO xl_lucky_display(id,activity_id) VALUES(1,NULL);

CREATE TABLE xl_lucky_entry (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 activity_id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 UNIQUE KEY uq_lucky_entry_activity_user(activity_id,user_id),
 KEY idx_lucky_entry_draw(activity_id,id),
 CONSTRAINT fk_lucky_entry_activity FOREIGN KEY(activity_id) REFERENCES xl_lucky_bag(id) ON DELETE RESTRICT,
 CONSTRAINT ck_lucky_entry_user CHECK(user_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE xl_lucky_result (
 activity_id BIGINT UNSIGNED NOT NULL,
 winner_user_id BIGINT UNSIGNED NULL,
 participant_count BIGINT UNSIGNED NOT NULL,
 scheduled_at DATETIME(3) NOT NULL,
 drawn_at DATETIME(3) NOT NULL,
 algorithm VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(activity_id),
 UNIQUE KEY uq_lucky_result_winner(activity_id,winner_user_id),
 CONSTRAINT fk_lucky_result_activity FOREIGN KEY(activity_id) REFERENCES xl_lucky_bag(id) ON DELETE RESTRICT,
 CONSTRAINT ck_lucky_result_participants CHECK((participant_count=0 AND winner_user_id IS NULL) OR (participant_count>0 AND winner_user_id IS NOT NULL AND winner_user_id>0)),
 CONSTRAINT ck_lucky_result_time CHECK(drawn_at>=scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Recoverable secret: intentionally stored in the protected DB; never in a public projection,
-- URL, notification body or generic audit. Backups require the same private ACL as runtime secrets.
CREATE TABLE xl_lucky_claim (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 activity_id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 code CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'pending',
 version INT UNSIGNED NOT NULL DEFAULT 0,
 notification_sent BOOLEAN NOT NULL DEFAULT FALSE,
 started_by BIGINT UNSIGNED NULL,
 started_at DATETIME(3) NULL,
 paid_by BIGINT UNSIGNED NULL,
 paid_at DATETIME(3) NULL,
 start_operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 paid_operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uq_lucky_claim_activity(activity_id),
 UNIQUE KEY uq_lucky_claim_code(code),
 KEY idx_lucky_claim_user(user_id,activity_id),
 KEY idx_lucky_claim_notice(notification_sent,id),
 CONSTRAINT fk_lucky_claim_result FOREIGN KEY(activity_id) REFERENCES xl_lucky_result(activity_id) ON DELETE RESTRICT,
 CONSTRAINT fk_lucky_claim_winner FOREIGN KEY(activity_id,user_id) REFERENCES xl_lucky_result(activity_id,winner_user_id) ON DELETE RESTRICT,
 CONSTRAINT ck_lucky_claim_user CHECK(user_id>0),
 CONSTRAINT ck_lucky_claim_status CHECK(status IN ('pending','issuing','paid')),
 CONSTRAINT ck_lucky_claim_code CHECK(code REGEXP '^[A-F0-9]{32}$'),
 CONSTRAINT ck_lucky_claim_lifecycle CHECK(
  (status='pending' AND started_by IS NULL AND started_at IS NULL AND start_operation_id IS NULL AND paid_by IS NULL AND paid_at IS NULL AND paid_operation_id IS NULL)
  OR (status='issuing' AND started_by IS NOT NULL AND started_at IS NOT NULL AND start_operation_id IS NOT NULL AND paid_by IS NULL AND paid_at IS NULL AND paid_operation_id IS NULL)
  OR (status='paid' AND started_by IS NOT NULL AND started_at IS NOT NULL AND start_operation_id IS NOT NULL AND paid_by IS NOT NULL AND paid_at IS NOT NULL AND paid_operation_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE xl_lucky_operation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 activity_id BIGINT UNSIGNED NOT NULL,
 claim_id BIGINT UNSIGNED NULL,
 admin_id BIGINT UNSIGNED NULL,
 action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(300) NOT NULL DEFAULT '',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id),
 KEY idx_lucky_operation_activity(activity_id,id),
 CONSTRAINT fk_lucky_operation_activity FOREIGN KEY(activity_id) REFERENCES xl_lucky_bag(id) ON DELETE RESTRICT,
 CONSTRAINT fk_lucky_operation_claim FOREIGN KEY(claim_id) REFERENCES xl_lucky_claim(id) ON DELETE RESTRICT,
 CONSTRAINT ck_lucky_operation_action CHECK(action IN ('create','edit','publish','cancel','display','hide','draw','verify','start','confirm'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- New platform event and polymorphic destination, independent of the interaction MQ protocol.
ALTER TABLE xl_user_notification DROP CHECK ck_notice_kind, DROP CHECK ck_notice_target,
 ADD CONSTRAINT ck_notice_kind CHECK(event_kind IN ('comment','reply','post_like','comment_like','post_favorite','system','announcement','moderation_warning','lucky_bag_win')),
 ADD CONSTRAINT ck_notice_target CHECK((target_type IS NULL AND target_id IS NULL) OR (target_type IS NOT NULL AND target_type IN ('post','comment','user','message','announcement','lucky_bag') AND target_id IS NOT NULL AND target_id>0));
ALTER TABLE xl_audit_log DROP CHECK ck_audit_module,
 ADD CONSTRAINT ck_audit_module CHECK(module IN ('posts','comments','reports','users','announcements','notifications','settings','lucky_bags'));
