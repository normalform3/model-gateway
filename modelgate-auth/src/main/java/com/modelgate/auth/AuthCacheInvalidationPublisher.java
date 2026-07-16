package com.modelgate.auth;

public interface AuthCacheInvalidationPublisher {
    void publish(AuthCacheInvalidationEvent event);
}
