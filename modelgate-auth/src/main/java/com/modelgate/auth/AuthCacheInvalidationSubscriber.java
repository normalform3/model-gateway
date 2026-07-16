package com.modelgate.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

@Component
public class AuthCacheInvalidationSubscriber implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(AuthCacheInvalidationSubscriber.class);

    private final ObjectMapper objectMapper;
    private final AuthCacheInvalidationHandler invalidationHandler;
    private final LongAdder received = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public AuthCacheInvalidationSubscriber(ObjectMapper objectMapper, AuthCacheInvalidationHandler invalidationHandler) {
        this.objectMapper = objectMapper;
        this.invalidationHandler = invalidationHandler;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            handle(objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), AuthCacheInvalidationEvent.class));
        } catch (Exception ex) {
            failures.increment();
            log.warn("Auth cache invalidation message was ignored. failures={}", failures.sum(), ex);
        }
    }

    public void handle(AuthCacheInvalidationEvent event) {
        if (event.version() != AuthCacheInvalidationEvent.CURRENT_VERSION) {
            log.warn("Auth cache invalidation message has unsupported version={}", event.version());
            return;
        }
        switch (event.targetType()) {
            case KEY_HASHES -> invalidationHandler.evictLocalKeyHashes(event.keyHashes());
            case MEMBER -> invalidationHandler.evictLocalMember(event.targetId());
            case TEAM -> invalidationHandler.evictLocalTeam(event.targetId());
        }
        received.increment();
        log.debug("Applied remote auth cache invalidation. eventId={}, origin={}, targetType={}, keyCount={}, received={}",
                event.eventId(), event.originInstanceId(), event.targetType(), event.keyHashes().size(), received.sum());
    }

    void onListenerError(Throwable error) {
        failures.increment();
        log.warn("Auth cache invalidation listener failed. failures={}", failures.sum(), error);
    }
}
