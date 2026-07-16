-- Keep the existing developer hierarchy intact and add an isolated application hierarchy.
UPDATE quota_account SET account_type = 'TEAM_DEVELOPMENT' WHERE account_type = 'TEAM';
UPDATE quota_account SET account_type = 'MEMBER_DEVELOPMENT' WHERE account_type = 'MEMBER';
ALTER TABLE quota_transfer MODIFY COLUMN member_id BIGINT NULL,
    ADD COLUMN pool_type VARCHAR(16) NOT NULL DEFAULT 'DEVELOPMENT' AFTER amount,
    ADD COLUMN project_id BIGINT NULL AFTER member_id;

ALTER TABLE model_entitlement_grant ADD COLUMN pool_type VARCHAR(16) NOT NULL DEFAULT 'DEVELOPMENT' AFTER model_name,
    ADD COLUMN project_id BIGINT NULL AFTER member_id,
    ADD KEY idx_model_entitlement_pool(team_id, pool_type, model_name, status);

CREATE TABLE project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    project_code VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_team_name(team_id, name),
    UNIQUE KEY uk_project_team_code(team_id, project_code),
    KEY idx_project_team_enabled(team_id, enabled)
);

CREATE TABLE project_model_access (
    project_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY(project_id, model_name)
);

CREATE TABLE project_model_entitlement (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    quota_mode VARCHAR(16) NOT NULL,
    quota_limit BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_model_entitlement(project_id, model_name),
    KEY idx_project_model_entitlement_status(project_id, status)
);

CREATE TABLE project_model_entitlement_usage (
    entitlement_id BIGINT NOT NULL,
    cycle_started_at DATETIME NOT NULL,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    frozen_tokens BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY(entitlement_id, cycle_started_at)
);

CREATE TABLE project_service_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_service_account(project_id, name)
);

INSERT IGNORE INTO quota_account(account_type, owner_id, available_tokens, frozen_tokens, consumed_tokens, version, updated_at)
SELECT 'TEAM_APPLICATION', id, 0, 0, 0, 0, NOW() FROM team;
-- PROJECT_APPLICATION accounts are created with each project; no historical project exists to backfill.

ALTER TABLE virtual_api_key ADD COLUMN credential_type VARCHAR(16) NOT NULL DEFAULT 'DEVELOPER' AFTER key_kind,
    ADD COLUMN project_id BIGINT NULL AFTER owner_member_id,
    ADD COLUMN service_account_id BIGINT NULL AFTER project_id,
    ADD KEY idx_virtual_api_key_project(project_id, credential_type);

ALTER TABLE ai_request ADD COLUMN credential_type VARCHAR(16) NOT NULL DEFAULT 'DEVELOPER',
    ADD COLUMN project_id BIGINT NULL, ADD COLUMN service_account_id BIGINT NULL;
ALTER TABLE usage_record ADD COLUMN credential_type VARCHAR(16) NOT NULL DEFAULT 'DEVELOPER',
    ADD COLUMN project_id BIGINT NULL, ADD COLUMN service_account_id BIGINT NULL;
ALTER TABLE billing_record ADD COLUMN credential_type VARCHAR(16) NOT NULL DEFAULT 'DEVELOPER',
    ADD COLUMN project_id BIGINT NULL, ADD COLUMN service_account_id BIGINT NULL;

CREATE TABLE provider_model_quota_pool (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_model_id BIGINT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_provider_model_pool(provider_model_id)
);
CREATE TABLE provider_model_pool_credential (
    pool_id BIGINT NOT NULL,
    credential_id BIGINT NOT NULL,
    available_tokens BIGINT NOT NULL DEFAULT 0,
    frozen_tokens BIGINT NOT NULL DEFAULT 0,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1,
    health_status VARCHAR(16) NOT NULL DEFAULT 'HEALTHY',
    cooldown_until DATETIME NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY(pool_id, credential_id)
);
