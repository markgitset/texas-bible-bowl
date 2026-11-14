-- Self-service account deletion (POST /auth/delete-account). Rows elsewhere point at users with
-- NOT NULL, ON DELETE RESTRICT keys (question authors, score entry, congregation creators…), so a
-- deleted account stays as a tombstone: its email, name, password, roles and person link are
-- scrubbed and this column records when. Non-null = the account no longer exists to the app.
ALTER TABLE users ADD COLUMN deleted_at_epoch_ms BIGINT;
