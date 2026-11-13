-- 消息序号与已读水位由会话行锁和事务维护；正文不可编辑。
CREATE TABLE xl_conversation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话主键',
    low_user_id BIGINT UNSIGNED NOT NULL COMMENT '数值较小的用户',
    high_user_id BIGINT UNSIGNED NOT NULL COMMENT '数值较大的用户',
    next_sequence BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '下一条可分配序号',
    last_message_id BIGINT UNSIGNED NULL COMMENT '最近消息逻辑指针',
    last_message_at DATETIME(3) NULL COMMENT '最近消息UTC时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_pair (low_user_id, high_user_id),
    KEY idx_conversation_high (high_user_id),
    KEY idx_conversation_activity (last_message_at, id),
    CONSTRAINT ck_conversation_pair CHECK (low_user_id < high_user_id),
    CONSTRAINT ck_conversation_sequence CHECK (next_sequence >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一对一会话，商品不另建会话';

CREATE TABLE xl_conversation_member (
    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '会话主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '成员用户',
    read_through_seq BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已读序号水位',
    cleared_through_seq BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '个人清空序号水位',
    muted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '会话免打扰',
    pinned BOOLEAN NOT NULL DEFAULT FALSE COMMENT '会话置顶',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (conversation_id, user_id),
    KEY idx_member_user (user_id, pinned, conversation_id),
    CONSTRAINT ck_member_watermarks CHECK (read_through_seq >= cleared_through_seq),
    CONSTRAINT ck_member_flags CHECK (muted IN (0, 1) AND pinned IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成员水位和偏好，事务保证恰为会话双方';

CREATE TABLE xl_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息主键',
    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '所属会话',
    sender_id BIGINT UNSIGNED NOT NULL COMMENT '发送成员用户',
    sequence_no BIGINT UNSIGNED NOT NULL COMMENT '会话内提交序号',
    client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发送者幂等消息标识',
    request_hash BINARY(32) NOT NULL COMMENT '会话、文字及商品的初次请求摘要',
    kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'text' COMMENT '消息类型',
    text VARCHAR(1000) NOT NULL COMMENT '不可编辑的文字正文',
    product_id BIGINT UNSIGNED NULL COMMENT '可选商品逻辑关联',
    product_title_snapshot VARCHAR(60) NULL COMMENT '受可见性限制的商品标题快照',
    product_price_fen_snapshot INT UNSIGNED NULL COMMENT '商品价格快照，单位分',
    moderation_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'normal' COMMENT '消息治理状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '治理更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    -- 唯一序号索引同时覆盖历史游标，不再建立相同列的普通索引。
    UNIQUE KEY uk_message_sequence (conversation_id, sequence_no),
    UNIQUE KEY uk_message_client (sender_id, client_message_id),
    KEY idx_message_product (product_id, sender_id),
    CONSTRAINT ck_message_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_message_client CHECK (CHAR_LENGTH(client_message_id) > 0),
    CONSTRAINT ck_message_kind CHECK (kind = 'text'),
    CONSTRAINT ck_message_text CHECK (CHAR_LENGTH(TRIM(text)) > 0),
    CONSTRAINT ck_message_price CHECK (product_price_fen_snapshot IS NULL OR product_price_fen_snapshot BETWEEN 1 AND 9999999),
    CONSTRAINT ck_message_moderation CHECK (moderation_state IN ('normal', 'hidden'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持久化消息，隐藏后保留序号占位';

CREATE TABLE xl_conversation_product (
    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '咨询会话',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '咨询商品',
    attached_by BIGINT UNSIGNED NOT NULL COMMENT '首次关联的会话成员',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次关联UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近关联UTC时间',
    PRIMARY KEY (conversation_id, product_id),
    KEY idx_conversation_product_recent (conversation_id, updated_at, product_id),
    KEY idx_product_inquiry (product_id, conversation_id),
    KEY idx_conversation_product_attacher (attached_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打开咨询即保存的商品关联，无需生成消息';

CREATE TABLE xl_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '举报主键',
    reporter_id BIGINT UNSIGNED NOT NULL COMMENT '举报用户',
    target_type VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '举报目标类型',
    target_id BIGINT UNSIGNED NOT NULL COMMENT '举报目标逻辑标识',
    target_user_id BIGINT UNSIGNED NOT NULL COMMENT '服务端推导的目标用户',
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '举报原因编码',
    description VARCHAR(200) NOT NULL COMMENT '举报说明，仅案件授权读取',
    priority VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'medium' COMMENT '处理优先级',
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'pending' COMMENT '待处理或已处理',
    target_snapshot JSON NOT NULL COMMENT '受控目标原文和身份快照',
    resolution VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '处理动作编码',
    resolution_reason VARCHAR(200) NULL COMMENT '处理原因',
    resolved_by BIGINT UNSIGNED NULL COMMENT '处理管理员',
    resolved_at DATETIME(3) NULL COMMENT '处理UTC时间',
    client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '提交幂等请求标识',
    request_hash BINARY(32) NOT NULL COMMENT '初次规范请求摘要',
    pending_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'pending' THEN 1 ELSE NULL END) STORED COMMENT '仅待处理举报参与重复约束',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处置乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_pending (reporter_id, target_type, target_id, pending_guard),
    UNIQUE KEY uk_report_request (reporter_id, client_request_id),
    KEY idx_report_queue (status, priority, created_at, id),
    KEY idx_report_target (target_type, target_id),
    KEY idx_report_user (target_user_id, created_at, id),
    KEY idx_report_resolver (resolved_by),
    CONSTRAINT ck_report_target CHECK (target_type IN ('post', 'comment', 'product', 'user', 'message') AND target_id > 0),
    CONSTRAINT ck_report_reason CHECK (reason_code IN ('commercial_ad', 'fraud_or_offsite_payment', 'harassment', 'impersonation', 'other')),
    CONSTRAINT ck_report_description CHECK (CHAR_LENGTH(TRIM(description)) BETWEEN 1 AND 200),
    CONSTRAINT ck_report_priority CHECK (priority IN ('high', 'medium', 'low')),
    CONSTRAINT ck_report_status CHECK (status IN ('pending', 'processed')),
    CONSTRAINT ck_report_resolution CHECK (resolution IS NULL OR resolution IN ('reject', 'hide_post', 'hide_comment', 'off_shelf_product', 'restrict_user', 'warn_user', 'hide_message')),
    CONSTRAINT ck_report_result CHECK (
        (status = 'pending' AND resolution IS NULL AND resolution_reason IS NULL AND resolved_by IS NULL AND resolved_at IS NULL) OR
        (status = 'processed' AND resolution IS NOT NULL AND resolution_reason IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT ck_report_request CHECK (CHAR_LENGTH(client_request_id) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='案件举报、原始快照与一次性审核结果';

CREATE TABLE xl_moderation_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '处置历史主键',
    operation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '处置操作唯一标识',
    source VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户管理、举报或系统来源',
    report_id BIGINT UNSIGNED NULL COMMENT '关联举报案件',
    target_type VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '处置目标类型',
    target_id BIGINT UNSIGNED NOT NULL COMMENT '处置目标逻辑标识',
    target_user_id BIGINT UNSIGNED NULL COMMENT '关联目标用户',
    admin_id BIGINT UNSIGNED NULL COMMENT '处置管理员，系统到期可为空',
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务动作编码',
    reason VARCHAR(200) NOT NULL COMMENT '业务处置原因',
    before_state JSON NOT NULL COMMENT '必要的处置前状态',
    after_state JSON NOT NULL COMMENT '必要的处置后状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '追加UTC时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_moderation_operation (operation_id),
    KEY idx_moderation_report (report_id, created_at, id),
    KEY idx_moderation_user (target_user_id, created_at, id),
    KEY idx_moderation_admin (admin_id),
    CONSTRAINT ck_moderation_operation CHECK (CHAR_LENGTH(operation_id) > 0),
    CONSTRAINT ck_moderation_source CHECK (source IN ('user_management', 'report', 'system')),
    CONSTRAINT ck_moderation_target CHECK (target_type IN ('post', 'comment', 'product', 'user', 'message') AND target_id > 0),
    CONSTRAINT ck_moderation_action CHECK (CHAR_LENGTH(action) > 0),
    CONSTRAINT ck_moderation_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与业务同事务的处置历史，只追加';
