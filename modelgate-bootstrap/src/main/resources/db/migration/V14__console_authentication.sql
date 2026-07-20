ALTER TABLE platform_user
    ADD COLUMN password_hash VARCHAR(100) NULL,
    ADD COLUMN password_change_required TINYINT NOT NULL DEFAULT 1,
    ADD COLUMN platform_admin TINYINT NOT NULL DEFAULT 0;

CREATE TABLE auth_session (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_auth_session_refresh_hash(refresh_token_hash),
    KEY idx_auth_session_user_active(user_id, revoked_at, expires_at)
);

-- The account is intentionally unusable until its BCrypt password hash is supplied by deployment configuration.
INSERT INTO platform_user(name, email, enabled, password_change_required, platform_admin, created_at, updated_at)
VALUES ('ModelGate Administrator', 'admin@modelgate.local', 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE platform_admin = 1;
