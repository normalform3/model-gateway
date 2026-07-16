package com.modelgate.usage;

import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.infrastructure.db.UsageEventConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetAlertService {
    public static final String GROUP = "modelgate-budget-alert-consumer";
    private final UsageEventConsumerRepository consumers;

    public BudgetAlertService(UsageEventConsumerRepository consumers) { this.consumers = consumers; }

    @Transactional
    public void consume(UsageCompletedEvent event) {
        if (!consumers.markConsumed(event.eventId(), GROUP)) return;
        event.quotaSettlements().forEach(snapshot -> consumers.insertBudgetAlert(event, snapshot));
    }
}
