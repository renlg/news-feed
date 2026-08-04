package com.newsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newsfeed.config.AiConfig;
import com.newsfeed.config.CanonicalTime;
import com.newsfeed.model.Article;
import com.newsfeed.repository.ArticleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文章AI处理服务：在抓取时异步对文章进行分类、打分和生成中文摘要
 */
@Slf4j
@Service
public class ArticleAiService {

    // Production responses for eight or more articles can exceed the 4,000-token cap.
    private static final int BATCH_SIZE = 4;
    private static final int SUMMARY_LIMIT = 300;
    static final int MAX_AI_FAILURES = 3;
    private static final Set<String> AI_CATEGORIES = Set.of(
            "ai", "tech", "domestic", "japan", "international");
    private static final Set<String> CHINESE_CATEGORIES = Set.of(
            "时政", "财经", "科技", "国际", "体育", "娱乐", "社会", "军事",
            "教育", "健康", "文化", "法治", "环保", "农业", "能源");

    private static final String SYSTEM_PROMPT = """
            你是新闻编辑。处理输入JSON中的每篇文章，只输出JSON对象：
            {"articles":[{"id":123,"aiCategory":"ai","chineseCategory":"科技","score":8,"summary":"摘要"}]}
            aiCategory只能是：ai(AI/大模型/机器学习)、tech(其他科技)、domestic(中国境内或中国主体)、japan(日本)、international(其他国际新闻)。外国主体不能归domestic。
            chineseCategory只能是：时政、财经、科技、国际、体育、娱乐、社会、军事、教育、健康、文化、法治、环保、农业、能源。
            score为1-10整数，综合社会影响、时效、知名度、与中国读者的相关性及冲突性；重大政策、灾难、国际冲突为9-10，一般新闻3-6，软文广告1-2。
            summary用不超过100个中文字客观概括时间、主体和事件。保留每个实际id，不得遗漏，不要输出JSON以外的文字。
            """;

    private final ArticleRepository articleRepository;
    private final AiConfig aiConfig;
    private final ArticleDedupService articleDedupService;
    private final ObjectMapper objectMapper;
    private final Executor articleAiExecutor;
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicLong cumulativePromptTokens = new AtomicLong();
    private final AtomicLong cumulativeCompletionTokens = new AtomicLong();
    private final AtomicLong cumulativeTotalTokens = new AtomicLong();

    public ArticleAiService(ArticleRepository articleRepository,
                            AiConfig aiConfig,
                            ArticleDedupService articleDedupService,
                            ObjectMapper objectMapper,
                            @Qualifier("articleAiExecutor") Executor articleAiExecutor) {
        this.articleRepository = articleRepository;
        this.aiConfig = aiConfig;
        this.articleDedupService = articleDedupService;
        this.objectMapper = objectMapper;
        this.articleAiExecutor = articleAiExecutor;
    }

    /** 定时检查并处理未处理的 articles（每 2 分钟执行一次）。 */
    @Scheduled(fixedDelay = 120000)
    public void processPendingArticles() {
        if (!processing.compareAndSet(false, true)) {
            log.info("AI处理任务已在执行，跳过本次定时检查");
            return;
        }
        try {
            processPendingArticlesInternal(null);
        } finally {
            processing.set(false);
        }
    }

    /** 手动触发AI处理，返回本次提交的待处理文章数量；已有任务执行时返回0。 */
    public int triggerProcessing() {
        if (!processing.compareAndSet(false, true)) {
            log.info("AI处理任务已在执行，忽略重复手动触发");
            return 0;
        }

        List<Article> unprocessed = articleRepository.findUnprocessedArticles();
        if (unprocessed.isEmpty()) {
            processing.set(false);
            return 0;
        }

        log.info("手动触发AI处理: {} 篇文章", unprocessed.size());
        try {
            articleAiExecutor.execute(() -> {
                try {
                    processPendingArticlesInternal(unprocessed);
                } finally {
                    processing.set(false);
                }
            });
        } catch (RuntimeException e) {
            processing.set(false);
            throw e;
        }
        return unprocessed.size();
    }

