package com.modelgate.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class AuthCacheInvalidationRedisConfiguration {
    @Bean
    RedisMessageListenerContainer authCacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            AuthCacheInvalidationSubscriber subscriber,
            @Value("${modelgate.auth-cache.invalidation-channel:modelgate:auth-cache:invalidate:v1}") String channel
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(channel));
        container.setErrorHandler(subscriber::onListenerError);
        return container;
    }
}
