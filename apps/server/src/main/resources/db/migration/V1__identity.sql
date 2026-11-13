-- 身份与认证。时间由UTC连接写入，业务更新显式维护updated_at和version。
-- 所有外键统一在V5添加，避免用户头像与资源归属形成建表循环。
CREATE TABLE xl_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    wechat_app_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '微信应用标识',
    wechat_open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '仅认证使用的微信用户标识',
    nickname VARCHAR(20) NOT NULL DEFAULT '新同学' COMMENT '用户昵称',
    avatar_image_id BIGINT UNSIGNED NULL COMMENT '头像图片资源',
    bio VARCHAR(40) NOT NULL DEFAULT '' COMMENT '自填简介',
    major VARCHAR(60) NOT NULL DEFAULT '' COMMENT '自填专业，不作身份认证',
    class_name VARCHAR(40) NOT NULL DEFAULT '' COMMENT '自填班级',
    enrollment_year CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '自填入学年份',
    ban_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'none' COMMENT '封禁层状态',
    ban_until DATETIME(3) NULL COMMENT '临时封禁截止UTC时间',
    restrict_publish BOOLEAN NOT NULL DEFAULT FALSE COMMENT '人工限制发布',
    restrict_comment BOOLEAN NOT NULL DEFAULT FALSE COMMENT '人工限制评论',
    restrict_message BOOLEAN NOT NULL DEFAULT FALSE COMMENT '人工限制私信',
    last_active_at DATETIME(3) NULL COMMENT '最近有效业务访问UTC时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    deleted_at DATETIME(3) NULL DEFAULT NULL COMMENT '软删除UTC时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_wechat (wechat_app_id, wechat_open_id),
    KEY idx_user_created (deleted_at, created_at, id),
    KEY idx_user_ban (ban_state, ban_until),
    KEY idx_user_active (last_active_at, id),
    KEY idx_user_avatar (avatar_image_id),
    CONSTRAINT ck_user_nickname CHECK (CHAR_LENGTH(TRIM(nickname)) BETWEEN 1 AND 20),
    CONSTRAINT ck_user_year CHECK (enrollment_year IS NULL OR enrollment_year REGEXP '^(19|20)[0-9]{2}$'),
    CONSTRAINT ck_user_ban_state CHECK (ban_state IN ('none', 'temporary', 'permanent')),
    CONSTRAINT ck_user_ban_until CHECK (
        (ban_state = 'temporary' AND ban_until IS NOT NULL) OR
        (ban_state IN ('none', 'permanent') AND ban_until IS NULL)
    ),
    CONSTRAINT ck_user_restrictions CHECK (restrict_publish IN (0, 1) AND restrict_comment IN (0, 1) AND restrict_message IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户资料与独立人工限制、封禁层';

CREATE TABLE xl_user_preference (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户主键',
    messages BOOLEAN NOT NULL DEFAULT TRUE COMMENT '消息提醒',
    interactions BOOLEAN NOT NULL DEFAULT TRUE COMMENT '互动提醒',
    announcements BOOLEAN NOT NULL DEFAULT TRUE COMMENT '公告提醒',
    sound BOOLEAN NOT NULL DEFAULT FALSE COMMENT '声音提醒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (user_id),
    CONSTRAINT ck_preference_flags CHECK (messages IN (0, 1) AND interactions IN (0, 1) AND announcements IN (0, 1) AND sound IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户站内提醒偏好';

CREATE TABLE xl_user_block (
    owner_id BIGINT UNSIGNED NOT NULL COMMENT '屏蔽发起用户',
    blocked_user_id BIGINT UNSIGNED NOT NULL COMMENT '被屏蔽用户',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '屏蔽UTC时间',
    PRIMARY KEY (owner_id, blocked_user_id),
    KEY idx_block_peer (blocked_user_id, owner_id),
    CONSTRAINT ck_block_distinct CHECK (owner_id <> blocked_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户屏蔽关系，解除时物理删行';

CREATE TABLE xl_admin (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '管理员主键',
    login_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端归一化登录名',
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'BCrypt密码摘要',
    display_name VARCHAR(20) NOT NULL COMMENT '安全展示名',
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ADMIN' COMMENT '管理员角色',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'active' COMMENT '账号状态',
    password_changed_at DATETIME(3) NOT NULL COMMENT '密码变更UTC时间',
    last_login_at DATETIME(3) NULL COMMENT '最近登录UTC时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_login (login_name),
    CONSTRAINT ck_admin_login CHECK (CHAR_LENGTH(TRIM(login_name)) > 0),
    CONSTRAINT ck_admin_display CHECK (CHAR_LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT ck_admin_role CHECK (role = 'ADMIN'),
    CONSTRAINT ck_admin_status CHECK (status IN ('active', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员认证账号';

CREATE TABLE xl_auth_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '认证会话主键',
    subject_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户或管理员主体类型',
    subject_id BIGINT UNSIGNED NOT NULL COMMENT '主体逻辑关联标识',
    refresh_hash BINARY(32) NOT NULL COMMENT '刷新票据SHA-256摘要',
    refresh_generation INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '刷新轮换代次',
    csrf_hash BINARY(32) NULL COMMENT '管理员CSRF摘要',
    expires_at DATETIME(3) NOT NULL COMMENT '到期UTC时间',
    revoked_at DATETIME(3) NULL COMMENT '吊销UTC时间',
    last_used_at DATETIME(3) NULL COMMENT '最近使用UTC时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建UTC时间',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新UTC时间',
    version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '轮换并发版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_refresh (refresh_hash),
    KEY idx_session_subject (subject_type, subject_id, revoked_at),
    KEY idx_session_expiry (expires_at),
    CONSTRAINT ck_session_subject CHECK (subject_type IN ('user', 'admin') AND subject_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只存摘要的可吊销认证会话';
