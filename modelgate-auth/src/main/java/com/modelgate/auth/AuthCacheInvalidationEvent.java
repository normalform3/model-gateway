package com.modelgate.auth;

import java.util.List;
import java.util.UUID;

/** Versioned, non-sensitive message for invalidating API-key caches on peer gateway nodes. */
public record AuthCacheInvalidationEvent(
        int version,
        String eventId,
        String originInstanceId,
        TargetType targetType,
        Long targetId,
        List<String> keyHashes
) {
    public static final int CURRENT_VERSION = 1;
    private static final String KEY_HASH_PATTERN = "[a-f0-9]{64}";

    public AuthCacheInvalidationEvent {
        eventId = eventId == null || eventId.isBlank() ? UUID.randomUUID().toString() : eventId;
        keyHashes = keyHashes == null ? List.of() : List.copyOf(keyHashes);
        if (targetType == null) throw new IllegalArgumentException("targetType is required.");
        if (targetType == TargetType.KEY_HASHES && keyHashes.isEmpty()) {
            throw new IllegalArgumentException("KEY_HASHES invalidation requires at least one key hash.");
        }
        if (targetType != TargetType.KEY_HASHES && !keyHashes.isEmpty()) {
            throw new IllegalArgumentException(targetType + " invalidation must not include key hashes.");
        }
        if (targetType != TargetType.KEY_HASHES && targetId == null) {
            throw new IllegalArgumentException(targetType + " invalidation requires a target ID.");
        }
        if (keyHashes.stream().anyMatch(hash -> hash == null || !hash.matches(KEY_HASH_PATTERN))) {
            throw new IllegalArgumentException("Auth cache invalidation must only contain SHA-256 key hashes.");
        }
    }

    public static AuthCacheInvalidationEvent keyHashes(List<String> keyHashes) {
        return new AuthCacheInvalidationEvent(CURRENT_VERSION, null, null, TargetType.KEY_HASHES, null, keyHashes);
    }

    public static AuthCacheInvalidationEvent member(long memberId) {
        return new AuthCacheInvalidationEvent(CURRENT_VERSION, null, null, TargetType.MEMBER, memberId, List.of());
    }

    public static AuthCacheInvalidationEvent team(long teamId) {
        return new AuthCacheInvalidationEvent(CURRENT_VERSION, null, null, TargetType.TEAM, teamId, List.of());
    }

    public AuthCacheInvalidationEvent withOrigin(String instanceId) {
        return new AuthCacheInvalidationEvent(version, eventId, instanceId, targetType, targetId, keyHashes);
    }

    public enum TargetType {
        KEY_HASHES,
        MEMBER,
        TEAM
    }
}
