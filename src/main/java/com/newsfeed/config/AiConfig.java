package com.newsfeed.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.api")
public class AiConfig {

    private String baseUrl;
    private String key;
    private String model;
    private String fallbackModel;
    private String embeddingModel;

    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static HttpClient getSharedHttpClient() {
        return SHARED_HTTP_CLIENT;
    }
}
