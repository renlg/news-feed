package com.newsfeed.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newsfeed.config.AiConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/** Sends chat requests and retries once with the configured fallback model when appropriate. */
@Slf4j
final class AiChatClient {

    private AiChatClient() {
    }

    static HttpResponse<String> send(AiConfig aiConfig, ObjectMapper objectMapper,
                                     ObjectNode requestBody, Duration timeout,
                                     String operation) throws IOException, InterruptedException {
        String primaryModel = requestBody.path("model").asText();
        HttpResponse<String> response = sendWithModel(
                aiConfig, objectMapper, requestBody, timeout, operation, primaryModel);

        String fallbackModel = aiConfig.getFallbackModel();
        if (isConfiguredFallback(primaryModel, fallbackModel)
                && isModelUnavailable(response.statusCode(), response.body())) {
            if (response.statusCode() == 429) {
                log.warn("{} chat API call using model {} was rate limited (status 429); "
                                + "retrying once with fallback model {}",
                        operation, primaryModel, fallbackModel);
            } else {
                log.warn("{} chat API model {} is unavailable (status {}); "
                                + "retrying once with fallback model {}",
                        operation, primaryModel, response.statusCode(), fallbackModel);
            }
            response = sendWithModel(
                    aiConfig, objectMapper, requestBody, timeout, operation, fallbackModel);
        }
        return response;
    }

    private static HttpResponse<String> sendWithModel(AiConfig aiConfig, ObjectMapper objectMapper,
                                                       ObjectNode requestBody, Duration timeout,
                                                       String operation, String model)
            throws IOException, InterruptedException {
        ObjectNode bodyForModel = requestBody.deepCopy();
        bodyForModel.put("model", model);
        log.info("{} chat API call using model {}", operation, model);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl(aiConfig) + "/v1/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + aiConfig.getKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(bodyForModel)))
                .build();
        return AiConfig.getSharedHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isConfiguredFallback(String primaryModel, String fallbackModel) {
        return fallbackModel != null && !fallbackModel.isBlank()
                && !fallbackModel.equals(primaryModel);
    }

    static boolean isModelUnavailable(int statusCode, String responseBody) {
        if (statusCode == 429) {
            return true;
        }
        if (statusCode < 400 || statusCode >= 500) {
            return false;
        }

        String body = responseBody == null ? "" : responseBody.toLowerCase(Locale.ROOT);
        if (statusCode == 404) {
            return true;
        }

        boolean modelReference = body.contains("model");
        boolean unavailableReason = body.contains("not found")
                || body.contains("not_found")
                || body.contains("unavailable")
                || body.contains("unsupported")
                || body.contains("invalid")
                || body.contains("does not exist")
                || body.contains("unknown")
                || body.contains("access")
                || body.contains("permission")
                || body.contains("authoriz")
                || body.contains("auth");
        boolean upstreamAuthFailure = (statusCode == 401 || statusCode == 403)
                && body.contains("upstream");
        return (modelReference && unavailableReason) || upstreamAuthFailure;
    }

    private static String apiBaseUrl(AiConfig aiConfig) {
        String baseUrl = aiConfig.getBaseUrl().replaceAll("/+$", "");
        return baseUrl.endsWith("/v1")
                ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }
}
