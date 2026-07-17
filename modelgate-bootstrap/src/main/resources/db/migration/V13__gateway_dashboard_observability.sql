ALTER TABLE ai_request
    ADD COLUMN limit_dimension VARCHAR(32) NULL AFTER error_code,
    ADD KEY idx_ai_request_limit_dimension_created(limit_dimension, created_at);
