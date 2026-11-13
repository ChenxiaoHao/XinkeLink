-- Remove the retired marketplace and its dependent records. Keep V1-V8 immutable.
-- Text messages and their sequence/read watermarks survive; only product context is removed.
CREATE TABLE retired_market_comments (id BIGINT UNSIGNED PRIMARY KEY);
INSERT INTO retired_market_comments SELECT id FROM xl_comment WHERE target_type = 'product';
CREATE TABLE retired_market_reports (id BIGINT UNSIGNED PRIMARY KEY);
INSERT INTO retired_market_reports
SELECT id FROM xl_report WHERE target_type = 'product'
 OR (target_type = 'comment' AND target_id IN (SELECT id FROM retired_market_comments));
CREATE TABLE retired_market_announcements (id BIGINT UNSIGNED PRIMARY KEY);
INSERT INTO retired_market_announcements SELECT id FROM xl_announcement WHERE category = 'market' OR scope = 'market';
CREATE TABLE retired_market_tasks (id BIGINT UNSIGNED PRIMARY KEY);
INSERT INTO retired_market_tasks SELECT id FROM xl_notification_task WHERE audience = 'sellers';
CREATE TABLE retired_market_assets (id BIGINT UNSIGNED PRIMARY KEY);
INSERT INTO retired_market_assets SELECT id FROM xl_image_asset WHERE purpose = 'product';
INSERT IGNORE INTO retired_market_assets
SELECT image_id FROM xl_media_ref WHERE owner_type = 'product'
 OR (owner_type IN ('report_evidence', 'report_snapshot') AND owner_id IN (SELECT id FROM retired_market_reports));

DELETE FROM xl_user_notification WHERE target_type = 'product'
 OR event_kind IN ('product_like', 'product_favorite')
 OR (target_type = 'comment' AND target_id IN (SELECT id FROM retired_market_comments))
 OR task_id IN (SELECT id FROM retired_market_tasks)
 OR announcement_id IN (SELECT id FROM retired_market_announcements)
 OR (target_type = 'announcement' AND target_id IN (SELECT id FROM retired_market_announcements))
 OR event_key IN (SELECT CONCAT('moderation:report:', id, ':reporter') FROM retired_market_reports)
 OR event_key IN (SELECT CONCAT('moderation:report:', id, ':target') FROM retired_market_reports);
DELETE FROM xl_audit_log WHERE module = 'products' OR target_type = 'product'
 OR (target_type = 'report' AND target_internal_id IN (SELECT id FROM retired_market_reports))
 OR (target_type = 'comment' AND target_internal_id IN (SELECT id FROM retired_market_comments))
 OR (target_type = 'announcement' AND target_internal_id IN (SELECT id FROM retired_market_announcements))
 OR (target_type = 'notification' AND target_internal_id IN (SELECT id FROM retired_market_tasks));
DELETE FROM xl_moderation_action WHERE target_type = 'product'
 OR report_id IN (SELECT id FROM retired_market_reports)
 OR (target_type = 'comment' AND target_id IN (SELECT id FROM retired_market_comments));
DELETE FROM xl_media_ref WHERE owner_type = 'product'
 OR (owner_type IN ('report_evidence', 'report_snapshot') AND owner_id IN (SELECT id FROM retired_market_reports));
DELETE FROM xl_report WHERE id IN (SELECT id FROM retired_market_reports);
DELETE FROM xl_like WHERE target_type = 'product'
 OR (target_type = 'comment' AND target_id IN (SELECT id FROM retired_market_comments));
DELETE FROM xl_favorite WHERE target_type = 'product';
-- Replies may reference sibling replies. Clear only retired comment links first so
-- self-referencing RESTRICT keys never depend on MySQL's physical delete order.
UPDATE xl_comment SET root_id = NULL, reply_to_id = NULL WHERE target_type = 'product';
DELETE FROM xl_comment WHERE target_type = 'product';
DELETE FROM xl_announcement WHERE id IN (SELECT id FROM retired_market_announcements);
DELETE FROM xl_notification_task WHERE id IN (SELECT id FROM retired_market_tasks);
-- Shared assets remain accessible through surviving forum/avatar/report bindings.
DELETE FROM xl_image_asset WHERE id IN (SELECT id FROM retired_market_assets)
 AND NOT EXISTS (SELECT 1 FROM xl_media_ref WHERE image_id = xl_image_asset.id)
 AND NOT EXISTS (SELECT 1 FROM xl_user WHERE avatar_image_id = xl_image_asset.id);
