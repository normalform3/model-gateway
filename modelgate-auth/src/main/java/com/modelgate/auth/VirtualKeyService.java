package com.modelgate.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.modelgate.common.api.AdminDtos.CreateMemberApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateApiKeyRequest;
import com.modelgate.common.api.AdminDtos.CreateApiKeyResponse;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.BudgetPolicy;
import com.modelgate.common.domain.RateLimitPolicy;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.AdminRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class VirtualKeyService {
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    private final AdminRepository adminRepository;
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, ApiKeyContext> localCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(Duration.ofMinutes(2))
            .build();

    public VirtualKeyService(AdminRepository adminRepository, StringRedisTemplate redisTemplate) {
        this.adminRepository = adminRepository;
        this.redisTemplate = redisTemplate;
    }

    public CreateApiKeyResponse create(CreateApiKeyRequest request) {
        String apiKey = "mg-key-" + randomToken();
        String prefix = apiKey.substring(0, Math.min(apiKey.length(), 18));
        String hash = sha256(apiKey);
        long keyId = adminRepository.insertApiKey(request, prefix, hash);
        return new CreateApiKeyResponse(keyId, prefix, apiKey, true);
    }

    public CreateApiKeyResponse createForMember(long teamId, long memberId, CreateMemberApiKeyRequest request) {
        String apiKey = "mg-key-" + randomToken();
        String prefix = apiKey.substring(0, Math.min(apiKey.length(), 18));
        String hash = sha256(apiKey);
        long keyId = adminRepository.insertMemberApiKey(teamId, memberId, request, prefix, hash);
        return new CreateApiKeyResponse(keyId, prefix, apiKey, true);
    }

    public void disable(long keyId) {
        if (!adminRepository.disableApiKey(keyId)) {
            throw new ModelGateException(ErrorCode.INVALID_API_KEY, "API key was not found.");
        }
        adminRepository.findKeyHash(keyId).ifPresent(hash -> {
            localCache.invalidate(hash);
            redisTemplate.delete(redisKey(hash));
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
            return Optional.of(decode(value));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void writeRedis(String hash, ApiKeyContext context) {
        try {
            redisTemplate.opsForValue().set(redisKey(hash), encode(context), REDIS_TTL);
        } catch (RuntimeException ignored) {
            // MySQL fallback remains authoritative when Redis is unavailable.
        }
    }

    private String redisKey(String hash) {
        return "auth:key:" + hash;
    }

    private String encode(ApiKeyContext context) {
        return String.join("|",
                Long.toString(context.keyId()),
                Long.toString(context.organizationId()),
                Long.toString(context.teamId()),
                Long.toString(context.applicationId()),
                context.memberId() == null ? "" : Long.toString(context.memberId()),
                Long.toString(context.quotaAccountId()),
                String.join(",", context.allowedModels()),
                Integer.toString(context.rateLimitPolicy().keyRpm()),
                Integer.toString(context.rateLimitPolicy().teamRpm()),
                Integer.toString(context.rateLimitPolicy().teamConcurrency()),
                Integer.toString(context.rateLimitPolicy().modelConcurrency()),
                Long.toString(context.budgetPolicy().maxAvailableTokens()),
                Boolean.toString(context.enabled()),
                context.expiresAt() == null ? "" : context.expiresAt().toString());
    }

    private ApiKeyContext decode(String value) {
        String[] p = value.split("\\|", -1);
        int offset = p.length >= 14 ? 1 : 0;
        Long memberId = offset == 0 || p[4].isBlank() ? null : Long.parseLong(p[4]);
        Set<String> models = p[5 + offset].isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(p[5 + offset].split(",")));
        OffsetDateTime expiresAt = p[12 + offset].isBlank() ? null : OffsetDateTime.parse(p[12 + offset]);
        return new ApiKeyContext(
                Long.parseLong(p[0]),
                Long.parseLong(p[1]),
                Long.parseLong(p[2]),
                Long.parseLong(p[3]),
                memberId,
                Long.parseLong(p[4 + offset]),
                models,
                new RateLimitPolicy(
                        Integer.parseInt(p[6 + offset]),
                        Integer.parseInt(p[7 + offset]),
                        Integer.parseInt(p[8 + offset]),
                        Integer.parseInt(p[9 + offset])),
                new BudgetPolicy(Long.parseLong(p[10 + offset])),
                Boolean.parseBoolean(p[11 + offset]),
                expiresAt);
    }

    private String randomToken() {
        byte[] raw = UUID.randomUUID().toString().replace("-", "").getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
