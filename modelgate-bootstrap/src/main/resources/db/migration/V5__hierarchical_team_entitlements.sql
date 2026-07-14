ALTER TABLE team
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

UPDATE team
SET status = CASE WHEN owner_user_id IS NULL THEN 'DRAFT' ELSE 'ACTIVE' END
WHERE status IS NULL OR status = 'ACTIVE';

CREATE TABLE team_entitlement_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    owner_member_id BIGINT NOT NULL,
    requested_models TEXT NOT NULL,
    requested_tokens BIGINT NOT NULL,
    purpose VARCHAR(512) NOT NULL,
    expires_at DATETIME NULL,
    status VARCHAR(32) NOT NULL,
    reviewer_note VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    reviewed_at DATETIME NULL,
    KEY idx_team_entitlement_request_team_status(team_id, status)
);

CREATE TABLE member_model_access (
    member_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY(member_id, model_name)
);

CREATE TABLE team_model_grant (
    team_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY(team_id, model_name)
);

INSERT IGNORE INTO team_model_grant(team_id, model_name, expires_at, created_at)
SELECT team_id, model_name, NULL, NOW()
FROM team_direct_model_access;

CREATE TABLE quota_transfer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    transfer_no VARCHAR(64) NOT NULL,
    team_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    from_account_id BIGINT NOT NULL,
    to_account_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_quota_transfer_no(transfer_no),
    KEY idx_quota_transfer_team_created(team_id, created_at),
    KEY idx_quota_transfer_member_created(member_id, created_at)
);

INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
SELECT 'MEMBER', m.id, 0, 0, 0, 0, NOW()
FROM team_member m;

INSERT IGNORE INTO member_model_access(member_id, model_name, created_at)
SELECT DISTINCT k.owner_member_id, ma.model_name, NOW()
FROM virtual_api_key k
JOIN team_direct_model_access ma ON ma.team_id = k.team_id
WHERE k.owner_member_id IS NOT NULL;

UPDATE virtual_api_key
SET enabled = 0
WHERE owner_member_id IS NULL;
