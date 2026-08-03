package com.newsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newsfeed.config.AiConfig;
import com.newsfeed.model.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiCategoryService {

    private static final List<String> CATEGORIES = List.of(
            "时政", "财经", "科技", "国际", "体育", "娱乐",
            "社会", "军事", "教育", "健康", "文化", "法治",
            "环保", "农业", "能源"
    );
    private static final int BATCH_SIZE = 15;
    private static final int SUMMARY_LIMIT = 300;

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Kept for callers that categorize one article; it uses the same batch implementation. */
    public String categorize(String summary, String content) {
        Article article = Article.builder().summary(summary).build();
        return categorize(List.of(article)).get(0);
    }

    /** Returns categories in the same order as the supplied articles; null means use the RSS category. */
    public List<String> categorize(List<Article> articles) {
        List<String> categories = new ArrayList<>(Collections.nCopies(articles.size(), null));
        if (articles.isEmpty() || !isConfigured()) {
            return categories;
        }

        for (int from = 0; from < articles.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, articles.size());
            List<String> batchCategories = categorizeBatch(articles.subList(from, to));
            for (int i = 0; i < batchCategories.size(); i++) {
                categories.set(from + i, batchCategories.get(i));
            }
        }
        return categories;
    }

    private boolean isConfigured() {
        return aiConfig.getKey() != null && !aiConfig.getKey().isBlank()
                && aiConfig.getBaseUrl() != null && !aiConfig.getBaseUrl().isBlank();
    }

    private List<String> categorizeBatch(List<Article> articles) {
        List<String> categories = new ArrayList<>(Collections.nCopies(articles.size(), null));
        try {
            StringBuilder articleList = new StringBuilder();
            for (int i = 0; i < articles.size(); i++) {
                Article article = articles.get(i);
                articleList.append(i + 1)
                        .append(". 标题: ").append(nullToEmpty(article.getTitle()))
                        .append(" | 摘要: ").append(truncateText(article.getSummary(), SUMMARY_LIMIT))
                        .append('\n');
            }

            String systemPrompt = "新闻分类器。为每条新闻从类目中选一个："
                    + String.join("、", CATEGORIES)
                    + "。只能输出JSON数组：[{\"id\":1,\"category\":\"科技\"}]。id是新闻编号，不要输出其他文字。";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", aiConfig.getModel() != null && !aiConfig.getModel().isBlank()
                    ? aiConfig.getModel() : "gpt-4o-mini");
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", Math.max(100, articles.size() * 20));
            var messages = requestBody.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", articleList.toString());

            String baseUrl = aiConfig.getBaseUrl().replaceAll("/+$", "");
            if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = AiConfig.getSharedHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String content = extractContent(response.body());
                if (content != null) {
                    return parseCategories(content, articles.size());
                }
                log.warn("AI categorization returned empty content");
            } else {
                log.warn("AI categorization API returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("AI batch categorization failed: {}", e.getMessage());
        }
        return categories;
    }

    private List<String> parseCategories(String content, int articleCount) {
        List<String> categories = new ArrayList<>(Collections.nCopies(articleCount, null));
        try {
            JsonNode items = findItemsArray(stripMarkdownCodeBlock(content));
            if (items == null || !items.isArray()) {
                log.warn("Unable to extract category JSON array; using RSS categories");
                return categories;
            }
            for (JsonNode item : items) {
                String category = item.has("category") ? item.get("category").asText().trim() : "";
                if (!CATEGORIES.contains(category)) {
                    continue;
                }
                for (String id : extractIds(item)) {
                    try {
                        int index = Integer.parseInt(id);
                        if (index >= 1 && index <= articleCount) {
                            categories.set(index - 1, category);
                        }
                    } catch (NumberFormatException ignored) {
                        // Tolerate link/url-style output just as the digest parser does.
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AI category response parsing failed: {}", e.getMessage());
        }
        return categories;
    }

    private List<String> extractIds(JsonNode item) {
        List<String> ids = new ArrayList<>();
        for (String field : List.of("ids", "links", "urls")) {
            JsonNode node = item.get(field);
            if (node != null && node.isArray()) {
                node.forEach(value -> ids.add(value.asText().trim()));
                return ids;
            }
        }
        for (String field : List.of("id", "link", "url")) {
            JsonNode node = item.get(field);
            if (node != null && !node.isNull()) {
                ids.add(node.asText().trim());
                return ids;
            }
        }
        return ids;
    }

    private String extractContent(String jsonBody) {
        try {
            JsonNode choices = objectMapper.readTree(jsonBody).get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.hasNonNull("content")) {
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse AI response: {}", e.getMessage());
        }
        return null;
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private String stripMarkdownCodeBlock(String content) {
        Matcher matcher = Pattern.compile("```(?:json|JSON)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL)
                .matcher(content.trim());
        return matcher.find() ? matcher.group(1).trim() : content.trim();
    }

    private JsonNode findItemsArray(String content) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node.isArray()) return node;
            if (node.isObject()) {
                for (String field : List.of("items", "categories", "results")) {
                    if (node.has(field) && node.get(field).isArray()) return node.get(field);
                }
                for (JsonNode child : node) if (child.isArray()) return child;
            }
        } catch (Exception ignored) {
            // A model may surround otherwise valid JSON with prose.
        }
        int start = content.indexOf('[');
        if (start < 0) return null;
        int end = findMatchingBracket(content, start);
        return end > start ? objectMapper.readTree(content.substring(start, end + 1)) : null;
    }

    private int findMatchingBracket(String content, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && inString && i + 1 < content.length()) {
                i++;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '[') {
                depth++;
            } else if (!inString && c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
