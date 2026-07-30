package com.newsfeed.service;

import com.newsfeed.config.AiConfig;
import com.newsfeed.model.Article;
import com.newsfeed.model.DailyDigest;
import com.newsfeed.repository.ArticleRepository;
import com.newsfeed.repository.DailyDigestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 候选池大小：国内200条，其他分类100条
    private static final int DOMESTIC_CANDIDATES = 200;
    private static final int OTHER_CANDIDATES = 100;
    // 最低分数线
    private static final int MIN_SCORE = 5;
    // AI最终输出条数
    private static final int OUTPUT_LIMIT = 10;

    // 每天早上9点执行
    @Scheduled(cron = "0 0 9 * * ?")
    public void generateDailyDigest() {
        log.info("开始生成每日新闻摘要...");
        String today = LocalDate.now().format(DATE_FORMATTER);
        if (digestRepository.existsByDigestDate(today)) {
            log.info("今日摘要已生成，跳过");
            return;
        }
        doGenerate(today);
    }

    // 手动触发生成（强制重新生成）
    public void forceGenerateDigest() {
        log.info("手动触发生成每日新闻摘要...");
        String today = LocalDate.now().format(DATE_FORMATTER);
        if (digestRepository.existsByDigestDate(today)) {
            digestRepository.deleteByDigestDate(today);
            log.info("已删除今日旧摘要记录");
        }
        doGenerate(today);
    }

    private void doGenerate(String today) {
        try {
            LocalDateTime until = LocalDateTime.of(LocalDate.now(), LocalTime.of(9, 0));
            LocalDateTime since = until.minusHours(24);

            // 查询各分类的高分文章（分数>5），按分数降序
            Map<String, List<Article>> candidates = new HashMap<>();
            candidates.put("domestic", articleRepository.findHighScoringByCategory("domestic", MIN_SCORE, since, until)
                    .stream().limit(DOMESTIC_CANDIDATES).collect(Collectors.toList()));
            candidates.put("ai", articleRepository.findHighScoringByCategory("ai", MIN_SCORE, since, until)
                    .stream().limit(OTHER_CANDIDATES).collect(Collectors.toList()));
            candidates.put("tech", articleRepository.findHighScoringByCategory("tech", MIN_SCORE, since, until)
                    .stream().limit(OTHER_CANDIDATES).collect(Collectors.toList()));
            candidates.put("japan", articleRepository.findHighScoringByCategory("japan", MIN_SCORE, since, until)
                    .stream().limit(OTHER_CANDIDATES).collect(Collectors.toList()));
            candidates.put("international", articleRepository.findHighScoringByCategory("international", MIN_SCORE, since, until)
                    .stream().limit(OTHER_CANDIDATES).collect(Collectors.toList()));

            int total = candidates.values().stream().mapToInt(List::size).sum();
            log.info("高分文章候选: domestic={}, ai={}, tech={}, japan={}, international={}, 总计={}",
                    candidates.get("domestic").size(), candidates.get("ai").size(),
                    candidates.get("tech").size(), candidates.get("japan").size(),
                    candidates.get("international").size(), total);

            if (total == 0) {
                log.warn("没有高分文章，跳过生成摘要");
                return;
            }

            // 对每个分类调用AI筛选+合并相似新闻
            Map<String, List<DigestItem>> finalItems = new HashMap<>();
            for (Map.Entry<String, List<Article>> entry : candidates.entrySet()) {
                String category = entry.getKey();
                List<Article> articles = entry.getValue();
                if (articles.isEmpty()) {
                    finalItems.put(category, Collections.emptyList());
                    continue;
                }
                log.info("AI筛选合并 {} 分类: {} 篇候选文章", category, articles.size());
                List<DigestItem> items = selectAndMergeWithAI(category, articles);
                finalItems.put(category, items);
                log.info("{} 分类最终: {} 条新闻", category, items.size());
            }

            // 生成摘要
            DailyDigest digest = buildDigest(today, finalItems);
            digestRepository.save(digest);
            log.info("每日新闻摘要生成完成: {}", today);
        } catch (Exception e) {
            log.error("生成每日新闻摘要失败: {}", e.getMessage(), e);
        }
    }

    // ========== AI筛选+合并 ==========

    private List<DigestItem> selectAndMergeWithAI(String category, List<Article> articles) {
        if (articles.isEmpty()) return Collections.emptyList();
        if (aiConfig.getKey() == null || aiConfig.getKey().isBlank()) {
            log.warn("AI未配置，直接使用文章摘要");
            return articles.stream().limit(OUTPUT_LIMIT)
                    .map(a -> new DigestItem(a.getAiSummary() != null ? a.getAiSummary() : a.getTitle(),
                            a.getLink() != null ? List.of(a.getLink()) : List.of()))
                    .collect(Collectors.toList());
        }

        try {
            StringBuilder articleList = new StringBuilder();
            for (Article a : articles) {
                String title = a.getTitle() != null ? a.getTitle() : "";
                String summary = a.getAiSummary() != null ? a.getAiSummary() : "";
                String link = a.getLink() != null ? a.getLink() : "";
                articleList.append(String.format("- ID=%d | 标题: %s | 摘要: %s | 链接: %s\n",
                        a.getId(), escapeJson(title), escapeJson(summary), escapeJson(link)));
            }

            String categoryName = getCategoryDisplayName(category);
            String systemPrompt = String.format("""
                    你是一个资深新闻编辑。以下是过去24小时内「%s」类别的高分候选新闻（共%d条）。
                    请完成以下任务：
                    1. 从中筛选出最重要、最有价值的新闻（最多%d条）
                    2. 如果有多篇报道的是同一个事件或高度相似的新闻，请合并成一条（摘要综合各报道内容，链接保留所有来源）
                    3. 按重要性从高到低排序

                    请返回JSON格式：
                    {"items": [{"summary": "100字以内的中文摘要", "links": ["链接1", "链接2"]}, ...]}

                    注意：
                    - 摘要不超过100个中文字，概括核心事实
                    - 合并相似新闻时，摘要要综合各报道的信息
                    - links数组包含所有相关报道的原始链接
                    - 只返回JSON，不要返回其他内容
                    """, categoryName, articles.size(), OUTPUT_LIMIT);

            String modelName = aiConfig.getModel() != null && !aiConfig.getModel().isBlank()
                    ? aiConfig.getModel() : "gpt-4o-mini";
            String jsonBody = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"候选新闻：\\n%s\"}],\"temperature\":0.2,\"max_tokens\":4000}",
                    modelName, escapeJson(systemPrompt), escapeJson(articleList.toString()));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

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
                    return parseDigestItems(content);
                }
                log.warn("AI筛选返回内容为空");
            } else {
                log.warn("AI筛选API返回状态 {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("AI筛选失败({}): {}", category, e.getMessage());
        }

        // AI失败时的降级处理
        return articles.stream().limit(OUTPUT_LIMIT)
                .map(a -> new DigestItem(a.getAiSummary() != null ? a.getAiSummary() : a.getTitle(),
                        a.getLink() != null ? List.of(a.getLink()) : List.of()))
                .collect(Collectors.toList());
    }

    private List<DigestItem> parseDigestItems(String content) {
        List<DigestItem> items = new ArrayList<>();
        String jsonStr = extractJsonArray(content);
        if (jsonStr == null) {
            log.warn("无法从AI筛选响应中提取JSON数组");
            return items;
        }

        // 解析每个item: {"summary": "...", "links": ["..."]}
        Pattern itemPattern = Pattern.compile(
                "\"summary\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"links\"\\s*:\\s*\\[([^\\]]*)]");
        Matcher matcher = itemPattern.matcher(jsonStr);

        while (matcher.find()) {
            try {
                String summary = matcher.group(1);
                summary = summary.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");

                String linksStr = matcher.group(2);
                List<String> links = new ArrayList<>();
                Pattern linkPattern = Pattern.compile("\"([^\"]+)\"");
                Matcher linkMatcher = linkPattern.matcher(linksStr);
                while (linkMatcher.find()) {
                    String link = linkMatcher.group(1).trim();
                    if (!link.isEmpty()) links.add(link);
                }
                items.add(new DigestItem(summary, links));
            } catch (Exception e) {
                log.warn("解析摘要条目失败: {}", e.getMessage());
            }
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
                .generatedAt(LocalDateTime.now())
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
            if (!item.links.isEmpty()) {
                if (item.links.size() == 1) {
                    sb.append(String.format(" <a href=\"%s\" target=\"_blank\" class=\"article-source-link\">查看原文</a>",
                            escapeHtml(item.links.get(0))));
                } else {
                    sb.append(String.format(" <a href=\"%s\" target=\"_blank\" class=\"article-source-link\">查看原文</a>",
                            escapeHtml(item.links.get(0))));
                    for (int i = 1; i < item.links.size(); i++) {
                        sb.append(String.format(" <a href=\"%s\" target=\"_blank\" class=\"article-source-link\">[%d]</a>",
                                escapeHtml(item.links.get(i)), i + 1));
                    }
                }
            }
            sb.append("\n  </li>\n");
        }
        sb.append("</ol>");
        return sb.toString();
    }

    // ========== 工具方法 ==========

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

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

    private String extractJsonArray(String content) {
        int startIdx = content.indexOf("[");
        int endIdx = content.lastIndexOf("]");
        if (startIdx >= 0 && endIdx > startIdx) {
            return content.substring(startIdx, endIdx + 1);
        }
        // 尝试提取JSON对象中的items数组
        int itemsIdx = content.indexOf("\"items\"");
        if (itemsIdx >= 0) {
            int arrStart = content.indexOf("[", itemsIdx);
            int arrEnd = content.lastIndexOf("]");
            if (arrStart >= 0 && arrEnd > arrStart) {
                return content.substring(arrStart, arrEnd + 1);
            }
        }
        return null;
    }

    private String extractJsonFromContent(String content) {
        int startIdx = content.indexOf("{");
        int endIdx = content.lastIndexOf("}");
        if (startIdx >= 0 && endIdx > startIdx) {
            return content.substring(startIdx, endIdx + 1);
        }
        return null;
    }

    // ========== 数据类 ==========

    private record DigestItem(String summary, List<String> links) {}

    // ========== 查询方法 ==========

    public Optional<DailyDigest> getDigestByDate(String date) {
        return digestRepository.findByDigestDate(date);
    }

    public Optional<DailyDigest> getLatestDigest() {
        return digestRepository.findAll().stream()
                .max(Comparator.comparing(DailyDigest::getDigestDate))
                .map(Optional::of)
                .orElse(Optional.empty());
    }

    public List<DailyDigest> getAllDigests() {
        return digestRepository.findAll().stream()
                .sorted(Comparator.comparing(DailyDigest::getDigestDate).reversed())
                .collect(Collectors.toList());
    }
}
