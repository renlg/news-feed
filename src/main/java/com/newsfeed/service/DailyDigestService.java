package com.newsfeed.service;

import com.newsfeed.config.AiConfig;
import com.newsfeed.config.CanonicalTime;
import com.newsfeed.config.HttpUrlSafety;
import com.newsfeed.model.Article;
import com.newsfeed.model.DailyDigest;
import com.newsfeed.repository.ArticleRepository;
import com.newsfeed.repository.DailyDigestRepository;
import com.newsfeed.service.ArticleDedupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 每日新闻摘要服务
 * 9点定时任务：查询高分文章 → AI筛选合并相似新闻 → 生成摘要
 * AI处理在文章抓取时由 ArticleAiService 异步完成（分类+打分+摘要）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyDigestService {

    private final ArticleRepository articleRepository;
    private final DailyDigestRepository digestRepository;
    private final AiConfig aiConfig;
    private final ArticleDedupService articleDedupService;
    private final HttpUrlSafety httpUrlSafety;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean generating = new AtomicBoolean(false);
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "digest-async"); t.setDaemon(true); return t; });
    private final ExecutorService categoryExecutor = Executors.newFixedThreadPool(5,
            r -> { Thread t = new Thread(r, "digest-category"); t.setDaemon(true); return t; });

    private static final AtomicReference<String> generationStatus = new AtomicReference<>("idle");
    private static final AtomicReference<String> generationError = new AtomicReference<>("");

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // DB查询上限（去重前的原始候选池，保证去重后仍有足够多样性）
    private static final int DOMESTIC_FETCH_LIMIT = 300;
    private static final int OTHER_FETCH_LIMIT = 150;
    // 去重后送入AI的候选条数上限（砍掉70%+输入token）
    private static final int AI_CANDIDATE_LIMIT = 60;
    // 最低分数线
    private static final int MIN_SCORE = 5;
    // AI最终输出条数
    private static final int OUTPUT_LIMIT = 10;
    // prompt中每条候选摘要截断长度（AI输出只要50字，输入不需要全文）
    private static final int PROMPT_SUMMARY_LIMIT = 70;

    // 每天早上9点执行，生成前一天的摘要
    @Scheduled(cron = "0 0 9 * * ?")
    public void generateDailyDigest() {
        log.info("开始生成昨日新闻摘要...");
        String yesterday = CanonicalTime.today().minusDays(1).format(DATE_FORMATTER);
        if (!generating.compareAndSet(false, true)) {
            log.info("已有摘要生成任务在执行中，跳过");
            return;
        }
        generationStatus.set("generating");
        generationError.set("");
        try {
            if (digestRepository.existsByDigestDate(yesterday)) {
                log.info("昨日摘要已存在，跳过");
                generationStatus.set("idle");
                return;
            }
            doGenerate(yesterday);
        } catch (Throwable t) {
            log.error("定时生成摘要失败: {}", t.getMessage(), t);
            generationError.set("生成失败: " + t.getMessage());
            generationStatus.set("error");
        } finally {
            if (generationStatus.get().equals("generating")) {
                generationStatus.set("idle");
            }
            generating.set(false);
        }
    }

    // 手动触发生成（异步执行，页面立即返回）
    // 返回 true=任务已提交，false=已有任务在执行中
    public boolean forceGenerateDigestAsync() {
        log.info("手动触发异步生成昨日新闻摘要...");
        String yesterday = CanonicalTime.today().minusDays(1).format(DATE_FORMATTER);
        if (!generating.compareAndSet(false, true)) {
            log.info("已有摘要生成任务在执行中，跳过");
            return false;
        }
        generationStatus.set("generating");
        generationError.set("");
        CompletableFuture.runAsync(() -> {
            try {
                if (digestRepository.existsByDigestDate(yesterday)) {
                    digestRepository.deleteByDigestDate(yesterday);
                    log.info("已删除昨日的旧摘要记录");
                }
                doGenerate(yesterday);
            } catch (Throwable t) {
                log.error("异步生成摘要失败: {}", t.getMessage(), t);
                generationError.set("生成失败: " + t.getMessage());
            } finally {
                if (!generationError.get().isEmpty()) {
                    generationStatus.set("error");
                } else {
                    generationStatus.set("idle");
                }
                generating.set(false);
            }
        }, asyncExecutor);
        return true;
    }

    public String getGenerationStatus() {
        return generationStatus.get();
    }

    public String getGenerationError() {
        return generationError.get();
    }

    // 按日期删除摘要
    public void deleteDigestByDate(String date) {
        log.info("删除摘要: {}", date);
        digestRepository.deleteByDigestDate(date);
    }

    private void doGenerate(String digestDate) {
        LocalDate targetDate = LocalDate.parse(digestDate, DATE_FORMATTER);
        LocalDateTime until = CanonicalTime.at(targetDate.plusDays(1), LocalTime.of(9, 0));
        LocalDateTime since = until.minusHours(24);

        // 查询各分类的高分文章（分数>5），按分数降序
        Map<String, List<Article>> candidates = new HashMap<>();
        candidates.put("domestic", articleRepository.findHighScoringByCategory("domestic", MIN_SCORE, since, until)
                .stream().limit(DOMESTIC_FETCH_LIMIT).collect(Collectors.toList()));
        candidates.put("ai", articleRepository.findHighScoringByCategory("ai", MIN_SCORE, since, until)
                .stream().limit(OTHER_FETCH_LIMIT).collect(Collectors.toList()));
        candidates.put("tech", articleRepository.findHighScoringByCategory("tech", MIN_SCORE, since, until)
                .stream().limit(OTHER_FETCH_LIMIT).collect(Collectors.toList()));
        candidates.put("japan", articleRepository.findHighScoringByCategory("japan", MIN_SCORE, since, until)
                .stream().limit(OTHER_FETCH_LIMIT).collect(Collectors.toList()));
        candidates.put("international", articleRepository.findHighScoringByCategory("international", MIN_SCORE, since, until)
                .stream().limit(OTHER_FETCH_LIMIT).collect(Collectors.toList()));

        int total = candidates.values().stream().mapToInt(List::size).sum();
        log.info("高分文章候选: domestic={}, ai={}, tech={}, japan={}, international={}, 总计={}",
                candidates.get("domestic").size(), candidates.get("ai").size(),
                candidates.get("tech").size(), candidates.get("japan").size(),
                candidates.get("international").size(), total);

        if (total == 0) {
            log.warn("没有高分文章，跳过生成摘要");
            return;
        }

        // 对每个分类先去重，再调用AI筛选+合并相似新闻（5个分类并行处理）
        List<String> categoryOrder = List.of("domestic", "ai", "tech", "japan", "international");
        Map<String, List<DigestItem>> finalItems = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = categoryOrder.stream()
                .map(category -> CompletableFuture.runAsync(() -> {
                    List<Article> articles = candidates.get(category);
                    if (articles.isEmpty()) {
                        finalItems.put(category, Collections.emptyList());
                        return;
                    }
                    ArticleDedupService.DedupResult dedupResult = articleDedupService.deduplicate(articles);
                    List<Article> deduped = dedupResult.articles();
                    Map<Long, List<String>> clusterLinks = dedupResult.clusterLinks();
                    if (deduped.size() > AI_CANDIDATE_LIMIT) {
                        deduped = deduped.subList(0, AI_CANDIDATE_LIMIT);
                    }
                    log.info("AI筛选合并 {} 分类: {} → {} 篇（去重后）→ {} 篇（截取top {}）",
                            category, articles.size(), dedupResult.articles().size(),
                            deduped.size(), AI_CANDIDATE_LIMIT);
                    List<DigestItem> items = selectAndMergeWithAI(category, deduped, clusterLinks);
                    finalItems.put(category, items);
                    log.info("{} 分类最终: {} 条新闻", category, items.size());
                }, categoryExecutor).exceptionally(ex -> {
                    log.error("处理 {} 分类时发生异常: {}", category, ex.getMessage(), ex);
                    finalItems.put(category, Collections.emptyList());
                    return null;
                }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 生成摘要（按固定顺序汇总）
        DailyDigest digest = buildDigest(digestDate, finalItems);
        digestRepository.save(digest);
        log.info("每日新闻摘要生成完成: {}", digestDate);
    }

    // ========== AI筛选+合并 ==========

    private List<DigestItem> selectAndMergeWithAI(String category, List<Article> articles,
                                                   Map<Long, List<String>> clusterLinks) {
        if (articles.isEmpty()) return Collections.emptyList();
        if (aiConfig.getKey() == null || aiConfig.getKey().isBlank()) {
            log.warn("AI未配置，直接使用文章摘要");
            return buildFallbackItems(articles, clusterLinks);
        }

        try {
            StringBuilder articleList = new StringBuilder();
            for (int i = 0; i < articles.size(); i++) {
                Article a = articles.get(i);
                String title = a.getTitle() != null ? a.getTitle() : "";
                String summary = truncateText(a.getAiSummary(), PROMPT_SUMMARY_LIMIT);
                articleList.append(String.format("%d. 标题: %s | 摘要: %s\n",
                        i + 1, title, summary));
            }

            String categoryName = getCategoryDisplayName(category);
            String systemPrompt = String.format("""
                    你是新闻摘要JSON生成器。你必须且只能输出合法JSON，不得输出任何叙述、解释或思考文字。

                    任务：从以下%d条「%s」类候选新闻中，筛选最多%d条最重要的，合并报道同一事件的多个来源。

                    输出格式（严格遵守，不得有任何偏差）：
                    {"items":[{"summary":"50字以内中文摘要","ids":[1,2]}]}

                    规则：
                    - summary不超过50字，概括核心事实
                    - ids填写每条对应的编号（候选列表前面的数字），同一事件的多篇报道合并为一条
                    - 按重要性降序排列
                    - 直接输出JSON对象，不要用```包裹，不要加任何前缀或后缀文字
                    """, articles.size(), categoryName, OUTPUT_LIMIT);

            String modelName = aiConfig.getModel() != null && !aiConfig.getModel().isBlank()
                    ? aiConfig.getModel() : "gpt-4o-mini";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", 4000);
            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            requestBody.set("response_format", responseFormat);
            var messages = requestBody.putArray("messages");
            var sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            var userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", "候选新闻：\n" + articleList);

            HttpResponse<String> response = AiChatClient.send(
                    aiConfig, objectMapper, requestBody, Duration.ofSeconds(120),
                    "Daily digest " + category);

            if (response.statusCode() == 200) {
                String content = extractContent(response.body());
                if (content != null && !content.isEmpty()) {
                    List<DigestItem> items = parseDigestItems(content);
                    if (!items.isEmpty()) {
                        items = resolveIdsToLinks(items, articles, clusterLinks);
                        items = deduplicateAcrossItems(items);
                        return items;
                    }
                    log.warn("AI筛选返回内容无法解析为JSON，回退使用文章摘要");
                } else {
                    log.warn("AI筛选返回内容为空");
                }
            } else {
                log.warn("AI筛选API返回状态 {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("AI筛选失败({}): {}", category, e.getMessage(), e);
        }

        return buildFallbackItems(articles, clusterLinks);
    }

    private List<DigestItem> resolveIdsToLinks(List<DigestItem> items, List<Article> articles,
                                               Map<Long, List<String>> clusterLinks) {
        List<DigestItem> result = new ArrayList<>();
        for (DigestItem item : items) {
            List<String> links = new ArrayList<>();
            Set<String> seenLinks = new LinkedHashSet<>();
            Article metadataArticle = null;
            for (String idStr : item.links) {
                Article article = resolveArticle(idStr, articles);
                if (article != null && article.getLink() != null) {
                    seenLinks.add(article.getLink());
                    if (metadataArticle == null || scoreOf(article) > scoreOf(metadataArticle)) {
                        metadataArticle = article;
                    }
                    List<String> additional = clusterLinks.get(article.getId());
                    if (additional != null) {
                        seenLinks.addAll(additional);
                    }
                } else {
                    log.debug("无法解析AI返回的编号: {}", idStr);
                }
            }
            links.addAll(seenLinks);
            if (!links.isEmpty()) {
                result.add(new DigestItem(item.summary, links,
                        metadataArticle != null ? metadataArticle.getAiCategoryDisplayName() : null,
                        metadataArticle != null ? metadataArticle.getImportanceScore() : null));
            }
        }
        return result;
    }

    private Article resolveArticle(String idStr, List<Article> articles) {
        try {
            int idx = Integer.parseInt(idStr.trim());
            if (idx >= 1 && idx <= articles.size()) {
                return articles.get(idx - 1);
            }
            log.warn("AI返回的序号越界: {}, 候选文章数: {}", idx, articles.size());
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private List<DigestItem> deduplicateAcrossItems(List<DigestItem> items) {
        Set<String> seenSummaries = new HashSet<>();
        List<DigestItem> result = new ArrayList<>();
        for (DigestItem item : items) {
            String normalizedSummary = item.summary.replaceAll("\\s+", "").toLowerCase();
            if (seenSummaries.contains(normalizedSummary)) continue;
            seenSummaries.add(normalizedSummary);
            result.add(item);
        }
        return result;
    }

    private List<DigestItem> buildFallbackItems(List<Article> articles, Map<Long, List<String>> clusterLinks) {
        return articles.stream().limit(OUTPUT_LIMIT)
                .map(a -> {
                    List<String> links = new ArrayList<>();
                    if (a.getLink() != null) {
                        links.add(a.getLink());
                        List<String> additional = clusterLinks.get(a.getId());
                        if (additional != null) links.addAll(additional);
                    }
                    return new DigestItem(
                            a.getAiSummary() != null ? a.getAiSummary() : a.getTitle(),
                            links,
                            a.getAiCategoryDisplayName(),
                            a.getImportanceScore());
                })
                .collect(Collectors.toList());
    }

    private List<DigestItem> parseDigestItems(String content) {
        List<DigestItem> items = new ArrayList<>();
        try {
            String stripped = stripMarkdownCodeBlock(content);
            JsonNode itemsArray = findItemsArray(stripped);
            if (itemsArray == null || !itemsArray.isArray()) {
                log.warn("无法从AI筛选响应中提取JSON数组, content前200字符: {}",
                        stripped.length() > 200 ? stripped.substring(0, 200) : stripped);
                return items;
            }
            for (JsonNode item : itemsArray) {
                String summary = item.has("summary") ? item.get("summary").asText() : null;
                if (summary == null || summary.isBlank()) continue;
                List<String> ids = new ArrayList<>();
                JsonNode idsNode = item.get("ids");
                if (idsNode == null || !idsNode.isArray()) {
                    idsNode = item.get("links");
                }
                if (idsNode == null || !idsNode.isArray()) {
                    idsNode = item.get("urls");
                }
                if (idsNode == null || !idsNode.isArray()) {
                    JsonNode idNode = item.get("id");
                    if (idNode != null) {
                        String idStr = idNode.asText().trim();
                        if (!idStr.isEmpty()) ids.add(idStr);
                    }
                }
                if (idsNode != null && idsNode.isArray()) {
                    for (JsonNode id : idsNode) {
                        String idStr = id.asText().trim();
                        if (!idStr.isEmpty()) ids.add(idStr);
                    }
                }
                items.add(new DigestItem(summary, ids, null, null));
            }
        } catch (Exception e) {
            log.warn("AI筛选响应解析失败: {}, content前300字符: {}",
                    e.getMessage(),
                    content.length() > 300 ? content.substring(0, 300) : content);
        }
        log.info("AI筛选解析到 {} 条新闻", items.size());
        return items;
    }

    // ========== 构建摘要 ==========

    private DailyDigest buildDigest(String date, Map<String, List<DigestItem>> categorized) {
        return DailyDigest.builder()
                .digestDate(date)
                .aiCategory(buildCategoryContent(categorized.get("ai")))
                .techCategory(buildCategoryContent(categorized.get("tech")))
                .domesticCategory(buildCategoryContent(categorized.get("domestic")))
                .japanCategory(buildCategoryContent(categorized.get("japan")))
                .internationalCategory(buildCategoryContent(categorized.get("international")))
                .rawAiArticles("")
                .rawTechArticles("")
                .rawDomesticArticles("")
                .rawJapanArticles("")
                .rawInternationalArticles("")
                .generatedAt(CanonicalTime.now())
                .build();
    }

    private String buildCategoryContent(List<DigestItem> items) {
        if (items == null || items.isEmpty()) {
            return "<p>暂无相关新闻</p>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<ol class=\"digest-article-list\">\n");
        for (DigestItem item : items) {
            String summary = item.summary;
            if (summary != null && summary.length() > 100) {
                summary = summary.substring(0, 100) + "...";
            }
            sb.append("  <li>\n");
            sb.append(String.format("    <span class=\"article-summary-text\">%s</span>", escapeHtml(summary)));
            if (item.aiCategoryName != null && !item.aiCategoryName.isBlank()) {
                sb.append(String.format(" <span class=\"badge bg-primary article-ai-badge\">AI分类 · %s</span>",
                        escapeHtml(item.aiCategoryName)));
            }
            if (item.importanceScore != null) {
                sb.append(String.format(" <span class=\"badge bg-warning text-dark article-ai-badge\">%d分</span>",
                        item.importanceScore));
            }
            if (!item.links.isEmpty()) {
                for (int i = 0; i < item.links.size(); i++) {
                    String safeLink = safeHttpUrl(item.links.get(i));
                    if (safeLink != null) {
                        String label = i == 0 ? "查看原文" : "[" + (i + 1) + "]";
                        sb.append(String.format(" <a href=\"%s\" target=\"_blank\" rel=\"noopener noreferrer\" class=\"article-source-link\">%s</a>",
                                escapeHtml(safeLink), label));
                    }
                }
            }
            sb.append("\n  </li>\n");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    // ========== 工具方法 ==========

    private String safeHttpUrl(String value) {
        return httpUrlSafety.safeHttpUrl(value);
    }

    private String truncateText(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text != null ? text : "";
        return text.substring(0, maxLen) + "…";
    }

    private int scoreOf(Article article) {
        return article.getImportanceScore() != null ? article.getImportanceScore() : Integer.MIN_VALUE;
    }

    private String getCategoryDisplayName(String category) {
        return switch (category) {
            case "ai" -> "AI科技";
            case "tech" -> "科技";
            case "domestic" -> "国内";
            case "japan" -> "日本";
            case "international" -> "国际";
            default -> category;
        };
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String extractContent(String jsonBody) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null) {
                    JsonNode contentNode = message.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        if (!content.isEmpty()) return content;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Jackson解析API响应失败，回退手动提取: {}", e.getMessage());
        }
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

    private String stripMarkdownCodeBlock(String content) {
        String trimmed = content.trim();
        Pattern codeBlock = Pattern.compile("```(?:json|JSON)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);
        Matcher m = codeBlock.matcher(trimmed);
        if (m.find()) {
            return m.group(1).trim();
        }
        return trimmed;
    }

    private JsonNode findItemsArray(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node.isArray()) return node;
            if (node.isObject()) {
                if (node.has("items") && node.get("items").isArray()) return node.get("items");
                for (JsonNode child : node) {
                    if (child.isArray()) return child;
                }
            }
        } catch (Exception e) {
            log.debug("直接JSON解析失败，尝试提取: {}", e.getMessage());
        }
        int startIdx = content.indexOf('[');
        int braceIdx = content.indexOf('{');
        String jsonCandidate;
        if (startIdx >= 0 && (braceIdx < 0 || startIdx < braceIdx)) {
            int end = findMatchingBracket(content, startIdx, '[', ']');
            if (end > startIdx) jsonCandidate = content.substring(startIdx, end + 1);
            else return null;
        } else if (braceIdx >= 0) {
            int end = findMatchingBracket(content, braceIdx, '{', '}');
            if (end > braceIdx) jsonCandidate = content.substring(braceIdx, end + 1);
            else return null;
        } else {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(jsonCandidate);
            if (node.isArray()) return node;
            if (node.isObject()) {
                if (node.has("items") && node.get("items").isArray()) return node.get("items");
                for (JsonNode child : node) {
                    if (child.isArray()) return child;
                }
            }
        } catch (Exception e) {
            log.debug("提取后JSON解析也失败: {}", e.getMessage());
        }
        return null;
    }

    private int findMatchingBracket(String s, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inString && i + 1 < s.length()) {
                i++;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    // ========== 数据类 ==========

    private record DigestItem(String summary, List<String> links,
                              String aiCategoryName, Integer importanceScore) {}

    // ========== 查询方法 ==========

    public Optional<DailyDigest> getDigestByDate(String date) {
        return digestRepository.findByDigestDate(date);
    }

    public List<DailyDigestRepository.DigestSummary> getAllDigestSummaries() {
        List<DailyDigestRepository.DigestSummary> summaries =
                new ArrayList<>(digestRepository.findAllByOrderByDigestDateDesc());
        Collections.reverse(summaries);
        return summaries;
    }

    public Page<DailyDigestRepository.DigestSummary> getDigestSummariesPaged(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<DailyDigestRepository.DigestSummary> result =
                digestRepository.findAllByOrderByDigestDateDesc(pageRequest);
        List<DailyDigestRepository.DigestSummary> summaries = new ArrayList<>(result.getContent());
        Collections.reverse(summaries);
        return new PageImpl<>(summaries, pageRequest, result.getTotalElements());
    }
}