    private void processPendingArticlesInternal(List<Article> prefetchedArticles) {
        if (!isConfigured()) {
            return;
        }

        List<Article> unprocessed = prefetchedArticles != null
                ? prefetchedArticles : articleRepository.findUnprocessedArticles();
        if (unprocessed.isEmpty()) {
            return;
        }

        TitleDeduplication deduplication = deduplicateByTitle(unprocessed);
        List<Article> articlesToProcess = deduplication.representatives();
        int duplicateCount = unprocessed.size() - articlesToProcess.size();
        log.info("发现 {} 篇待AI处理的文章，标题去重后 {} 篇（省略 {} 篇重复文章）",
                unprocessed.size(), articlesToProcess.size(), duplicateCount);

        for (int i = 0; i < articlesToProcess.size(); i += BATCH_SIZE) {
            List<Article> batch = articlesToProcess.subList(
                    i, Math.min(i + BATCH_SIZE, articlesToProcess.size()));
            try {
                processBatch(batch);
            } catch (Exception e) {
                log.warn("AI处理批次失败: {}", e.getMessage());
                recordAiFailures(batch, "批次异常: " + e.getMessage());
            }
        }

        copyResultsToDuplicates(articlesToProcess, deduplication.duplicatesByRepresentativeId());
    }

    private boolean isConfigured() {
        return aiConfig.getKey() != null && !aiConfig.getKey().isBlank()
                && aiConfig.getBaseUrl() != null && !aiConfig.getBaseUrl().isBlank();
    }

    public Map<String, Long> getStats() {
        long processed = articleRepository.countProcessedFromAiSources();
        long unprocessed = articleRepository.countUnprocessedFromAiSources();
        long totalToday = articleRepository.countArticlesSince(CanonicalTime.now().minusDays(1));
        return Map.of("processed", processed, "unprocessed", unprocessed, "totalToday", totalToday);
    }

    /** Resets only AI-enabled articles fetched during today's canonical calendar day. */
    public int resetTodayProcessing() {
        LocalDateTime since = CanonicalTime.at(CanonicalTime.today(), LocalTime.MIDNIGHT);
        LocalDateTime until = since.plusDays(1);
        int count = articleRepository.resetAiProcessingBetween(since, until);
        log.info("已重置今天 {} 篇AI源文章的处理状态（{} 至 {}）", count, since, until);
        return count;
    }

    /** Calls the AI once for a batch to categorize, score, summarize, and set the Chinese category. */
    void processBatch(List<Article> articles) {
        try {
            ObjectNode input = objectMapper.createObjectNode();
            var inputArticles = input.putArray("articles");
            for (Article article : articles) {
                ObjectNode item = inputArticles.addObject();
                item.put("id", article.getId());
                item.put("title", nullToEmpty(article.getTitle()));
                item.put("summary", truncate(article.getSummary(), SUMMARY_LIMIT));
                item.put("currentCategory", nullToEmpty(article.getCategory()));
            }

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", configuredModel());
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", 4000);
            var messages = requestBody.putArray("messages");
            messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
            messages.addObject().put("role", "user")
                    .put("content", objectMapper.writeValueAsString(input));

            HttpResponse<String> response = AiChatClient.send(
                    aiConfig, objectMapper, requestBody, Duration.ofSeconds(120),
                    "Article AI processing");

            if (response.statusCode() == 200) {
                logTokenUsage(response.body(), articles.size());
                String content = extractContent(response.body());
                if (content != null && !content.isBlank()) {
                    parseAndUpdateArticles(content, articles);
                    log.info("AI处理完成: {} 篇文章", articles.size());
                } else {
                    log.warn("AI返回内容为空");
                    recordAiFailures(articles, "AI返回内容为空");
                }
            } else {
                log.warn("AI API返回状态 {}: {}", response.statusCode(), response.body());
                recordAiFailures(articles, "AI API状态 " + response.statusCode());
            }
        } catch (Exception e) {
            log.warn("AI处理失败: {}", e.getMessage());
            recordAiFailures(articles, "AI处理异常: " + e.getMessage());
        }
    }

