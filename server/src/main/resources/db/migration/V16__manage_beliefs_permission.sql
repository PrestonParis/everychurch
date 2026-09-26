-- Extend the database enum check as well as Java's permission enum. Owners retain
-- every capability even when their stored permissions predate this migration.
ALTER TABLE church_staff_permissions DROP CONSTRAINT IF EXISTS church_staff_permissions_permission_check;
ALTER TABLE church_staff_permissions ADD CONSTRAINT church_staff_permissions_permission_check
  CHECK (permission IN ('EDIT_PROFILE','MANAGE_SERMONS','MANAGE_EVENTS','MANAGE_STAFF','MANAGE_POSTS','MANAGE_BELIEFS'));
