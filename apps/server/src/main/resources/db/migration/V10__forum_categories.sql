-- Forum categories only; secondhand posts do not restore the retired marketplace.
ALTER TABLE xl_post DROP CHECK ck_post_category,
 ADD CONSTRAINT ck_post_category CHECK (category IN ('campus', 'lost_found', 'study', 'help', 'secondhand', 'complain', 'buddy'));
