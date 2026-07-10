package com.modelgate.billing;

import com.modelgate.common.event.UsageReportedEvent;
import com.modelgate.infrastructure.db.BillingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    private static final String CONSUMER_GROUP = "modelgate-billing-consumer";

    private final BillingRepository billingRepository;

    public BillingService(BillingRepository billingRepository) {
        this.billingRepository = billingRepository;
    }

    @Transactional
    public void consume(UsageReportedEvent event) {
        if (!billingRepository.markConsumed(event.eventId(), CONSUMER_GROUP)) {
            return;
        }
        billingRepository.insertUsage(event);
        if ("SUCCESS".equals(event.status())) {
            billingRepository.insertBilling(event);
            billingRepository.insertQuotaConsumeTransaction(event);
        }
    }
}
