CREATE TABLE IF NOT EXISTS model_entitlement_grant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    member_id BIGINT NULL,
    model_name VARCHAR(100) NOT NULL,
    quota_mode VARCHAR(16) NOT NULL,
    quota_limit BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    KEY idx_model_entitlement_team_model_status(team_id, model_name, status),
    KEY idx_model_entitlement_member_model_status(member_id, model_name, status)
);

CREATE TABLE IF NOT EXISTS model_entitlement_usage (
    grant_id BIGINT NOT NULL,
    cycle_started_at DATETIME NOT NULL,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    frozen_tokens BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (grant_id, cycle_started_at)
);

-- Existing balances were not model-aware. During this transition each enabled
-- model receives the legacy balance as a daily limit, as agreed for the first
-- periodic release. Team limits include member balances so migrated member
-- grants remain covered by their parent team entitlement.
INSERT INTO model_entitlement_grant(team_id, member_id, model_name, quota_mode, quota_limit, status, reason, created_at)
SELECT t.id, NULL, g.model_name, 'DAILY',
       COALESCE(tq.available_tokens, 0) + COALESCE((
           SELECT SUM(mq.available_tokens)
           FROM team_member m JOIN quota_account mq ON mq.account_type = 'MEMBER' AND mq.owner_id = m.id
           WHERE m.team_id = t.id
       ), 0),
       'ACTIVE', 'Migrated from legacy token balances', NOW()
FROM team t
JOIN team_model_grant g ON g.team_id = t.id AND (g.expires_at IS NULL OR g.expires_at > NOW())
LEFT JOIN quota_account tq ON tq.account_type = 'TEAM' AND tq.owner_id = t.id
WHERE t.enabled = 1
  AND NOT EXISTS (SELECT 1 FROM model_entitlement_grant existing WHERE existing.team_id = t.id AND existing.member_id IS NULL AND existing.model_name = g.model_name AND existing.status = 'ACTIVE');

INSERT INTO model_entitlement_grant(team_id, member_id, model_name, quota_mode, quota_limit, status, reason, created_at)
SELECT m.team_id, m.id, ma.model_name, 'DAILY', COALESCE(mq.available_tokens, 0),
       'ACTIVE', 'Migrated from legacy token balances', NOW()
FROM team_member m
JOIN member_model_access ma ON ma.member_id = m.id
LEFT JOIN quota_account mq ON mq.account_type = 'MEMBER' AND mq.owner_id = m.id
WHERE m.enabled = 1
  AND NOT EXISTS (SELECT 1 FROM model_entitlement_grant existing WHERE existing.team_id = m.team_id AND existing.member_id = m.id AND existing.model_name = ma.model_name AND existing.status = 'ACTIVE');
