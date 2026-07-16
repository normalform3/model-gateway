package com.modelgate.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.AdminRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VirtualKeyService implements AuthCacheInvalidationHandler {
    private static final Logger log = LoggerFactory.getLogger(VirtualKeyService.class);
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    private final AdminRepository adminRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthCacheInvalidationPublisher invalidationPublisher;
    private final Cache<String, ApiKeyContext> localCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofMinutes(2))
            .build();

    public VirtualKeyService(
            AdminRepository adminRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AuthCacheInvalidationPublisher invalidationPublisher
    ) {
        this.adminRepository = adminRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.invalidationPublisher = invalidationPublisher;
    }

    /** A member owns at most one active key; its model set is resolved dynamically. */
    public CreateApiKeyResponse provisionMemberKey(long teamId, long memberId) {
        Optional<Long> existing = adminRepository.findActiveMemberKeyId(teamId, memberId);
        if (existing.isPresent()) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST,
                    "An active key already exists for this member. Rotate it instead.");
        }
        String apiKey = "mg-key-" + randomToken();
        String prefix = apiKey.substring(0, Math.min(apiKey.length(), 18));
        long keyId = adminRepository.insertSystemMemberApiKey(teamId, memberId, prefix, sha256(apiKey));
        return new CreateApiKeyResponse(keyId, prefix, apiKey, true);
    }

    public void invalidateMember(long memberId) {
        scheduleInvalidation(AuthCacheInvalidationEvent.member(memberId));
    }

    public void invalidateKeyHashes(Iterable<String> hashes) {
        List<String> copiedHashes = copyHashes(hashes);
        if (copiedHashes.isEmpty()) return;
        scheduleInvalidation(AuthCacheInvalidationEvent.keyHashes(copiedHashes));
    }

    public void invalidateTeam(long teamId) {
        scheduleInvalidation(AuthCacheInvalidationEvent.team(teamId));
    }

    public void invalidateQuotaAccount(long accountId) {
        try { redisTemplate.delete("quota:account:" + accountId); } catch (RuntimeException ignored) { }
    }

    public void disable(long keyId) {
        if (!adminRepository.disableApiKey(keyId)) {
            throw new ModelGateException(ErrorCode.INVALID_API_KEY, "API key was not found.");
        }
        adminRepository.findKeyHash(keyId).ifPresent(hash -> {
            invalidateKeyHashes(List.of(hash));
        });
    }

    public ApiKeyContext authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ModelGateException(ErrorCode.INVALID_API_KEY);
        }
        String apiKey = authorizationHeader.substring("Bearer ".length()).trim();
        if (!apiKey.startsWith("mg-key-")) {
            throw new ModelGateException(ErrorCode.INVALID_API_KEY);
        }
        String hash = sha256(apiKey);
        ApiKeyContext cached = localCache.getIfPresent(hash);
        if (cached != null) {
            return validate(cached);
        }

        Optional<ApiKeyContext> redisContext = readRedis(hash);
        if (redisContext.isPresent()) {
            ApiKeyContext context = validate(redisContext.get());
            localCache.put(hash, context);
            return context;
        }

        ApiKeyContext context = adminRepository.findApiKeyContextByHash(hash)
                .orElseThrow(() -> new ModelGateException(ErrorCode.INVALID_API_KEY));
        validate(context);
        localCache.put(hash, context);
        writeRedis(hash, context);
        return context;
    }

    public void assertModelAllowed(ApiKeyContext context, String model) {
        if (!context.modelAllowed(model)) {
            throw new ModelGateException(ErrorCode.MODEL_NOT_ALLOWED);
        }
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private ApiKeyContext validate(ApiKeyContext context) {
        if (!context.enabled()) {
            throw new ModelGateException(ErrorCode.API_KEY_DISABLED);
        }
        if (context.expiresAt() != null && context.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new ModelGateException(ErrorCode.API_KEY_EXPIRED);
        }
        return context;
    }

    private Optional<ApiKeyContext> readRedis(String hash) {
        try {
            String value = redisTemplate.opsForValue().get(redisKey(hash));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            CachedContext cached = objectMapper.readValue(value, CachedContext.class);
            if (cached.version() != 2 || cached.context() == null) {
                redisTemplate.delete(redisKey(hash));
                return Optional.empty();
            }
            return Optional.of(cached.context());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void writeRedis(String hash, ApiKeyContext context) {
        try {
            redisTemplate.opsForValue().set(redisKey(hash), objectMapper.writeValueAsString(new CachedContext(2, context)), REDIS_TTL);
        } catch (Exception ignored) {
            // MySQL fallback remains authoritative when Redis is unavailable.
        }
    }

    private String redisKey(String hash) {
        return "auth:key:" + hash;
    }

    @Override
    public void evictLocalKeyHashes(Iterable<String> hashes) {
        for (String hash : hashes) {
            localCache.invalidate(hash);
        }
    }

    @Override
    public void evictLocalMember(long memberId) {
        evictLocalKeyHashes(adminRepository.findKeyHashesByMember(memberId));
    }

    @Override
    public void evictLocalTeam(long teamId) {
        evictLocalKeyHashes(adminRepository.findKeyHashesByTeam(teamId));
    }

    private void scheduleInvalidation(AuthCacheInvalidationEvent event) {
        Runnable action = () -> invalidateOrigin(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void invalidateOrigin(AuthCacheInvalidationEvent event) {
        List<String> hashes = hashesFor(event);
        evictLocalKeyHashes(hashes);
        for (String hash : hashes) {
            try {
                redisTemplate.delete(redisKey(hash));
            } catch (RuntimeException ex) {
                log.warn("Auth cache Redis eviction failed. targetType={}, keyCount={}", event.targetType(), hashes.size(), ex);
                break;
            }
        }
        invalidationPublisher.publish(event);
    }

    private List<String> hashesFor(AuthCacheInvalidationEvent event) {
        return switch (event.targetType()) {
            case KEY_HASHES -> event.keyHashes();
            case MEMBER -> adminRepository.findKeyHashesByMember(event.targetId());
            case TEAM -> adminRepository.findKeyHashesByTeam(event.targetId());
        };
    }

    private List<String> copyHashes(Iterable<String> hashes) {
        List<String> copy = new ArrayList<>();
        for (String hash : hashes) {
            if (hash != null && !hash.isBlank()) copy.add(hash);
        }
        return copy;
    }

    private record CachedContext(int version, ApiKeyContext context) {
    }

    private String randomToken() {
        byte[] raw = UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
