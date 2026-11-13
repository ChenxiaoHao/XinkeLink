-- 统一后补明确实体外键。全部限制物理级联；软删不触发级联操作。
-- 多态目标、认证主体、会话最近消息和消息商品保持设计中的逻辑关联。
ALTER TABLE xl_user
    ADD CONSTRAINT fk_user_avatar FOREIGN KEY (avatar_image_id) REFERENCES xl_image_asset (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_user_preference
    ADD CONSTRAINT fk_preference_user FOREIGN KEY (user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_user_block
    ADD CONSTRAINT fk_block_owner FOREIGN KEY (owner_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_block_peer FOREIGN KEY (blocked_user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_post
    ADD CONSTRAINT fk_post_author FOREIGN KEY (author_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_comment
    ADD CONSTRAINT fk_comment_author FOREIGN KEY (author_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_comment_root FOREIGN KEY (root_id) REFERENCES xl_comment (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_comment_reply FOREIGN KEY (reply_to_id) REFERENCES xl_comment (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_like
    ADD CONSTRAINT fk_like_user FOREIGN KEY (user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_favorite
    ADD CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_product
    ADD CONSTRAINT fk_product_seller FOREIGN KEY (seller_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_conversation
    ADD CONSTRAINT fk_conversation_low FOREIGN KEY (low_user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_conversation_high FOREIGN KEY (high_user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_conversation_member
    ADD CONSTRAINT fk_member_conversation FOREIGN KEY (conversation_id) REFERENCES xl_conversation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_message
    ADD CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES xl_conversation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_conversation_product
    ADD CONSTRAINT fk_conversation_product_conversation FOREIGN KEY (conversation_id) REFERENCES xl_conversation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_conversation_product_product FOREIGN KEY (product_id) REFERENCES xl_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_conversation_product_attacher FOREIGN KEY (attached_by) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_report
    ADD CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_report_target_user FOREIGN KEY (target_user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_report_resolver FOREIGN KEY (resolved_by) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_moderation_action
    ADD CONSTRAINT fk_moderation_report FOREIGN KEY (report_id) REFERENCES xl_report (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_moderation_user FOREIGN KEY (target_user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_moderation_admin FOREIGN KEY (admin_id) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_announcement
    ADD CONSTRAINT fk_announcement_creator FOREIGN KEY (created_by) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_announcement_updater FOREIGN KEY (updated_by) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_notification_task
    ADD CONSTRAINT fk_task_creator FOREIGN KEY (created_by) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_task_updater FOREIGN KEY (updated_by) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_user_notification
    ADD CONSTRAINT fk_notice_recipient FOREIGN KEY (recipient_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_notice_actor FOREIGN KEY (actor_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_notice_task FOREIGN KEY (task_id) REFERENCES xl_notification_task (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_notice_announcement FOREIGN KEY (announcement_id) REFERENCES xl_announcement (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_audit_log
    ADD CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_system_setting
    ADD CONSTRAINT fk_setting_updater FOREIGN KEY (updated_by) REFERENCES xl_admin (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_image_asset
    ADD CONSTRAINT fk_asset_owner FOREIGN KEY (owner_user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_media_ref
    ADD CONSTRAINT fk_media_image FOREIGN KEY (image_id) REFERENCES xl_image_asset (id) ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE xl_user_activity_day
    ADD CONSTRAINT fk_activity_user FOREIGN KEY (user_id) REFERENCES xl_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT;
