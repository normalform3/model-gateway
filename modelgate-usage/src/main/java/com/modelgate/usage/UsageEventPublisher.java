package com.modelgate.usage;

import com.modelgate.common.event.UsageReportedEvent;

public interface UsageEventPublisher {
    void publish(UsageReportedEvent event);
}
