package com.modelgate.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.common.event.UsageReportedEvent;
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

@Component
@ConditionalOnProperty(prefix = "modelgate.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqUsageConsumer implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(RocketMqUsageConsumer.class);

    private final ObjectMapper objectMapper;
    private final BillingService billingService;
    private final DefaultMQPushConsumer consumer;
    private final String topic;

    public RocketMqUsageConsumer(
            ObjectMapper objectMapper,
            BillingService billingService,
            @Value("${modelgate.rocketmq.endpoints}") String nameServer,
            @Value("${modelgate.rocketmq.topic:AI_USAGE_EVENT}") String topic
    ) {
        this.objectMapper = objectMapper;
        this.billingService = billingService;
        this.topic = topic;
        this.consumer = new DefaultMQPushConsumer("modelgate-billing-consumer");
        this.consumer.setNamesrvAddr(nameServer);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            try {
                for (var message : messages) {
                    UsageReportedEvent event = objectMapper.readValue(message.getBody(), UsageReportedEvent.class);
                    billingService.consume(event);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception ex) {
                log.warn("Failed to consume usage event batch.", ex);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        });
        consumer.start();
        log.info("RocketMQ usage consumer started. topic={}", topic);
    }

    @Override
    public void destroy() {
        consumer.shutdown();
    }
}
