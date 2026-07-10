package com.modelgate.usage;

import com.modelgate.common.event.UsageReportedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "modelgate.rocketmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopUsageEventPublisher implements UsageEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(NoopUsageEventPublisher.class);

    @Override
    public void publish(UsageReportedEvent event) {
        log.info("RocketMQ disabled; usage event kept in request log only. eventId={}, requestId={}",
                event.eventId(), event.requestId());
    }
}
