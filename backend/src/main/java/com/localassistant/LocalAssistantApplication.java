package com.localassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LocalAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalAssistantApplication.class, args);
    }
}
