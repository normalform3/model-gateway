package com.modelgate.infrastructure.db;

import com.modelgate.common.api.AdminDtos.QuotaResponse;
import com.modelgate.common.api.AdminDtos.MemberQuotaResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaAccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public QuotaAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public QuotaResponse findTeamQuota(long teamId) {
        return jdbcTemplate.queryForObject("""
                        SELECT owner_id, available_tokens, frozen_tokens, consumed_tokens, updated_at
                        FROM quota_account
                        WHERE account_type = 'TEAM' AND owner_id = ?
                        """,
                (rs, rowNum) -> new QuotaResponse(
                        rs.getLong("owner_id"),
                        rs.getLong("available_tokens"),
                        rs.getLong("frozen_tokens"),
                        rs.getLong("consumed_tokens"),
                        JdbcTime.toOffsetDateTime(rs.getTimestamp("updated_at"))),
                teamId);
    }

    public MemberQuotaResponse findMemberQuota(long memberId) {
        return jdbcTemplate.queryForObject("""
                        SELECT owner_id, available_tokens, frozen_tokens, consumed_tokens, updated_at
                        FROM quota_account
                        WHERE account_type = 'MEMBER' AND owner_id = ?
                        """,
                (rs, rowNum) -> new MemberQuotaResponse(
                        rs.getLong("owner_id"),
                        rs.getLong("available_tokens"),
                        rs.getLong("frozen_tokens"),
                        rs.getLong("consumed_tokens"),
                        JdbcTime.toOffsetDateTime(rs.getTimestamp("updated_at"))),
                memberId);
    }

    public void syncRedisSettlement(long accountId, int estimatedTokens, int actualTokens) {
        long released = Math.max(0, estimatedTokens - actualTokens);
        jdbcTemplate.update("""
                        UPDATE quota_account
                        SET frozen_tokens = GREATEST(0, frozen_tokens - ?),
                            consumed_tokens = consumed_tokens + ?,
                            available_tokens = available_tokens + ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ?
                        """,
                estimatedTokens,
                actualTokens,
                released,
                JdbcTime.toTimestamp(java.time.OffsetDateTime.now()),
                accountId);
    }

    public void syncRedisRelease(long accountId, int estimatedTokens) {
        jdbcTemplate.update("""
                        UPDATE quota_account
                        SET frozen_tokens = GREATEST(0, frozen_tokens - ?),
                            available_tokens = available_tokens + ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ?
                        """,
                estimatedTokens,
                estimatedTokens,
                JdbcTime.toTimestamp(java.time.OffsetDateTime.now()),
                accountId);
    }
}
