ALTER TABLE users ADD COLUMN username character varying(30);
ALTER TABLE users ADD COLUMN bio character varying(240);
ALTER TABLE users ADD COLUMN location character varying(100);
ALTER TABLE users ADD COLUMN tradition character varying(100);
ALTER TABLE users ADD COLUMN home_church_id bigint REFERENCES churches(id);
ALTER TABLE users ADD COLUMN profile_visibility character varying(20) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE users ADD COLUMN show_location boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN show_tradition boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN show_home_church boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN show_following boolean NOT NULL DEFAULT true;

-- Case-insensitive uniqueness, partial so any number of accounts with no
-- username yet (NULL) can coexist — see User.java's username field comment.
CREATE UNIQUE INDEX uq_users_username ON users (LOWER(username)) WHERE username IS NOT NULL;
