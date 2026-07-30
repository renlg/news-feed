package com.newsfeed.service;

import com.newsfeed.config.AiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiCategoryService {

    private static final List<String> CATEGORIES = List.of(
            "时政", "财经", "科技", "国际", "体育", "娱乐",
            "社会", "军事", "教育", "健康", "文化", "法治",
            "环保", "农业", "能源"
    );

    private final AiConfig aiConfig;

    public String categorize(String summary, String content) {
        if (aiConfig.getKey() == null || aiConfig.getKey().isBlank()) {
            return null;
        }
        if (aiConfig.getBaseUrl() == null || aiConfig.getBaseUrl().isBlank()) {
            return null;
        }
        if (summary == null || summary.isBlank()) {
            return null;
        }

        String userMessage;
        if (content != null && !content.isBlank()) {
            userMessage = "摘要：" + summary + "\n\n正文：" + content;
        } else {
            userMessage = summary;
        }

        try {
            String categoryList = String.join("、", CATEGORIES);
            String systemPrompt = "你是一个新闻分类助手。请根据用户提供的新闻内容，从以下类目中选择最匹配的一个类目，只返回类目名称，不要返回其他内容。类目列表：" + categoryList;

            String jsonBody = """
                    {"model":"default","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}],"temperature":0.1,"max_tokens":50}"""
                    .formatted(escapeJson(systemPrompt), escapeJson(userMessage));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiConfig.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                String aiResult = extractContent(body);
                if (aiResult != null) {
                    aiResult = aiResult.trim();
                    if (CATEGORIES.contains(aiResult)) {
                        return aiResult;
                    }
                    log.warn("AI returned unrecognized category: {}", aiResult);
                }
            } else {
                log.warn("AI API returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("AI categorization failed: {}", e.getMessage());
        }
        return null;
    }

    private String extractContent(String jsonBody) {
        try {
            int choicesIdx = jsonBody.indexOf("\"choices\"");
            if (choicesIdx < 0) return null;
            int contentIdx = jsonBody.indexOf("\"content\"", choicesIdx);
            if (contentIdx < 0) return null;
            int colonIdx = jsonBody.indexOf(":", contentIdx);
            if (colonIdx < 0) return null;
            int quoteStart = jsonBody.indexOf("\"", colonIdx + 1);
            if (quoteStart < 0) return null;
            int quoteEnd = jsonBody.indexOf("\"", quoteStart + 1);
            if (quoteEnd < 0) return null;
            return jsonBody.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            log.warn("Failed to extract content from AI response: {}", e.getMessage());
            return null;
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
