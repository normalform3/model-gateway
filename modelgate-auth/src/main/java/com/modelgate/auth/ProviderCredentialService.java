package com.modelgate.auth;

import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.ProviderCatalogRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ProviderCredentialService {
    private final ProviderCatalogRepository repository;
    private final ProviderCredentialCipher cipher;
    private final StringRedisTemplate redisTemplate;
    private final AtomicLong localSequence = new AtomicLong();

    public ProviderCredentialService(ProviderCatalogRepository repository, ProviderCredentialCipher cipher, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.cipher = cipher;
        this.redisTemplate = redisTemplate;
    }

    public ResolvedCredential select(long providerId, Set<Long> excluded) {
        List<ProviderCatalogRepository.EncryptedCredential> candidates = repository.enabledCredentials(providerId).stream()
                .filter(candidate -> !excluded.contains(candidate.credentialId())).toList();
        if (candidates.isEmpty()) {
            throw new ModelGateException(ErrorCode.MODEL_ROUTE_NOT_FOUND, "No enabled credential is available for this provider.");
        }
        long sequence = next(providerId);
        ProviderCatalogRepository.EncryptedCredential selected = candidates.get((int) Math.floorMod(sequence, candidates.size()));
        return new ResolvedCredential(selected.credentialId(), cipher.decrypt(selected.ciphertext(), selected.version()));
    }

    private long next(long providerId) {
        try {
            Long value = redisTemplate.opsForValue().increment("provider:credential:rr:" + providerId);
            if (value != null) return value;
        } catch (RuntimeException ignored) {
            // The local sequence preserves deterministic selection while Redis is temporarily unavailable.
        }
        return localSequence.incrementAndGet();
    }

    public record ResolvedCredential(long credentialId, String apiKey) { }
}
