package com.modelgate.quota;

import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.ModelQuotaPolicy;
import com.modelgate.common.domain.QuotaReservation;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.event.QuotaSettlementSnapshot;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.AdminControlRepository;
import com.modelgate.infrastructure.db.ModelEntitlementRepository;
import com.modelgate.common.domain.GlobalRuntimePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Atomically applies rate/concurrency controls and both model entitlement levels. */
@Service
public class QuotaService {
    private static final String RESERVE_SCRIPT = """
            local now = tonumber(ARGV[1])
            local window_start = now - tonumber(ARGV[2])
            local request_id = ARGV[11]
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, window_start)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, window_start)
            local expired_tpm = redis.call('ZRANGEBYSCORE', KEYS[3], 0, window_start)
            for _, member in ipairs(expired_tpm) do
                local tokens = tonumber(string.match(member, '|(%d+)$')) or 0
                redis.call('DECRBY', KEYS[4], tokens)
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, window_start)
            redis.call('ZREMRANGEBYSCORE', KEYS[5], 0, window_start)
            redis.call('ZREMRANGEBYSCORE', KEYS[6], 0, now)
            redis.call('ZREMRANGEBYSCORE', KEYS[7], 0, now)
            redis.call('ZREMRANGEBYSCORE', KEYS[8], 0, now)
            redis.call('ZREMRANGEBYSCORE', KEYS[9], 0, now)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 'RATE_LIMIT_EXCEEDED|KEY_RPM' end
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 'RATE_LIMIT_EXCEEDED|TEAM_RPM' end
            local estimated = tonumber(ARGV[14])
            if tonumber(redis.call('GET', KEYS[4]) or '0') + estimated > tonumber(ARGV[5]) then return 'RATE_LIMIT_EXCEEDED|TEAM_TPM' end
            if redis.call('ZCARD', KEYS[5]) >= tonumber(ARGV[6]) then return 'RATE_LIMIT_EXCEEDED|GLOBAL_RPM' end
            if redis.call('ZCARD', KEYS[6]) >= tonumber(ARGV[7]) then return 'CONCURRENCY_LIMIT_EXCEEDED|KEY_CONCURRENCY' end
            if redis.call('ZCARD', KEYS[7]) >= tonumber(ARGV[8]) then return 'CONCURRENCY_LIMIT_EXCEEDED|TEAM_CONCURRENCY' end
            if redis.call('ZCARD', KEYS[8]) >= tonumber(ARGV[9]) then return 'CONCURRENCY_LIMIT_EXCEEDED|MODEL_CONCURRENCY' end
            if redis.call('ZCARD', KEYS[9]) >= tonumber(ARGV[10]) then return 'CONCURRENCY_LIMIT_EXCEEDED|GLOBAL_CONCURRENCY' end
            if ARGV[15] == '1' and tonumber(redis.call('HGET', KEYS[10], 'available_tokens') or '-1') < estimated then return 'QUOTA_INSUFFICIENT' end
            if ARGV[16] == '1' and tonumber(redis.call('HGET', KEYS[11], 'available_tokens') or '-1') < estimated then return 'QUOTA_INSUFFICIENT' end
            local expire_at = tonumber(ARGV[12])
            local ttl = tonumber(ARGV[13])
            redis.call('ZADD', KEYS[1], now, request_id .. ':key')
            redis.call('ZADD', KEYS[2], now, request_id .. ':team')
            redis.call('ZADD', KEYS[3], now, request_id .. '|' .. estimated)
            redis.call('INCRBY', KEYS[4], estimated)
            redis.call('ZADD', KEYS[5], now, request_id .. ':global-rpm')
            redis.call('ZADD', KEYS[6], expire_at, request_id .. ':key')
            redis.call('ZADD', KEYS[7], expire_at, request_id .. ':team')
            redis.call('ZADD', KEYS[8], expire_at, request_id .. ':model')
            redis.call('ZADD', KEYS[9], expire_at, request_id .. ':global')
            for i = 1, 9 do redis.call('EXPIRE', KEYS[i], ttl) end
            if ARGV[15] == '1' then redis.call('HINCRBY', KEYS[10], 'available_tokens', -estimated); redis.call('HINCRBY', KEYS[10], 'frozen_tokens', estimated) end
            if ARGV[16] == '1' then redis.call('HINCRBY', KEYS[11], 'available_tokens', -estimated); redis.call('HINCRBY', KEYS[11], 'frozen_tokens', estimated) end
            return 'OK'
            """;
    private static final String SETTLE_SCRIPT = """
            local estimated = tonumber(ARGV[2]); local actual = tonumber(ARGV[3]); local refund = math.max(0, estimated - actual)
            redis.call('ZREM', KEYS[1], ARGV[1] .. ':key'); redis.call('ZREM', KEYS[2], ARGV[1] .. ':team'); redis.call('ZREM', KEYS[3], ARGV[1] .. ':model'); redis.call('ZREM', KEYS[4], ARGV[1] .. ':global')
            if ARGV[4] == '1' and redis.call('EXISTS', KEYS[5]) == 1 then redis.call('HINCRBY', KEYS[5], 'frozen_tokens', -estimated); redis.call('HINCRBY', KEYS[5], 'consumed_tokens', actual); redis.call('HINCRBY', KEYS[5], 'available_tokens', refund) end
            if ARGV[5] == '1' and redis.call('EXISTS', KEYS[6]) == 1 then redis.call('HINCRBY', KEYS[6], 'frozen_tokens', -estimated); redis.call('HINCRBY', KEYS[6], 'consumed_tokens', actual); redis.call('HINCRBY', KEYS[6], 'available_tokens', refund) end
            return 'OK'
            """;
    private static final String RELEASE_SCRIPT = """
            local estimated = tonumber(ARGV[2])
            redis.call('ZREM', KEYS[1], ARGV[1] .. ':key'); redis.call('ZREM', KEYS[2], ARGV[1] .. ':team'); redis.call('ZREM', KEYS[3], ARGV[1] .. ':model'); redis.call('ZREM', KEYS[4], ARGV[1] .. ':global')
            if ARGV[3] == '1' and redis.call('EXISTS', KEYS[5]) == 1 then redis.call('HINCRBY', KEYS[5], 'frozen_tokens', -estimated); redis.call('HINCRBY', KEYS[5], 'available_tokens', estimated) end
            if ARGV[4] == '1' and redis.call('EXISTS', KEYS[6]) == 1 then redis.call('HINCRBY', KEYS[6], 'frozen_tokens', -estimated); redis.call('HINCRBY', KEYS[6], 'available_tokens', estimated) end
            return 'OK'
            """;
    private static final String REFRESH_QUOTA_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            local consumed = tonumber(redis.call('HGET', KEYS[1], 'consumed_tokens') or '0')
            local frozen = tonumber(redis.call('HGET', KEYS[1], 'frozen_tokens') or '0')
            redis.call('HSET', KEYS[1], 'available_tokens', math.max(0, tonumber(ARGV[1]) - consumed - frozen))
            return 1
            """;

    private final StringRedisTemplate redis;
    private final ModelEntitlementRepository entitlements;
    private final AdminControlRepository controls;
    private final long requestTtlSeconds;
    private volatile GlobalRuntimePolicy cachedGlobalPolicy = new GlobalRuntimePolicy(10_000, 1_000);
    private volatile long globalPolicyLoadedAt;

    public QuotaService(StringRedisTemplate redis, ModelEntitlementRepository entitlements, AdminControlRepository controls,
                        @Value("${modelgate.quota.request-ttl-seconds:300}") long requestTtlSeconds) {
        this.redis = redis; this.entitlements = entitlements; this.controls = controls; this.requestTtlSeconds = requestTtlSeconds;
    }

    public QuotaReservation reserve(ApiKeyContext context, String logicalModel, String requestId, int inputTokens, int maxOutputTokens) {
        ModelQuotaPolicy member = context.memberQuota(logicalModel);
        ModelQuotaPolicy team = context.teamQuota(logicalModel);
        if (member == null || team == null) throw new ModelGateException(ErrorCode.MODEL_NOT_ALLOWED, "The model no longer has a current entitlement.", requestId);
        String cycle = ModelEntitlementRepository.cycleStart(member.mode());
        String pool = context.credentialType().name().toLowerCase();
        ensureQuota(member, cycle, pool); ensureQuota(team, cycle, pool);
        int estimated = Math.addExact(inputTokens, maxOutputTokens); long now = Instant.now().toEpochMilli(); long expires = now + requestTtlSeconds * 1000;
        GlobalRuntimePolicy global = globalPolicy();
        String result = redis.execute(new DefaultRedisScript<>(RESERVE_SCRIPT, String.class), List.of(
                        "rate:key:" + context.keyId() + ":rpm", "rate:team:" + context.teamId() + ":rpm", "rate:team:" + context.teamId() + ":tpm", "rate:team:" + context.teamId() + ":tpm:total",
                        "rate:global:rpm", "concurrency:key:" + context.keyId(), "concurrency:team:" + context.teamId(), "concurrency:model:" + logicalModel, "concurrency:global",
                        quotaKey(member, cycle, pool), quotaKey(team, cycle, pool)),
                Long.toString(now), "60000", Integer.toString(context.rateLimitPolicy().keyRpm()), Integer.toString(context.rateLimitPolicy().teamRpm()), Integer.toString(context.rateLimitPolicy().teamTpm()), Integer.toString(global.globalRpm()),
                Integer.toString(context.rateLimitPolicy().keyConcurrency()), Integer.toString(context.rateLimitPolicy().teamConcurrency()), Integer.toString(context.rateLimitPolicy().modelConcurrency()), Integer.toString(global.globalConcurrency()), requestId,
                Long.toString(expires), Long.toString(requestTtlSeconds + 60), Integer.toString(estimated), member.limited() ? "1" : "0", team.limited() ? "1" : "0");
        if (!"OK".equals(result)) {
            String[] parts = (result == null ? ErrorCode.INTERNAL_ERROR.name() : result).split("\\|", 2);
            ErrorCode code = ErrorCode.valueOf(parts[0]);
            throw new ModelGateException(code, code.defaultMessage(), requestId, parts.length == 2 ? parts[1] : null);
        }
        return new QuotaReservation(requestId, estimated, inputTokens, member, team, cycle);
    }

    public List<QuotaSettlementSnapshot> settle(ApiKeyContext context, String logicalModel, QuotaReservation reservation, int actualTokens) {
        redis.execute(new DefaultRedisScript<>(SETTLE_SCRIPT, String.class), settleKeys(context, logicalModel, reservation), reservation.requestId(), Integer.toString(reservation.estimatedTokens()), Integer.toString(actualTokens), reservation.memberQuota().limited() ? "1" : "0", reservation.teamQuota().limited() ? "1" : "0");
        return snapshots(context, reservation, actualTokens, false);
    }

    public List<QuotaSettlementSnapshot> release(ApiKeyContext context, String logicalModel, QuotaReservation reservation) {
        redis.execute(new DefaultRedisScript<>(RELEASE_SCRIPT, String.class), settleKeys(context, logicalModel, reservation), reservation.requestId(), Integer.toString(reservation.estimatedTokens()), reservation.memberQuota().limited() ? "1" : "0", reservation.teamQuota().limited() ? "1" : "0");
        return snapshots(context, reservation, 0, true);
    }

    /** Refreshes the current policy without dropping frozen tokens held by in-flight requests. */
    public void refreshEntitlement(ModelQuotaPolicy policy) {
        if (!policy.limited()) {
            evictEntitlement(policy.grantId());
            return;
        }
        String cycle = ModelEntitlementRepository.cycleStart(policy.mode());
        redis.execute(new DefaultRedisScript<>(REFRESH_QUOTA_SCRIPT, Long.class), List.of(quotaKey(policy, cycle, "development")), Long.toString(policy.limit()));
    }

    /** Both periodic keys are removed because a policy may have changed between daily and weekly. */
    public void evictEntitlements(Iterable<Long> grantIds) {
        for (Long grantId : grantIds) {
            if (grantId != null) evictEntitlement(grantId);
        }
    }

    private List<String> settleKeys(ApiKeyContext context, String logicalModel, QuotaReservation reservation) { String pool=context.credentialType().name().toLowerCase(); return List.of("concurrency:key:" + context.keyId(), "concurrency:team:" + context.teamId(), "concurrency:model:" + logicalModel, "concurrency:global", quotaKey(reservation.memberQuota(), reservation.cycleStart(), pool), quotaKey(reservation.teamQuota(), reservation.cycleStart(), pool)); }

    private List<QuotaSettlementSnapshot> snapshots(ApiKeyContext context, QuotaReservation reservation, int actualTokens, boolean released) {
        String pool = context.credentialType().name().toLowerCase();
        List<QuotaSettlementSnapshot> result = new ArrayList<>();
        result.add(snapshot(reservation.memberQuota(), reservation, actualTokens, released, quotaKey(reservation.memberQuota(), reservation.cycleStart(), pool)));
        if (reservation.teamQuota().grantId() != reservation.memberQuota().grantId()) result.add(snapshot(reservation.teamQuota(), reservation, actualTokens, released, quotaKey(reservation.teamQuota(), reservation.cycleStart(), pool)));
        return result;
    }

    private QuotaSettlementSnapshot snapshot(ModelQuotaPolicy policy, QuotaReservation reservation, int actualTokens, boolean released, String key) {
        Object available = redis.opsForHash().get(key, "available_tokens");
        long remaining = available == null ? 0 : Long.parseLong(available.toString());
        return new QuotaSettlementSnapshot(policy.grantId(), policy.modelName(), policy.mode().name(), policy.limit(), policy.alertRemainingPercent(), OffsetDateTime.parse(reservation.cycleStart()), reservation.estimatedTokens(), actualTokens, remaining, released);
    }
    private void ensureQuota(ModelQuotaPolicy policy, String cycle, String pool) {
        if (!policy.limited() || Boolean.TRUE.equals(redis.hasKey(quotaKey(policy, cycle, pool)))) return;
        ModelEntitlementRepository.UsageSnapshot usage = entitlements.usage(policy, cycle);
        long available = Math.max(0, policy.limit() - usage.consumedTokens() - usage.frozenTokens());
        redis.opsForHash().put(quotaKey(policy, cycle, pool), "available_tokens", Long.toString(available));
        redis.opsForHash().put(quotaKey(policy, cycle, pool), "frozen_tokens", Long.toString(usage.frozenTokens()));
        redis.opsForHash().put(quotaKey(policy, cycle, pool), "consumed_tokens", Long.toString(usage.consumedTokens()));
    }
    private void evictEntitlement(long grantId) {
        redis.delete(List.of(
                quotaKey(grantId, ModelEntitlementRepository.cycleStart(com.modelgate.common.domain.QuotaMode.DAILY)),
                quotaKey(grantId, ModelEntitlementRepository.cycleStart(com.modelgate.common.domain.QuotaMode.WEEKLY))));
    }
    private String quotaKey(ModelQuotaPolicy policy, String cycle, String pool) { return "quota:" + pool + ":entitlement:" + policy.grantId() + ":" + cycle.replace(':', '_'); }
    private String quotaKey(long grantId, String cycle) { return "quota:entitlement:" + grantId + ":" + cycle.replace(':', '_'); }
    private GlobalRuntimePolicy globalPolicy() { long now = System.currentTimeMillis(); if (now - globalPolicyLoadedAt < 5_000) return cachedGlobalPolicy; synchronized (this) { if (now - globalPolicyLoadedAt >= 5_000) { cachedGlobalPolicy = controls.globalRuntimePolicy(); globalPolicyLoadedAt = now; } return cachedGlobalPolicy; } }
}
