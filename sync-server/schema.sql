CREATE TABLE IF NOT EXISTS users (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT NOT NULL,
  token_hash  TEXT NOT NULL UNIQUE,
  created_at  BIGINT NOT NULL
);

-- One row per (user, key). updated_at is client-supplied epoch ms;
-- the newest write wins (last-write-wins per key).
CREATE TABLE IF NOT EXISTS sync_entries (
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  key         TEXT NOT NULL,
  value       TEXT,
  deleted     BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at  BIGINT NOT NULL,
  device_id   TEXT NOT NULL,
  PRIMARY KEY (user_id, key)
);

CREATE INDEX IF NOT EXISTS sync_entries_user_updated
  ON sync_entries (user_id, updated_at);
