ALTER TABLE team
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1;

CREATE TABLE team_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_team_member_email(team_id, email),
    KEY idx_team_member_team(team_id),
    KEY idx_team_member_org(organization_id)
);

ALTER TABLE virtual_api_key
    ADD COLUMN owner_member_id BIGINT NULL,
    ADD COLUMN created_by_member_id BIGINT NULL,
    ADD KEY idx_virtual_api_key_owner_member(owner_member_id);

ALTER TABLE ai_request
    ADD COLUMN member_id BIGINT NULL,
    ADD KEY idx_ai_request_member_created(member_id, created_at);

ALTER TABLE usage_record
    ADD COLUMN member_id BIGINT NULL,
    ADD KEY idx_usage_member_occurred(member_id, occurred_at);

ALTER TABLE billing_record
    ADD COLUMN member_id BIGINT NULL,
    ADD KEY idx_billing_member_created(member_id, created_at);
