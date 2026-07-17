package com.modelgate.infrastructure.db;

import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.RequestStatus;
import com.modelgate.common.domain.RouteTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public class RequestRepository {
    private final JdbcTemplate jdbcTemplate;

    public RequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertStarted(
            String requestId,
            ApiKeyContext context,
            String requestedModel,
            RouteTarget target,
            boolean stream,
            int inputTokens,
            int estimatedTokens,
            String testRunId
    ) {
        jdbcTemplate.update("""
                        INSERT INTO ai_request(
                            request_id, test_run_id, organization_id, team_id, api_key_id,
                            member_id, credential_type, project_id, service_account_id, requested_model, actual_provider, actual_model, stream_enabled,
                            input_tokens, estimated_tokens, status, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requestId,
                testRunId,
                context.organizationId(),
                context.teamId(),
                context.keyId(),
                context.memberId(),
                context.credentialType().name(),
                context.projectId(),
                context.serviceAccountId(),
                requestedModel,
                target.provider(),
                target.actualModel(),
                stream ? 1 : 0,
                inputTokens,
                estimatedTokens,
                RequestStatus.STARTED.name(),
                JdbcTime.toTimestamp(OffsetDateTime.now()));
    }

    public void complete(String requestId, RequestStatus status, int inputTokens, int outputTokens, long durationMs, Long firstTokenMs, String errorCode) {
        jdbcTemplate.update("""
                        UPDATE ai_request
                        SET status = ?, input_tokens = ?, output_tokens = ?, duration_ms = ?, first_token_ms = ?,
                            error_code = ?, completed_at = ?
                        WHERE request_id = ?
                        """,
                status.name(),
                inputTokens,
                outputTokens,
                durationMs,
                firstTokenMs,
                errorCode,
                JdbcTime.toTimestamp(OffsetDateTime.now()),
                requestId);
    }

    /** Records a request rejected before a route/provider is selected, after authentication and model authorization succeeded. */
    public void insertRejected(
            String requestId,
            ApiKeyContext context,
            String requestedModel,
            boolean stream,
            int inputTokens,
            int estimatedTokens,
            String errorCode,
            String limitDimension
    ) {
        java.sql.Timestamp now = JdbcTime.toTimestamp(OffsetDateTime.now());
        jdbcTemplate.update("""
                        INSERT INTO ai_request(
                            request_id, organization_id, team_id, api_key_id, member_id, credential_type, project_id, service_account_id,
                            requested_model, stream_enabled, input_tokens, estimated_tokens, status, error_code, limit_dimension, created_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requestId, context.organizationId(), context.teamId(), context.keyId(), context.memberId(), context.credentialType().name(),
                context.projectId(), context.serviceAccountId(), requestedModel, stream ? 1 : 0, inputTokens, estimatedTokens,
                RequestStatus.FAILED.name(), errorCode, limitDimension, now, now);
    }
}
