package com.modelgate.billing;

import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.infrastructure.db.UsageEventConsumerRepository;
import com.modelgate.infrastructure.db.BillingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    public static final String CONSUMER_GROUP = "modelgate-billing-consumer";

    private final BillingRepository billingRepository;
    private final UsageEventConsumerRepository consumers;

    public BillingService(BillingRepository billingRepository, UsageEventConsumerRepository consumers) {
        this.billingRepository = billingRepository;
        this.consumers = consumers;
    }

    @Transactional
    public void consume(UsageCompletedEvent event) {
        if (!consumers.markConsumed(event.eventId(), CONSUMER_GROUP)) {
            return;
        }
        if ("SUCCESS".equals(event.status())) {
            billingRepository.insertBilling(event);
        }
    }
}
