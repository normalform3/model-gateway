package com.modelgate.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.common.chat.Usage;
import com.modelgate.common.domain.ApiKeyContext;
import com.modelgate.common.domain.RequestStatus;
import com.modelgate.common.domain.RouteTarget;
import com.modelgate.common.event.QuotaSettlementSnapshot;
import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.infrastructure.db.RequestRepository;
import com.modelgate.infrastructure.db.UsageEventOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Persists the terminal request state and one durable event before asynchronous dispatch. */
@Service
public class UsageCompletedOutboxService {
    private final RequestRepository requests;
    private final UsageEventOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final String topic;

    public UsageCompletedOutboxService(RequestRepository requests, UsageEventOutboxRepository outbox, ObjectMapper objectMapper,
                                       @Value("${modelgate.rocketmq.topic:AI_USAGE_EVENT}") String topic) {
        this.requests = requests;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Transactional
    public void complete(ApiKeyContext key, RouteTarget target, String requestId, String requestedModel, Usage usage, long durationMs,
                         Long firstTokenMs, RequestStatus status, String errorCode, List<QuotaSettlementSnapshot> settlements) {
        requests.complete(requestId, status, usage.promptTokens(), usage.completionTokens(), durationMs, firstTokenMs, errorCode);
        UsageCompletedEvent event = new UsageCompletedEvent(
                "usage-" + requestId, requestId, key.organizationId(), key.teamId(), key.memberId(), key.keyId(), key.credentialType(),
                key.projectId(), key.serviceAccountId(), requestedModel, target.provider(), target.actualModel(), usage.promptTokens(),
                usage.completionTokens(), usage.totalTokens(), durationMs, status.name(), OffsetDateTime.now(), settlements);
        try {
            outbox.insert(event.eventId(), requestId, topic, "REQUEST_" + status.name(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize UsageCompletedEvent.", ex);
        }
    }
}
