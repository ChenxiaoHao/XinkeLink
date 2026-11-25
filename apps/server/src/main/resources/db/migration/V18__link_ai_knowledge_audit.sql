ALTER TABLE xl_audit_log DROP CHECK ck_audit_module,
 ADD CONSTRAINT ck_audit_module CHECK (module IN ('posts','comments','reports','users','announcements','notifications','settings','lucky_bags','ai_knowledge'));