    void parseAndUpdateArticles(String content, List<Article> articles) {
        Map<Long, Article> articleMap = new HashMap<>();
        articles.forEach(article -> articleMap.put(article.getId(), article));
        Set<Long> successfulArticleIds = new java.util.HashSet<>();
        int processed = 0;

        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            JsonNode results = root.get("articles");
            if (results == null || !results.isArray()) {
                throw new IllegalArgumentException("响应缺少articles数组");
            }

            for (JsonNode result : results) {
                long id = result.path("id").asLong(-1);
                Article article = articleMap.get(id);
                String aiCategory = textValue(result, "aiCategory", "category");
                String summary = textValue(result, "summary");
                int score = result.path("score").asInt(-1);
                if (article == null || !AI_CATEGORIES.contains(aiCategory)
                        || score < 1 || score > 10 || summary == null || summary.isBlank()
                        || !successfulArticleIds.add(id)) {
                    continue;
                }

                article.setAiCategory(aiCategory);
                article.setImportanceScore(score);
                article.setAiSummary(summary);
                String chineseCategory = textValue(result, "chineseCategory");
                if (CHINESE_CATEGORIES.contains(chineseCategory)) {
                    article.setAiCategoryName(chineseCategory);
                }
                if (article.getCategory() == null || article.getCategory().isBlank()) {
                    if (CHINESE_CATEGORIES.contains(chineseCategory)) {
                        article.setCategory(chineseCategory);
                    }
                }
                article.setAiProcessed(true);
                article.setAiFailCount(0);
                processed++;
            }
        } catch (Exception e) {
            log.warn("无法解析AI响应JSON: {}", e.getMessage());
            recordAiFailures(articles, "AI响应JSON解析失败: " + e.getMessage());
            log.info("AI处理结果: 0/{} 篇文章成功分类", articles.size());
            return;
        }

