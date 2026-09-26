-- Match users.display_name so every valid account name can confirm a profile.
ALTER TABLE faith_profiles ALTER COLUMN confirmed_by TYPE VARCHAR(255);
