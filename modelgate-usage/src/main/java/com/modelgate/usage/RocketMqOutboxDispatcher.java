package com.modelgate.usage;

import com.modelgate.infrastructure.db.UsageEventOutboxRepository;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/** Sends only committed outbox rows. A failed asynchronous send leaves the row retryable. */
@Component
@ConditionalOnProperty(prefix = "modelgate.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOutboxDispatcher implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(RocketMqOutboxDispatcher.class);
    private final UsageEventOutboxRepository outbox;
    private final DefaultMQProducer producer;

    public RocketMqOutboxDispatcher(UsageEventOutboxRepository outbox,
                                    @Value("${modelgate.rocketmq.endpoints}") String endpoints,
                                    @Value("${modelgate.rocketmq.producer-group:modelgate-usage-producer}") String producerGroup) {
        if (!StringUtils.hasText(endpoints)) throw new IllegalArgumentException("modelgate.rocketmq.endpoints must be set when RocketMQ is enabled.");
        this.outbox = outbox;
        this.producer = new DefaultMQProducer(producerGroup + "-outbox");
        this.producer.setNamesrvAddr(endpoints);
        this.producer.setVipChannelEnabled(false);
        this.producer.setSendMessageWithVIPChannel(false);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        producer.start();
    }

    @Scheduled(fixedDelayString = "${modelgate.rocketmq.outbox-dispatch-delay-ms:1000}")
    public void dispatch() {
        for (UsageEventOutboxRepository.OutboxRecord record : outbox.due(100)) {
            if (!outbox.lease(record.eventId())) continue;
            Message message = new Message(record.topic(), record.tag(), record.eventId(), record.payloadJson().getBytes(StandardCharsets.UTF_8));
            try {
                producer.send(message, new SendCallback() {
                    @Override public void onSuccess(SendResult sendResult) { outbox.markSent(record.eventId()); }
                    @Override public void onException(Throwable ex) {
                        log.warn("Usage outbox dispatch failed. eventId={}", record.eventId(), ex);
                        outbox.reschedule(record.eventId(), record.attempts(), ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                outbox.reschedule(record.eventId(), record.attempts(), ex.getMessage());
            }
        }
    }

    @Override
    public void destroy() { producer.shutdown(); }
}
