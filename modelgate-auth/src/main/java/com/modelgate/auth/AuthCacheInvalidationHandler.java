package com.modelgate.auth;

interface AuthCacheInvalidationHandler {
    void evictLocalKeyHashes(Iterable<String> hashes);

    void evictLocalMember(long memberId);

    void evictLocalTeam(long teamId);
}
