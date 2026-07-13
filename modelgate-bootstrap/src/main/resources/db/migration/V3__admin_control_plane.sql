ALTER TABLE provider
    ADD COLUMN base_url VARCHAR(512) NULL;

CREATE TABLE provider_credential (
    provider_id BIGINT PRIMARY KEY,
    api_key_ciphertext TEXT NOT NULL,
    key_version VARCHAR(32) NOT NULL,
    key_last_four VARCHAR(4) NOT NULL,
    updated_at DATETIME NOT NULL
);

ALTER TABLE model_deployment
    ADD COLUMN input_price_per_million DECIMAL(18, 8) NOT NULL DEFAULT 0,
    ADD COLUMN output_price_per_million DECIMAL(18, 8) NOT NULL DEFAULT 0,
    ADD COLUMN currency VARCHAR(16) NOT NULL DEFAULT 'USD';

ALTER TABLE billing_record
    ADD COLUMN input_unit_price DECIMAL(18, 8) NOT NULL DEFAULT 0,
    ADD COLUMN output_unit_price DECIMAL(18, 8) NOT NULL DEFAULT 0;

CREATE TABLE team_model_access (
    team_id BIGINT NOT NULL,
    logical_model VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (team_id, logical_model)
);

CREATE TABLE global_runtime_policy (
    id TINYINT PRIMARY KEY,
    global_rpm INT NOT NULL,
    global_concurrency INT NOT NULL,
    updated_at DATETIME NOT NULL
);

INSERT INTO global_runtime_policy(id, global_rpm, global_concurrency, updated_at)
VALUES (1, 10000, 1000, NOW())
ON DUPLICATE KEY UPDATE id = id;

CREATE INDEX idx_virtual_api_key_team_app_enabled ON virtual_api_key(team_id, application_id, enabled);
