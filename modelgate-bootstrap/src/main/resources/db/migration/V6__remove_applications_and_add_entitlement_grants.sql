-- A virtual key is now owned by one team member, not by an application.
-- Existing application-bound keys are deliberately disabled; developers reissue
-- their own key from the console so that plaintext is never created in SQL.
UPDATE virtual_api_key SET enabled = 0 WHERE enabled = 1;

ALTER TABLE team_entitlement_request
    ADD COLUMN granted_models TEXT NULL,
    ADD COLUMN granted_tokens BIGINT NULL;

DROP INDEX idx_virtual_api_key_team_app_enabled ON virtual_api_key;
DROP INDEX idx_virtual_api_key_app ON virtual_api_key;
DROP INDEX idx_application_created ON ai_request;

ALTER TABLE virtual_api_key
    DROP COLUMN application_id,
    DROP COLUMN allowed_models;

ALTER TABLE ai_request DROP COLUMN application_id;
ALTER TABLE usage_record DROP COLUMN application_id;
ALTER TABLE billing_record DROP COLUMN application_id;

DROP TABLE application;
