package com.modelgate.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.common.event.UsageReportedEvent;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "modelgate.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqUsageEventPublisher implements UsageEventPublisher, InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(RocketMqUsageEventPublisher.class);

    private final ObjectMapper objectMapper;
    private final DefaultMQProducer producer;
    private final String topic;

    public RocketMqUsageEventPublisher(
            ObjectMapper objectMapper,
            @Value("${modelgate.rocketmq.endpoints}") String nameServer,
            @Value("${modelgate.rocketmq.producer-group:modelgate-usage-producer}") String producerGroup,
            @Value("${modelgate.rocketmq.topic:AI_USAGE_EVENT}") String topic
    ) {
        if (!StringUtils.hasText(nameServer)) {
            throw new IllegalArgumentException("modelgate.rocketmq.endpoints must be set when RocketMQ is enabled. "
                    + "Set MODELGATE_ROCKETMQ_ENDPOINTS to a NameServer address such as host:9876.");
        }
        this.objectMapper = objectMapper;
        this.producer = new DefaultMQProducer(producerGroup);
        this.producer.setNamesrvAddr(nameServer);
        this.producer.setVipChannelEnabled(false);
        this.producer.setSendMessageWithVIPChannel(false);
        this.topic = topic;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        producer.start();
        log.info("RocketMQ usage producer started. topic={}", topic);
    }

    @Override
    public void publish(UsageReportedEvent event) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(event);
            Message message = new Message(topic, tag(event), event.eventId(), body);
            producer.send(message);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish usage event to RocketMQ.", ex);
        }
    }

    @Override
    public void destroy() {
        producer.shutdown();
    }

    private String tag(UsageReportedEvent event) {
        return "REQUEST_" + event.status();
    }
}
