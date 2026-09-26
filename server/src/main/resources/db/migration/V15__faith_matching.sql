-- Catalogs and published profiles are immutable snapshots. Unknown positions are never inherited.
CREATE TABLE faith_catalogs (
  version VARCHAR(80) PRIMARY KEY,
  document TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE faith_catalog_current (
  singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
  version VARCHAR(80) NOT NULL REFERENCES faith_catalogs(version)
);
CREATE TABLE faith_profiles (
  id BIGSERIAL PRIMARY KEY,
  church_id BIGINT NOT NULL REFERENCES churches(id) ON DELETE CASCADE,
  catalog_version VARCHAR(80) NOT NULL REFERENCES faith_catalogs(version),
  document TEXT NOT NULL,
  confirmed_by VARCHAR(200) NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX faith_profiles_church_revision ON faith_profiles(church_id, id DESC);
CREATE TABLE faith_profile_current (
  church_id BIGINT PRIMARY KEY REFERENCES churches(id) ON DELETE CASCADE,
  profile_id BIGINT NOT NULL UNIQUE REFERENCES faith_profiles(id) ON DELETE CASCADE
);
CREATE TABLE faith_drafts (
  church_id BIGINT PRIMARY KEY REFERENCES churches(id) ON DELETE CASCADE,
  document TEXT NOT NULL,
  updated_by BIGINT NOT NULL REFERENCES users(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE faith_saved_attempts (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  request_document TEXT NOT NULL,
  result_document TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX faith_saved_user ON faith_saved_attempts(user_id, id DESC);
