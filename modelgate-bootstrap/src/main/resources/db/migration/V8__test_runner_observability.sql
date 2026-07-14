ALTER TABLE virtual_api_key
    ADD COLUMN key_kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD' AFTER key_hash,
    ADD COLUMN test_run_id VARCHAR(64) NULL AFTER key_kind,
    ADD KEY idx_virtual_api_key_test_run(test_run_id),
    ADD KEY idx_virtual_api_key_kind_enabled(key_kind, enabled);

ALTER TABLE ai_request
    ADD COLUMN test_run_id VARCHAR(64) NULL AFTER request_id,
    ADD KEY idx_ai_request_test_run(test_run_id, created_at);
