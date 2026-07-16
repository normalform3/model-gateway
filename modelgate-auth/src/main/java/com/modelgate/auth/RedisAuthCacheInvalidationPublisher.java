package com.modelgate.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

@Component
public class RedisAuthCacheInvalidationPublisher implements AuthCacheInvalidationPublisher {
    private static final Logger log = LoggerFactory.getLogger(RedisAuthCacheInvalidationPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final String instanceId;
    private final LongAdder published = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public RedisAuthCacheInvalidationPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${modelgate.auth-cache.invalidation-channel:modelgate:auth-cache:invalidate:v1}") String channel,
            @Value("${modelgate.auth-cache.instance-id:}") String configuredInstanceId
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.channel = channel;
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString()
                : configuredInstanceId.trim();
    }

    @Override
    public void publish(AuthCacheInvalidationEvent event) {
        try {
            AuthCacheInvalidationEvent outbound = event.withOrigin(instanceId);
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(outbound));
            published.increment();
            log.debug("Published auth cache invalidation. eventId={}, origin={}, targetType={}, keyCount={}, published={}",
                    outbound.eventId(), outbound.originInstanceId(), outbound.targetType(), outbound.keyHashes().size(), published.sum());
        } catch (Exception ex) {
            failures.increment();
            log.warn("Auth cache invalidation publish failed. targetType={}, keyCount={}, failures={}",
                    event.targetType(), event.keyHashes().size(), failures.sum(), ex);
        }
    }
}
