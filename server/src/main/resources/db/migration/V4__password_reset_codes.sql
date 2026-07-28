-- Forgot-password flow: each user may hold ONE active emailed reset code. Only the PBKDF2 hash of
-- the 6-digit code is stored; expiry is epoch millis (codes live minutes, no TZ semantics needed);
-- attempts counts wrong guesses so the route can invalidate the code before it can be brute-forced.

CREATE TABLE password_reset_codes (
    user_id VARCHAR(36) NOT NULL,
    code_hash VARCHAR(512) NOT NULL,
    expires_at_epoch_ms BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
