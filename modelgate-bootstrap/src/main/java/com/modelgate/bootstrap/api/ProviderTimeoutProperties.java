package com.modelgate.bootstrap.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Gateway-wide deadlines for provider calls. Stream limits apply to the first and subsequent events separately. */
@ConfigurationProperties(prefix = "modelgate.provider-timeout")
public record ProviderTimeoutProperties(
        Duration completion,
        Duration streamFirstEvent,
        Duration streamIdle
) {
    public ProviderTimeoutProperties {
        completion = completion == null ? Duration.ofSeconds(60) : completion;
        streamFirstEvent = streamFirstEvent == null ? Duration.ofSeconds(30) : streamFirstEvent;
        streamIdle = streamIdle == null ? Duration.ofSeconds(60) : streamIdle;
    }
}
