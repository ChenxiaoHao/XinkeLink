-- Keep historical activity and notification records, but remove retired notices from inboxes and unread counts.
UPDATE xl_user_notification
SET active = 0, updated_at = UTC_TIMESTAMP(3), version = version + 1
WHERE event_kind = 'lucky_bag_win' AND active = 1;
