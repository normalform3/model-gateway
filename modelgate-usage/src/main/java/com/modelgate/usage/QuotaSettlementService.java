package com.modelgate.usage;

import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.infrastructure.db.ModelEntitlementRepository;
import com.modelgate.infrastructure.db.UsageEventConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaSettlementService {
    public static final String GROUP = "modelgate-quota-settlement-consumer";
    private final UsageEventConsumerRepository consumers;
    private final ModelEntitlementRepository entitlements;

    public QuotaSettlementService(UsageEventConsumerRepository consumers, ModelEntitlementRepository entitlements) { this.consumers = consumers; this.entitlements = entitlements; }

    @Transactional
    public void consume(UsageCompletedEvent event) {
        if (!consumers.markConsumed(event.eventId(), GROUP)) return;
        event.quotaSettlements().forEach(entitlements::applySettlement);
    }
}
