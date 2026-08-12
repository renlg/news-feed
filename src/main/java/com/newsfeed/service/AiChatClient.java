package com.newsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newsfeed.config.AiConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Sends chat requests. */
@Slf4j
final class AiChatClient {

    private AiChatClient() {
    }

    static HttpResponse<String> send(AiConfig aiConfig, ObjectMapper objectMapper,
                                     ObjectNode requestBody, Duration timeout,
                                     String operation) throws IOException, InterruptedException {
        String primaryModel = requestBody.path("model").asText();
        ObjectNode normalizedRequestBody = requestBody.deepCopy();
        moveSystemMessageToFront(normalizedRequestBody, objectMapper);
        return sendWithModel(
                aiConfig, objectMapper, normalizedRequestBody, timeout, operation, primaryModel);
    }

    private static HttpResponse<String> sendWithModel(AiConfig aiConfig, ObjectMapper objectMapper,
                                                       ObjectNode requestBody, Duration timeout,
                                                       String operation, String model)
            throws IOException, InterruptedException {
        ObjectNode bodyForModel = requestBody.deepCopy();
        bodyForModel.put("model", model);
        List<String> roles = messageRoles(bodyForModel);
        if (roles.isEmpty() || !"system".equals(roles.get(0))) {
            throw new IllegalStateException("Normalized chat request must start with system message");
        }
        log.info("{} chat API call using model {}; messages roles: {}", operation, model, roles);

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

    private static void moveSystemMessageToFront(ObjectNode requestBody,
                                                  ObjectMapper objectMapper) {
        JsonNode messagesNode = requestBody.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            throw new IllegalArgumentException("Chat request must contain a messages array");
        }

        ObjectNode systemMessage = null;
        StringBuilder systemContent = new StringBuilder();
        ArrayNode nonSystemMessages = objectMapper.createArrayNode();
        for (JsonNode message : messagesNode) {
            if (message.isObject() && "system".equals(message.path("role").asText())) {
                if (systemMessage == null) {
                    systemMessage = ((ObjectNode) message).deepCopy();
                }
                if (message.hasNonNull("content")) {
                    if (!systemContent.isEmpty()) {
                        systemContent.append("\n\n");
                    }
                    systemContent.append(message.get("content").asText());
                }
            } else {
                nonSystemMessages.add(message.deepCopy());
            }
        }

        if (systemMessage == null) {
            throw new IllegalArgumentException("Chat request must contain a system message");
        }
        systemMessage.put("content", systemContent.toString());

        ArrayNode normalizedMessages = objectMapper.createArrayNode();
        normalizedMessages.add(systemMessage);
        normalizedMessages.addAll(nonSystemMessages);
        requestBody.set("messages", normalizedMessages);
    }

    private static List<String> messageRoles(ObjectNode requestBody) {
        JsonNode messagesNode = requestBody.get("messages");
        if (messagesNode == null || !messagesNode.isArray()) {
            throw new IllegalArgumentException("Chat request must contain a messages array");
        }
        List<String> roles = new ArrayList<>();
        for (JsonNode message : messagesNode) {
            roles.add(message.path("role").asText("<missing>"));
        }
        return roles;
    }

    private static String apiBaseUrl(AiConfig aiConfig) {
        String baseUrl = aiConfig.getBaseUrl().replaceAll("/+$", "");
        return baseUrl.endsWith("/v1")
                ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
    }
}
