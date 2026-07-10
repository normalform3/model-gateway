CREATE TABLE IF NOT EXISTS organization (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_organization_name(name)
);

CREATE TABLE IF NOT EXISTS team (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    key_rpm INT NOT NULL DEFAULT 60,
    team_rpm INT NOT NULL DEFAULT 600,
    team_concurrency INT NOT NULL DEFAULT 20,
    model_concurrency INT NOT NULL DEFAULT 50,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_team_org_name(organization_id, name)
);

CREATE TABLE IF NOT EXISTS application (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_application_team_name(team_id, name)
);

CREATE TABLE IF NOT EXISTS virtual_api_key (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    key_prefix VARCHAR(64) NOT NULL,
    key_hash VARCHAR(128) NOT NULL,
    allowed_models TEXT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_virtual_api_key_hash(key_hash),
    KEY idx_virtual_api_key_app(application_id)
);

CREATE TABLE IF NOT EXISTS provider (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_provider_name(name)
);

CREATE TABLE IF NOT EXISTS model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    logical_model VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_model_logical(logical_model)
);

CREATE TABLE IF NOT EXISTS model_deployment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    actual_model VARCHAR(100) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_deployment_provider_name(provider_id, name)
);

CREATE TABLE IF NOT EXISTS model_route (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    logical_model VARCHAR(100) NOT NULL,
    strategy VARCHAR(32) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_route_logical(logical_model)
);

CREATE TABLE IF NOT EXISTS route_target (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_id BIGINT NOT NULL,
    deployment_id BIGINT NOT NULL,
    weight INT NOT NULL DEFAULT 100,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_route_deployment(route_id, deployment_id)
);

CREATE TABLE IF NOT EXISTS quota_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_type VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL,
    available_tokens BIGINT NOT NULL,
    frozen_tokens BIGINT NOT NULL DEFAULT 0,
    consumed_tokens BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_quota_owner(account_type, owner_id)
);

CREATE TABLE IF NOT EXISTS ai_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    api_key_id BIGINT NOT NULL,
    requested_model VARCHAR(100) NOT NULL,
    actual_provider VARCHAR(50),
    actual_model VARCHAR(100),
    stream_enabled TINYINT NOT NULL,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    estimated_tokens INT DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    duration_ms BIGINT,
    first_token_ms BIGINT,
    created_at DATETIME NOT NULL,
    completed_at DATETIME,
    UNIQUE KEY uk_request_id(request_id),
    KEY idx_team_created(team_id, created_at),
    KEY idx_application_created(application_id, created_at)
);

CREATE TABLE IF NOT EXISTS usage_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    api_key_id BIGINT NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    total_tokens INT NOT NULL,
    usage_source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    occurred_at DATETIME NOT NULL,
    UNIQUE KEY uk_usage_event(event_id),
    UNIQUE KEY uk_usage_request(request_id)
);

CREATE TABLE IF NOT EXISTS billing_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    application_id BIGINT NOT NULL,
    api_key_id BIGINT NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    unit_price DECIMAL(18, 8) NOT NULL,
    amount DECIMAL(18, 8) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    billing_type VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_request_billing(request_id, billing_type)
);

CREATE TABLE IF NOT EXISTS quota_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    transaction_no VARCHAR(64) NOT NULL,
    account_id BIGINT NOT NULL,
    request_id VARCHAR(64),
    transaction_type VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    event_id VARCHAR(64),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_transaction_no(transaction_no),
    UNIQUE KEY uk_event_type(event_id, transaction_type),
    KEY idx_account_created(account_id, created_at)
);

CREATE TABLE IF NOT EXISTS mq_consume_record (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    consumed_at DATETIME NOT NULL,
    PRIMARY KEY (event_id, consumer_group)
);