        for (Article article : articles) {
            if (!successfulArticleIds.contains(article.getId())) {
                applyAiFailure(article, "AI响应缺少有效文章结果");
            }
        }
        articleRepository.saveAll(articles);
        log.info("AI处理结果: {}/{} 篇文章成功分类", processed, articles.size());
    }

    private void recordAiFailures(List<Article> articles, String reason) {
        articles.forEach(article -> applyAiFailure(article, reason));
        articleRepository.saveAll(articles);
    }

    private void applyAiFailure(Article article, String reason) {
        int previousFailures = article.getAiFailCount() == null ? 0 : article.getAiFailCount();
        int failures = Math.min(previousFailures + 1, MAX_AI_FAILURES);
        article.setAiFailCount(failures);
        article.setAiProcessed(failures >= MAX_AI_FAILURES);
        if (previousFailures < MAX_AI_FAILURES && failures >= MAX_AI_FAILURES) {
            log.warn("文章 {} 连续AI处理失败 {} 次，停止重试。最后失败原因: {}",
                    article.getId(), failures, reason);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode choices = objectMapper.readTree(responseBody).get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null) {
                return null;
            }
            for (String field : List.of("content", "reasoning_content")) {
                if (message.hasNonNull(field) && !message.get(field).asText().isBlank()) {
                    return message.get(field).asText();
                }
            }
        } catch (Exception e) {
            log.debug("无法解析AI API响应: {}", e.getMessage());
        }
        return null;
    }

    private void logTokenUsage(String responseBody, int batchArticleCount) {
        try {
            JsonNode usage = objectMapper.readTree(responseBody).get("usage");
            if (usage == null || !usage.isObject()) {
                log.debug("AI响应未包含token usage（批次 {} 篇）", batchArticleCount);
                return;
            }
            long prompt = usage.path("prompt_tokens").asLong(0);
            long completion = usage.path("completion_tokens").asLong(0);
            long total = usage.path("total_tokens").asLong(prompt + completion);
            long cumulativePrompt = cumulativePromptTokens.addAndGet(prompt);
            long cumulativeCompletion = cumulativeCompletionTokens.addAndGet(completion);
            long cumulativeTotal = cumulativeTotalTokens.addAndGet(total);
            log.info("AI token用量（批次 {} 篇）: prompt={}, completion={}, total={}; " +
                            "进程累计: prompt={}, completion={}, total={}",
                    batchArticleCount, prompt, completion, total,
                    cumulativePrompt, cumulativeCompletion, cumulativeTotal);
        } catch (Exception e) {
            log.debug("无法读取AI token usage: {}", e.getMessage());
        }
    }

    private TitleDeduplication deduplicateByTitle(List<Article> articles) {
        ArticleDedupService.DedupResult result = articleDedupService.deduplicateByTitle(articles);
        Map<String, Article> representativeByTitle = new HashMap<>();
        for (Article representative : result.articles()) {
            String normalized = articleDedupService.normalizeTitle(representative.getTitle());
            if (!normalized.isEmpty()) {
                representativeByTitle.put(normalized, representative);
            }
        }

        Map<Long, List<Article>> duplicates = new LinkedHashMap<>();
        for (Article article : articles) {
            String normalized = articleDedupService.normalizeTitle(article.getTitle());
            Article representative = representativeByTitle.get(normalized);
            if (!normalized.isEmpty() && representative != null
                    && !representative.getId().equals(article.getId())) {
                duplicates.computeIfAbsent(representative.getId(), ignored -> new ArrayList<>())
                        .add(article);
            }
        }
        return new TitleDeduplication(result.articles(), duplicates);
    }

    private void copyResultsToDuplicates(List<Article> representativeArticles,
                                         Map<Long, List<Article>> duplicatesByRepresentativeId) {
        if (duplicatesByRepresentativeId.isEmpty()) {
            return;
        }
        Map<Long, Article> representatives = new HashMap<>();
        representativeArticles.forEach(article -> representatives.put(article.getId(), article));

        List<Article> duplicates = new ArrayList<>();
        for (Map.Entry<Long, List<Article>> entry : duplicatesByRepresentativeId.entrySet()) {
            Article representative = representatives.get(entry.getKey());
            for (Article duplicate : entry.getValue()) {
                if (representative == null
                        || !Boolean.TRUE.equals(representative.getAiProcessed())
                        || failureCount(representative) > 0) {
                    applyAiFailure(duplicate, "去重代表文章AI处理失败");
                    duplicates.add(duplicate);
                    continue;
                }
                duplicate.setAiCategory(representative.getAiCategory());
                duplicate.setAiCategoryName(representative.getAiCategoryName());
                duplicate.setImportanceScore(representative.getImportanceScore());
                duplicate.setAiSummary(representative.getAiSummary());
                if ((duplicate.getCategory() == null || duplicate.getCategory().isBlank())
                        && representative.getCategory() != null
                        && !representative.getCategory().isBlank()) {
                    duplicate.setCategory(representative.getCategory());
                }
                duplicate.setAiProcessed(true);
                duplicate.setAiFailCount(0);
                duplicates.add(duplicate);
            }
        }
        articleRepository.saveAll(duplicates);
    }

    private int failureCount(Article article) {
        return article.getAiFailCount() == null ? 0 : article.getAiFailCount();
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("响应中没有JSON对象");
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\' && inString && i + 1 < content.length()) {
                i++;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}' && --depth == 0) {
                return content.substring(start, i + 1);
            }
        }
        throw new IllegalArgumentException("JSON对象不完整");
    }

    private String textValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.hasNonNull(fieldName) && node.get(fieldName).isTextual()) {
                return node.get(fieldName).asText();
            }
        }
        return null;
    }

    private String configuredModel() {
        return aiConfig.getModel() != null && !aiConfig.getModel().isBlank()
                ? aiConfig.getModel() : "gpt-4o-mini";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record TitleDeduplication(List<Article> representatives,
                                      Map<Long, List<Article>> duplicatesByRepresentativeId) {
    }
}
