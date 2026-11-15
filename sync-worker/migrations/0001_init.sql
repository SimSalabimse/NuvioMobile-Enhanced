-- Analyzed-details sync. The Worker stores ciphertext and routing metadata only.
-- database_id in wrangler.jsonc is a placeholder. Do not apply this until the
-- owner has created a D1 database. See sync-worker/README.md.
--
-- Each new blob writes the table row plus the primary key and two unique
-- indexes. D1 counts an index write as an extra written row. See the README.

CREATE TABLE spaces (
  space_id TEXT PRIMARY KEY,
  token_hash_hex TEXT NOT NULL,
  salt_b64 TEXT NOT NULL,
  key_check_b64 TEXT NOT NULL,
  kdf TEXT NOT NULL,
  iterations INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE TABLE space_counters (
  space_id TEXT PRIMARY KEY,
  next_seq INTEGER NOT NULL
);

CREATE TABLE blobs (
  space_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  blob_id TEXT NOT NULL,
  v INTEGER NOT NULL,
  kind TEXT NOT NULL,
  device_id TEXT NOT NULL,
  nonce_b64 TEXT NOT NULL,
  ciphertext_b64 TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  PRIMARY KEY (space_id, seq),
  UNIQUE (space_id, blob_id),
  UNIQUE (space_id, nonce_b64)
);

CREATE TABLE rate_windows (
  bucket TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  hits INTEGER NOT NULL,
  PRIMARY KEY (bucket, window_start)
);
