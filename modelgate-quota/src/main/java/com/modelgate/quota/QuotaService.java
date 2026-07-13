package com.modelgate.quota;

import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.QuotaReservation;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.QuotaAccountRepository;
import com.modelgate.infrastructure.db.AdminControlRepository;
import com.modelgate.common.domain.GlobalRuntimePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class QuotaService {
    private static final String RESERVE_SCRIPT = """
            local now = tonumber(ARGV[1])
            local window_start = now - tonumber(ARGV[2])
            local request_id = ARGV[9]
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, window_start)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, window_start)
            redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, window_start)
            redis.call('ZREMRANGEBYSCORE', KEYS[4], 0, now)
            redis.call('ZREMRANGEBYSCORE', KEYS[5], 0, now)
            redis.call('ZREMRANGEBYSCORE', KEYS[6], 0, now)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 'RATE_LIMIT_EXCEEDED' end
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 'RATE_LIMIT_EXCEEDED' end
            if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[5]) then return 'RATE_LIMIT_EXCEEDED' end
            if redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[6]) then return 'CONCURRENCY_LIMIT_EXCEEDED' end
            if redis.call('ZCARD', KEYS[5]) >= tonumber(ARGV[7]) then return 'CONCURRENCY_LIMIT_EXCEEDED' end
            if redis.call('ZCARD', KEYS[6]) >= tonumber(ARGV[8]) then return 'CONCURRENCY_LIMIT_EXCEEDED' end
            local estimated = tonumber(ARGV[12])
            local available = tonumber(redis.call('HGET', KEYS[7], 'available_tokens') or '-1')
            if available < estimated then return 'QUOTA_INSUFFICIENT' end
            local expire_at = tonumber(ARGV[10])
            local ttl = tonumber(ARGV[11])
            redis.call('ZADD', KEYS[1], now, request_id .. ':key')
            redis.call('ZADD', KEYS[2], now, request_id .. ':team')
            redis.call('ZADD', KEYS[3], now, request_id .. ':global')
            redis.call('ZADD', KEYS[4], expire_at, request_id .. ':team')
            redis.call('ZADD', KEYS[5], expire_at, request_id .. ':model')
            redis.call('ZADD', KEYS[6], expire_at, request_id .. ':global')
            for i = 1, 6 do redis.call('EXPIRE', KEYS[i], ttl) end
            redis.call('HINCRBY', KEYS[7], 'available_tokens', -estimated)
            redis.call('HINCRBY', KEYS[7], 'frozen_tokens', estimated)
            return 'OK'
            """;

    private static final String SETTLE_SCRIPT = """
            local estimated = tonumber(ARGV[2])
            local actual = tonumber(ARGV[3])
            local refund = estimated - actual
            if refund < 0 then refund = 0 end
            redis.call('ZREM', KEYS[1], ARGV[1] .. ':team')
            redis.call('ZREM', KEYS[2], ARGV[1] .. ':model')
            redis.call('ZREM', KEYS[3], ARGV[1] .. ':global')
            redis.call('HINCRBY', KEYS[4], 'frozen_tokens', -estimated)
            redis.call('HINCRBY', KEYS[4], 'consumed_tokens', actual)
            redis.call('HINCRBY', KEYS[4], 'available_tokens', refund)
            return 'OK'
            """;

    private static final String RELEASE_SCRIPT = """
            local estimated = tonumber(ARGV[2])
            redis.call('ZREM', KEYS[1], ARGV[1] .. ':team')
            redis.call('ZREM', KEYS[2], ARGV[1] .. ':model')
            redis.call('ZREM', KEYS[3], ARGV[1] .. ':global')
            redis.call('HINCRBY', KEYS[4], 'frozen_tokens', -estimated)
            redis.call('HINCRBY', KEYS[4], 'available_tokens', estimated)
            return 'OK'
            """;

    private final StringRedisTemplate redisTemplate;
    private final QuotaAccountRepository quotaAccountRepository;
    private final AdminControlRepository adminControlRepository;
    private final long requestTtlSeconds;
    private volatile GlobalRuntimePolicy cachedGlobalPolicy = new GlobalRuntimePolicy(10_000, 1_000);
    private volatile long globalPolicyLoadedAt;

    public QuotaService(
            StringRedisTemplate redisTemplate,
            QuotaAccountRepository quotaAccountRepository,
            AdminControlRepository adminControlRepository,
            @Value("${modelgate.quota.request-ttl-seconds:300}") long requestTtlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.quotaAccountRepository = quotaAccountRepository;
        this.adminControlRepository = adminControlRepository;
        this.requestTtlSeconds = requestTtlSeconds;
    }

    public QuotaReservation reserve(ApiKeyContext context, RouteTarget target, String requestId, int inputTokens, int maxOutputTokens) {
        ensureQuotaHash(context);
        int estimated = Math.addExact(inputTokens, maxOutputTokens);
        long now = Instant.now().toEpochMilli();
        long expireAt = now + requestTtlSeconds * 1000;
        List<String> keys = List.of(
                "rate:key:" + context.keyId() + ":rpm",
                "rate:team:" + context.teamId() + ":rpm",
                "rate:global:rpm",
                "concurrency:team:" + context.teamId(),
                "concurrency:model:" + target.deploymentId(),
                "concurrency:global",
                quotaKey(context.quotaAccountId()));
        GlobalRuntimePolicy globalPolicy = globalPolicy();

        String result = redisTemplate.execute(new DefaultRedisScript<>(RESERVE_SCRIPT, String.class), keys,
                Long.toString(now),
                "60000",
                Integer.toString(context.rateLimitPolicy().keyRpm()),
                Integer.toString(context.rateLimitPolicy().teamRpm()),
                Integer.toString(globalPolicy.globalRpm()),
                Integer.toString(context.rateLimitPolicy().teamConcurrency()),
                Integer.toString(context.rateLimitPolicy().modelConcurrency()),
                Integer.toString(globalPolicy.globalConcurrency()),
                requestId,
                Long.toString(expireAt),
                Long.toString(requestTtlSeconds + 60),
                Integer.toString(estimated));

        if (!"OK".equals(result)) {
            ErrorCode errorCode = ErrorCode.valueOf(result == null ? ErrorCode.INTERNAL_ERROR.name() : result);
            throw new ModelGateException(errorCode, errorCode.defaultMessage(), requestId);
        }
        return new QuotaReservation(context.quotaAccountId(), requestId, estimated, inputTokens);
    }

    public void settle(ApiKeyContext context, RouteTarget target, QuotaReservation reservation, int actualTokens) {
        redisTemplate.execute(new DefaultRedisScript<>(SETTLE_SCRIPT, String.class),
                List.of("concurrency:team:" + context.teamId(), "concurrency:model:" + target.deploymentId(), "concurrency:global", quotaKey(reservation.accountId())),
                reservation.requestId(),
                Integer.toString(reservation.estimatedTokens()),
                Integer.toString(actualTokens));
        quotaAccountRepository.syncRedisSettlement(reservation.accountId(), reservation.estimatedTokens(), actualTokens);
    }

    public void release(ApiKeyContext context, RouteTarget target, QuotaReservation reservation) {
        redisTemplate.execute(new DefaultRedisScript<>(RELEASE_SCRIPT, String.class),
                List.of("concurrency:team:" + context.teamId(), "concurrency:model:" + target.deploymentId(), "concurrency:global", quotaKey(reservation.accountId())),
                reservation.requestId(),
                Integer.toString(reservation.estimatedTokens()));
        quotaAccountRepository.syncRedisRelease(reservation.accountId(), reservation.estimatedTokens());
    }

    private void ensureQuotaHash(ApiKeyContext context) {
        String key = quotaKey(context.quotaAccountId());
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        redisTemplate.opsForHash().put(key, "available_tokens", Long.toString(context.budgetPolicy().maxAvailableTokens()));
        redisTemplate.opsForHash().put(key, "frozen_tokens", "0");
        redisTemplate.opsForHash().put(key, "consumed_tokens", "0");
    }

    private String quotaKey(long accountId) {
        return "quota:account:" + accountId;
    }

    private GlobalRuntimePolicy globalPolicy() {
        long now = System.currentTimeMillis();
        if (now - globalPolicyLoadedAt < 5_000) return cachedGlobalPolicy;
        synchronized (this) {
            if (now - globalPolicyLoadedAt >= 5_000) {
                cachedGlobalPolicy = adminControlRepository.globalRuntimePolicy();
                globalPolicyLoadedAt = now;
            }
            return cachedGlobalPolicy;
        }
    }
}
