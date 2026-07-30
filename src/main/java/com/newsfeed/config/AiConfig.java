package com.newsfeed.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.api")
public class AiConfig {

    private String baseUrl;
    private String key;
}
