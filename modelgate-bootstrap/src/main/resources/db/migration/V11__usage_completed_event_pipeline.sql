ALTER TABLE team
    ADD COLUMN team_tpm INT NOT NULL DEFAULT 120000 AFTER team_rpm,
    ADD COLUMN key_concurrency INT NOT NULL DEFAULT 5 AFTER key_rpm;

ALTER TABLE model_entitlement_grant
    ADD COLUMN alert_remaining_percent TINYINT NULL AFTER quota_limit;

CREATE TABLE usage_event_outbox (
    event_id VARCHAR(96) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    lease_until DATETIME NULL,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME NOT NULL,
    sent_at DATETIME NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_usage_outbox_request(request_id),
    KEY idx_usage_outbox_dispatch(status, next_attempt_at, lease_until)
);

CREATE TABLE budget_alert (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(96) NOT NULL,
    grant_id BIGINT NOT NULL,
    cycle_started_at DATETIME NOT NULL,
    alert_remaining_percent TINYINT NOT NULL,
    remaining_tokens BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_budget_alert_grant_cycle_threshold(grant_id, cycle_started_at, alert_remaining_percent),
    KEY idx_budget_alert_event(event_id)
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(96) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    member_id BIGINT NULL,
    project_id BIGINT NULL,
    api_key_id BIGINT NOT NULL,
    requested_model VARCHAR(100) NOT NULL,
    actual_model VARCHAR(100) NULL,
    provider VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    total_tokens INT NOT NULL,
    duration_ms BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_audit_event(event_id),
    KEY idx_audit_team_occurred(team_id, occurred_at)
);
