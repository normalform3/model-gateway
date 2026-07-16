ALTER TABLE billing_record
    ADD KEY idx_billing_created_at(created_at),
    ADD KEY idx_billing_team_created(team_id, created_at),
    ADD KEY idx_billing_project_created(project_id, created_at),
    ADD KEY idx_billing_provider_model_created(provider, model, created_at);
