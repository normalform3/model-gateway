CREATE TABLE IF NOT EXISTS platform_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    email VARCHAR(255) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_platform_user_email(email)
);

INSERT INTO platform_user(name, email, enabled, created_at, updated_at)
SELECT m.name, m.email, m.enabled, m.created_at, m.created_at
FROM team_member m
ON DUPLICATE KEY UPDATE id = platform_user.id;

ALTER TABLE team_member
    ADD COLUMN user_id BIGINT NULL,
    ADD KEY idx_team_member_user(user_id);

UPDATE team_member m
JOIN platform_user u ON u.email = m.email
SET m.user_id = u.id;

ALTER TABLE team_member
    ADD UNIQUE KEY uk_team_member_user(user_id);

ALTER TABLE team
    ADD COLUMN owner_user_id BIGINT NULL,
    ADD KEY idx_team_owner_user(owner_user_id);

UPDATE team t
JOIN team_member m ON m.team_id = t.id AND m.role = 'OWNER'
SET t.owner_user_id = m.user_id;

CREATE TABLE provider_model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    input_price_per_million DECIMAL(18, 8) NOT NULL DEFAULT 0,
    output_price_per_million DECIMAL(18, 8) NOT NULL DEFAULT 0,
    currency VARCHAR(16) NOT NULL DEFAULT 'USD',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_provider_model_name(model_name),
    KEY idx_provider_model_provider(provider_id)
);

ALTER TABLE provider_credential
    DROP PRIMARY KEY,
    ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD COLUMN name VARCHAR(128) NOT NULL DEFAULT 'default',
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1,
    ADD PRIMARY KEY(id),
    ADD UNIQUE KEY uk_provider_credential_name(provider_id, name),
    ADD KEY idx_provider_credential_enabled(provider_id, enabled);

CREATE TABLE access_subject (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    reference_id BIGINT NULL,
    name VARCHAR(128) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_subject_reference(subject_type, reference_id),
    KEY idx_subject_team(team_id, subject_type)
);

CREATE TABLE subject_entitlement (
    subject_id BIGINT PRIMARY KEY,
    token_budget BIGINT NOT NULL,
    rpm INT NOT NULL,
    tpm INT NOT NULL,
    max_concurrency INT NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE subject_model_access (
    subject_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY(subject_id, model_name)
);

CREATE TABLE team_direct_model_access (
    team_id BIGINT NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY(team_id, model_name)
);

ALTER TABLE virtual_api_key
    ADD COLUMN subject_id BIGINT NULL,
    ADD KEY idx_virtual_api_key_subject(subject_id);

ALTER TABLE ai_request
    ADD COLUMN subject_id BIGINT NULL,
    ADD COLUMN subject_type VARCHAR(32) NULL,
    ADD COLUMN provider_credential_id BIGINT NULL,
    ADD KEY idx_ai_request_subject_created(subject_id, created_at);

ALTER TABLE usage_record
    ADD COLUMN subject_id BIGINT NULL,
    ADD COLUMN subject_type VARCHAR(32) NULL;

ALTER TABLE billing_record
    ADD COLUMN subject_id BIGINT NULL,
    ADD COLUMN subject_type VARCHAR(32) NULL;
