package com.newsfeed.service;

import com.newsfeed.config.CanonicalTime;
import com.newsfeed.config.AiConfig;
import com.newsfeed.model.Article;
import com.newsfeed.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章AI处理服务：在抓取时异步对文章进行分类、打分和生成中文摘要
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleAiService {

    private final ArticleRepository articleRepository;
    private final AiConfig aiConfig;

    /**
     * 定时检查并处理未处理的 articles（每 2 分钟执行一次）
     */
    @Scheduled(fixedDelay = 120000)
    public void processPendingArticles() {
        if (aiConfig.getKey() == null || aiConfig.getKey().isBlank()) {
            return;
        }

        List<Article> unprocessed = articleRepository.findUnprocessedArticles();
        if (unprocessed.isEmpty()) {
            return;
        }

        log.info("发现 {} 篇待AI处理的文章", unprocessed.size());

        // 分批处理，每批 10 篇
        int batchSize = 10;
        for (int i = 0; i < unprocessed.size(); i += batchSize) {
            List<Article> batch = unprocessed.subList(i, Math.min(i + batchSize, unprocessed.size()));
            try {
                processBatch(batch);
            } catch (Exception e) {
                log.warn("AI处理批次失败: {}", e.getMessage());
                // 标记为已处理，避免反复重试
                markAsProcessed(batch);
            }
        }
    }

    /**
     * 手动触发AI处理，返回待处理文章数量
     */
    public int triggerProcessing() {
        List<Article> unprocessed = articleRepository.findUnprocessedArticles();
        if (unprocessed.isEmpty()) {
            return 0;
        }
        log.info("手动触发AI处理: {} 篇文章", unprocessed.size());
        // 异步执行，避免阻塞HTTP请求
        new Thread(() -> processPendingArticles()).start();
        return unprocessed.size();
    }

    /**
     * 获取AI处理状态统计
     */
    public Map<String, Long> getStats() {
        long processed = articleRepository.countProcessedFromAiSources();
        long unprocessed = articleRepository.countUnprocessedFromAiSources();
        long totalToday = articleRepository.countArticlesSince(CanonicalTime.now().minusDays(1));
        return Map.of("processed", processed, "unprocessed", unprocessed, "totalToday", totalToday);
    }

    /**
     * 重置今天AI源文章的处理状态，以便重新处理
     */
    public int resetTodayProcessing() {
        List<Article> articles = articleRepository.findUnprocessedArticles();
        // 获取所有AI源的文章（包括已处理的）
        List<Article> allAiArticles = articleRepository.findAll().stream()
                .filter(a -> a.getFeedSourceId() != null)
                .filter(a -> {
                    var source = articleRepository.findById(a.getId());
                    return true;
                })
                .toList();
        // 简单方式：将所有aiProcessed=true的文章重置
        int count = 0;
        for (Article a : articleRepository.findAll()) {
            if (Boolean.TRUE.equals(a.getAiProcessed())) {
                a.setAiProcessed(false);
                a.setAiCategory(null);
                a.setImportanceScore(null);
                a.setAiSummary(null);
                articleRepository.save(a);
                count++;
            }
        }
        log.info("已重置 {} 篇文章的AI处理状态", count);
        return count;
    }

    /**
     * 对一批文章调用AI进行分类、打分、生成摘要
     */
    private void processBatch(List<Article> articles) {
        try {
            // 构建文章列表
            StringBuilder articleList = new StringBuilder();
            for (Article a : articles) {
                String title = a.getTitle() != null ? a.getTitle() : "";
                String rssSummary = a.getSummary() != null ? a.getSummary() : "";
                if (rssSummary.length() > 300) {
                    rssSummary = rssSummary.substring(0, 300) + "...";
                }
                articleList.append(String.format("- ID=%d | 标题: %s | 原文摘要: %s\n",
                        a.getId(), escapeJson(title), escapeJson(rssSummary)));
            }

            String systemPrompt = """
                你是一个新闻编辑助手。请对每篇文章进行以下处理：
                            
                1. 分类（ai_category）：将文章分到以下5个类别之一：
                   - ai: AI相关新闻（人工智能、大模型、机器学习、AI产品等）
                   - tech: 科技新闻（互联网、硬件、软件、产品发布、科技公司动态等）
                   - domestic: 中国国内新闻（仅限发生在中国境内的新闻，包括中国政策、中国社会事件、中国经济、中国民生等）
                   - japan: 日本新闻（与日本相关的新闻）
                   - international: 国际新闻（中国以外的其他国家/地区的新闻、国际关系、全球事件等）
                            
                分类规则（重要）：
                - domestic 仅限中国境内发生的新闻，外国新闻绝对不能分到domestic
                - 如果新闻涉及美国、欧洲、韩国、东南亚等中国以外的地区，应分到 international
                - 中国企业的海外动态，如果主体是中国公司，可分到 domestic
                - 外国公司/政府的新闻，即使与中国经济相关，也应分到 international
                - ai 和 tech 的区分：ai专注人工智能领域，tech是更广泛的科技领域
                            
                2. 重要性评分（score）：1-10分，10分最重要。按以下5个维度综合评估：
                   - 重要性：事件对社会秩序、公共安全、政策走向或群体利益的影响深度与广度（国家级政策、重大灾难权重最高）
                   - 时效性：事件发生与发布的时间差，突发且即时报道的价值远高于滞后信息
                   - 显著性：涉及人物、机构或地点的知名度（政要、名人、地标事件自带高权重）
                   - 接近性：地理、心理或利益上与目标受众（中国读者）的关联度
                   - 趣味性/冲突性：内容的反常度、人情味、矛盾张力
                   评分参考：
                   - 9-10分：国家级重大政策、突发灾难、重大国际冲突等
                   - 7-8分：重要行业动态、知名企业/人物动态、有影响力的政策
                   - 5-6分：有一定影响力的行业/地区新闻
                   - 3-4分：一般性新闻，影响范围有限
                   - 1-2分：低价值内容、软文、广告性质
                            
                3. 中文摘要（summary）：用中文概括文章核心内容
                   - 不超过100个中文字
                   - 概括"什么时间、谁、做了什么"
                   - 使用简洁客观的新闻语言
                            
                请返回JSON格式：
                {"articles": [{"id": 123, "category": "ai", "score": 8, "summary": "摘要内容"}, ...]}
                            
                注意：
                - 必须使用文章的实际ID
                - 每篇文章都必须有分类、评分和摘要
                - 只返回JSON，不要返回其他内容
                """;

            String userMessage = "请处理以下文章：\n\n" + articleList;

            String modelName = aiConfig.getModel() != null && !aiConfig.getModel().isBlank()
                    ? aiConfig.getModel() : "gpt-4o-mini";

            String jsonBody = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}],\"temperature\":0.2,\"max_tokens\":4000}",
                    modelName, escapeJson(systemPrompt), escapeJson(userMessage));

            HttpClient client = AiConfig.getSharedHttpClient();

            String baseUrl = aiConfig.getBaseUrl().replaceAll("/+$", "");
            if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String content = extractContent(response.body());
                if (content != null && !content.isEmpty()) {
                    parseAndUpdateArticles(content, articles);
                    log.info("AI处理完成: {} 篇文章", articles.size());
                } else {
                    log.warn("AI返回内容为空");
                    markAsProcessed(articles);
                }
            } else {
                log.warn("AI API返回状态 {}: {}", response.statusCode(), response.body());
                markAsProcessed(articles);
            }
        } catch (Exception e) {
            log.warn("AI处理失败: {}", e.getMessage());
            markAsProcessed(articles);
        }
    }

    /**
     * 解析AI返回的结果并更新文章
     */
    private void parseAndUpdateArticles(String content, List<Article> articles) {
        // 提取JSON
        String jsonStr = extractJsonFromContent(content);
        if (jsonStr == null) {
            log.warn("无法从AI响应中提取JSON");
            markAsProcessed(articles);
            return;
        }

        // 解析每篇文章的结果
        // 格式: {"id": 123, "category": "ai", "score": 8, "summary": "摘要"}
        Pattern articlePattern = Pattern.compile(
                "\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"category\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"score\"\\s*:\\s*(\\d+)\\s*,\\s*\"summary\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = articlePattern.matcher(jsonStr);

        Map<Long, Article> articleMap = new HashMap<>();
        for (Article a : articles) {
            articleMap.put(a.getId(), a);
        }

        int processed = 0;
        while (matcher.find()) {
            try {
                long id = Long.parseLong(matcher.group(1));
                String category = matcher.group(2);
                int score = Integer.parseInt(matcher.group(3));
                String summary = matcher.group(4);
                // 处理JSON转义
                summary = summary.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");

                Article article = articleMap.get(id);
                if (article != null) {
                    article.setAiCategory(category);
                    article.setImportanceScore(score);
                    article.setAiSummary(summary);
                    article.setAiProcessed(true);
                    articleRepository.save(article);
                    processed++;
                }
            } catch (Exception e) {
                log.warn("解析文章结果失败: {}", e.getMessage());
            }
        }

        // 标记未匹配到的文章为已处理
        for (Article a : articles) {
            if (!Boolean.TRUE.equals(a.getAiProcessed())) {
                a.setAiProcessed(true);
                articleRepository.save(a);
            }
        }

        log.info("AI处理结果: {}/{} 篇文章成功分类", processed, articles.size());
    }

    /**
     * 标记文章为已处理（即使AI失败）
     */
    private void markAsProcessed(List<Article> articles) {
        for (Article a : articles) {
            a.setAiProcessed(true);
            articleRepository.save(a);
        }
    }

    private String extractContent(String jsonBody) {
        try {
            int choicesIdx = jsonBody.indexOf("\"choices\"");
            if (choicesIdx < 0) return null;

            String content = extractField(jsonBody, "\"content\"", choicesIdx);
            if (content == null || content.isEmpty()) {
                content = extractField(jsonBody, "\"reasoning_content\"", choicesIdx);
            }
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractField(String jsonBody, String fieldName, int startIdx) {
        try {
            int fieldIdx = jsonBody.indexOf(fieldName, startIdx);
            if (fieldIdx < 0) return null;
            int colonIdx = jsonBody.indexOf(":", fieldIdx);
            if (colonIdx < 0) return null;
            int quoteStart = jsonBody.indexOf("\"", colonIdx + 1);
            if (quoteStart < 0) return null;

            StringBuilder content = new StringBuilder();
            int i = quoteStart + 1;
            while (i < jsonBody.length()) {
                char c = jsonBody.charAt(i);
                if (c == '\\' && i + 1 < jsonBody.length()) {
                    char next = jsonBody.charAt(i + 1);
                    switch (next) {
                        case 'n': content.append('\n'); break;
                        case 'r': content.append('\r'); break;
                        case 't': content.append('\t'); break;
                        case '"': content.append('"'); break;
                        case '\\': content.append('\\'); break;
                        case '/': content.append('/'); break;
                        default: content.append(next); break;
                    }
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    content.append(c);
                    i++;
                }
            }
            return content.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonFromContent(String content) {
        int startIdx = content.indexOf("{");
        int endIdx = content.lastIndexOf("}");
        if (startIdx >= 0 && endIdx > startIdx) {
            return content.substring(startIdx, endIdx + 1);
        }
        return null;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
