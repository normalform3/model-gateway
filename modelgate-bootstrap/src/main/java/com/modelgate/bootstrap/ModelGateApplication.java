package com.modelgate.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.modelgate")
public class ModelGateApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelGateApplication.class, args);
    }
}
