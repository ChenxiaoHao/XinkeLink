-- 仅扩展点赞与通知的合法类型，保留全部旧收藏和历史通知。
ALTER TABLE xl_like DROP CHECK ck_like_target,
    ADD CONSTRAINT ck_like_target CHECK (target_type IN ('post', 'comment', 'product') AND target_id > 0);
ALTER TABLE xl_user_notification DROP CHECK ck_notice_kind,
    ADD CONSTRAINT ck_notice_kind CHECK (event_kind IN ('comment', 'reply', 'post_like', 'comment_like', 'product_like', 'post_favorite', 'product_favorite', 'system', 'announcement', 'moderation_warning'));
