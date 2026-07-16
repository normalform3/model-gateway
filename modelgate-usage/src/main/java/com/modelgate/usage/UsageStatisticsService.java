package com.modelgate.usage;

import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.infrastructure.db.BillingRepository;
import com.modelgate.infrastructure.db.UsageEventConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageStatisticsService {
    public static final String GROUP = "modelgate-usage-consumer";
    private final UsageEventConsumerRepository consumers;
    private final BillingRepository billing;

    public UsageStatisticsService(UsageEventConsumerRepository consumers, BillingRepository billing) { this.consumers = consumers; this.billing = billing; }

    @Transactional
    public void consume(UsageCompletedEvent event) {
        if (consumers.markConsumed(event.eventId(), GROUP)) billing.insertUsage(event);
    }
}
