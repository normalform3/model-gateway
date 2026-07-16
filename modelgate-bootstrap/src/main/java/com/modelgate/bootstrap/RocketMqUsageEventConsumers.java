package com.modelgate.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.billing.BillingService;
import com.modelgate.common.event.UsageCompletedEvent;
import com.modelgate.usage.AuditLogService;
import com.modelgate.usage.BudgetAlertService;
import com.modelgate.usage.QuotaSettlementService;
import com.modelgate.usage.UsageStatisticsService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Starts one independent RocketMQ consumer group for each terminal-event concern. */
@Component
@ConditionalOnProperty(prefix = "modelgate.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqUsageEventConsumers implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(RocketMqUsageEventConsumers.class);
    private final ObjectMapper mapper;
    private final String endpoints;
    private final String topic;
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();
    private final List<EventHandler> handlers;

    public RocketMqUsageEventConsumers(ObjectMapper mapper,
                                       @Value("${modelgate.rocketmq.endpoints}") String endpoints,
                                       @Value("${modelgate.rocketmq.topic:AI_USAGE_EVENT}") String topic,
                                       UsageStatisticsService usage,
                                       QuotaSettlementService quota,
                                       BillingService billing,
                                       BudgetAlertService budget,
                                       AuditLogService audit) {
        if (!StringUtils.hasText(endpoints)) throw new IllegalArgumentException("modelgate.rocketmq.endpoints must be set when RocketMQ is enabled.");
        this.mapper = mapper;
        this.endpoints = endpoints;
        this.topic = topic;
        this.handlers = List.of(
                new EventHandler(UsageStatisticsService.GROUP, usage::consume),
                new EventHandler(QuotaSettlementService.GROUP, quota::consume),
                new EventHandler(BillingService.CONSUMER_GROUP, billing::consume),
                new EventHandler(BudgetAlertService.GROUP, budget::consume),
                new EventHandler(AuditLogService.GROUP, audit::consume));
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        for (EventHandler handler : handlers) start(handler);
    }

    private void start(EventHandler handler) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(handler.group());
        consumer.setNamesrvAddr(endpoints);
        consumer.setVipChannelEnabled(false);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                for (var message : messages) handler.consumer().accept(mapper.readValue(message.getBody(), UsageCompletedEvent.class));
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception ex) {
                log.warn("Usage event consumer failed. group={}", handler.group(), ex);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        consumers.add(consumer);
    }

    @Override
    public void destroy() { consumers.forEach(DefaultMQPushConsumer::shutdown); }

    private record EventHandler(String group, ThrowingConsumer consumer) { }
    @FunctionalInterface private interface ThrowingConsumer { void accept(UsageCompletedEvent event) throws Exception; }
}
