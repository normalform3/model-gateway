package com.modelgate.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.modelgate.bootstrap.api.ProviderTimeoutProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.modelgate")
@EnableScheduling
@EnableConfigurationProperties(ProviderTimeoutProperties.class)
public class ModelGateApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelGateApplication.class, args);
    }
}