UPDATE xl_image_asset a SET purpose = CASE
 WHEN EXISTS (SELECT 1 FROM xl_user u WHERE u.avatar_image_id = a.id) THEN 'avatar'
 WHEN EXISTS (SELECT 1 FROM xl_media_ref r WHERE r.image_id = a.id AND r.owner_type = 'post') THEN 'post'
 ELSE 'report' END WHERE purpose = 'product';

DROP TABLE IF EXISTS xl_conversation_product;
DROP TABLE IF EXISTS xl_product;
ALTER TABLE xl_message DROP CHECK ck_message_price, DROP INDEX idx_message_product,
 DROP COLUMN product_id, DROP COLUMN product_title_snapshot, DROP COLUMN product_price_fen_snapshot,
 COMMENT = '持久化文字消息，隐藏后保留序号占位';
ALTER TABLE xl_conversation COMMENT = '一对一私信会话';
ALTER TABLE xl_comment DROP CHECK ck_comment_target,
 ADD CONSTRAINT ck_comment_target CHECK (target_type = 'post' AND target_id > 0),
 MODIFY target_type VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '帖子目标类型';
ALTER TABLE xl_like DROP CHECK ck_like_target,
 ADD CONSTRAINT ck_like_target CHECK (target_type IN ('post', 'comment') AND target_id > 0);
ALTER TABLE xl_favorite DROP CHECK ck_favorite_target,
 ADD CONSTRAINT ck_favorite_target CHECK (target_type = 'post' AND target_id > 0);
ALTER TABLE xl_report DROP CHECK ck_report_target, DROP CHECK ck_report_resolution,
 ADD CONSTRAINT ck_report_target CHECK (target_type IN ('post', 'comment', 'user', 'message') AND target_id > 0),
 ADD CONSTRAINT ck_report_resolution CHECK (resolution IS NULL OR resolution IN ('reject', 'hide_post', 'hide_comment', 'restrict_user', 'warn_user', 'hide_message'));
ALTER TABLE xl_moderation_action DROP CHECK ck_moderation_target,
 ADD CONSTRAINT ck_moderation_target CHECK (target_type IN ('post', 'comment', 'user', 'message') AND target_id > 0);
ALTER TABLE xl_announcement DROP CHECK ck_announcement_category, DROP CHECK ck_announcement_scope,
 ADD CONSTRAINT ck_announcement_category CHECK (category IN ('community', 'activity', 'safety')),
 ADD CONSTRAINT ck_announcement_scope CHECK (scope IN ('all', 'forum'));
ALTER TABLE xl_notification_task DROP CHECK ck_task_audience,
 ADD CONSTRAINT ck_task_audience CHECK (audience IN ('all', 'active', 'recent'));
ALTER TABLE xl_user_notification DROP CHECK ck_notice_kind, DROP CHECK ck_notice_target,
 ADD CONSTRAINT ck_notice_kind CHECK (event_kind IN ('comment', 'reply', 'post_like', 'comment_like', 'post_favorite', 'system', 'announcement', 'moderation_warning')),
 ADD CONSTRAINT ck_notice_target CHECK ((target_type IS NULL AND target_id IS NULL) OR (target_type IS NOT NULL AND target_type IN ('post', 'comment', 'user', 'message', 'announcement') AND target_id IS NOT NULL AND target_id > 0));
ALTER TABLE xl_audit_log DROP CHECK ck_audit_module,
 ADD CONSTRAINT ck_audit_module CHECK (module IN ('posts', 'comments', 'reports', 'users', 'announcements', 'notifications', 'settings'));
ALTER TABLE xl_image_asset DROP CHECK ck_asset_purpose,
 ADD CONSTRAINT ck_asset_purpose CHECK (purpose IN ('avatar', 'post', 'report'));
ALTER TABLE xl_media_ref DROP CHECK ck_media_owner,
 ADD CONSTRAINT ck_media_owner CHECK (owner_type IN ('user_avatar', 'post', 'report_evidence', 'report_snapshot') AND owner_id > 0);
DROP TABLE retired_market_assets;
DROP TABLE retired_market_tasks;
DROP TABLE retired_market_announcements;
DROP TABLE retired_market_reports;
DROP TABLE retired_market_comments;
