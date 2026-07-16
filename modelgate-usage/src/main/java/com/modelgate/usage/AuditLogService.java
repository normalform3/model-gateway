package com.modelgate.usage;

import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.infrastructure.db.UsageEventConsumerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {
    public static final String GROUP = "modelgate-audit-log-consumer";
    private final UsageEventConsumerRepository consumers;

    public AuditLogService(UsageEventConsumerRepository consumers) { this.consumers = consumers; }

    @Transactional
    public void consume(UsageCompletedEvent event) {
        if (consumers.markConsumed(event.eventId(), GROUP)) consumers.insertAuditLog(event);
    }
}
