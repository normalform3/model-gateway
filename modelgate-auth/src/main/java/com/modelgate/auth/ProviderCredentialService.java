package com.modelgate.auth;

import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.ProviderCatalogRepository;
import com.modelgate.infrastructure.db.ProviderModelQuotaPoolRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ProviderCredentialService {
    private final ProviderCatalogRepository repository;
    private final ProviderCredentialCipher cipher;
    private final StringRedisTemplate redisTemplate;
    private final AtomicLong localSequence = new AtomicLong();
    private final ProviderModelQuotaPoolRepository pools;

    public ProviderCredentialService(ProviderCatalogRepository repository, ProviderCredentialCipher cipher, StringRedisTemplate redisTemplate, ProviderModelQuotaPoolRepository pools) {
        this.repository = repository;
        this.cipher = cipher;
        this.redisTemplate = redisTemplate;
        this.pools = pools;
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

    public ResolvedCredential select(long providerId, long providerModelId, Set<Long> excluded) {
        Optional<Long> pooled = pools.nextCredential(providerModelId, excluded.stream().toList());
        if (pooled.isEmpty()) {
            if (pools.configured(providerModelId)) throw new ModelGateException(ErrorCode.PROVIDER_UNAVAILABLE, "The fixed provider model pool has no healthy credential with available quota.");
            return select(providerId, excluded);
        }
        ProviderCatalogRepository.EncryptedCredential credential = repository.enabledCredentials(providerId).stream().filter(item -> item.credentialId() == pooled.get()).findFirst()
                .orElseThrow(() -> new ModelGateException(ErrorCode.MODEL_ROUTE_NOT_FOUND, "No enabled credential is available for this provider model pool."));
        return new ResolvedCredential(credential.credentialId(), cipher.decrypt(credential.ciphertext(), credential.version()));
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
