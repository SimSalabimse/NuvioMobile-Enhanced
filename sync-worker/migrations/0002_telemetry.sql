-- Behaviour batches. The device column is the SHA-256 of the bearer, never the token.
-- Rows older than 30 days are deleted on the next accepted write.

CREATE TABLE telemetry_batches (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  app_build TEXT NOT NULL,
  kind TEXT NOT NULL,
  body BLOB NOT NULL
);

CREATE INDEX telemetry_batches_received ON telemetry_batches (received_at);
