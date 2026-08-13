package com.newsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newsfeed.config.AiConfig;
import com.newsfeed.model.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleDedupService {

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int AI_DEDUP_BATCH_SIZE = 25;
    private static final int AI_DEDUP_CROSS_BATCH_STRIDE = 20;
    private static final int AI_DEDUP_TITLE_LIMIT = 80;
    private static final int AI_DEDUP_SUMMARY_LIMIT = 100;
    private static final Duration AI_DEDUP_TIMEOUT = Duration.ofSeconds(90);

    private static final String AI_DEDUP_SYSTEM_PROMPT = """
            你是严谨的新闻编辑。判断输入文章中哪些描述的是同一个具体事件或同一条新闻，而不只是主题、人物或领域相似。
            仅将确实属于同一事件的文章放进同一簇；拿不准时不要合并。没有重复的文章不要输出。
            输出必须且只能是一个合法JSON对象，其中clusters是二维文章id数组；禁止输出说明、注释、Markdown代码围栏或任何前后缀文字。
            必须严格使用以下紧凑格式（示例）：{"clusters":[[123,456],[789,1011,1213]]}。若没有重复，输出{"clusters":[]}。
            每个重复簇至少包含两个输入中的id；只能使用输入中实际存在的id，不得编造、改写或遗漏重复簇中的id。
            严格保证JSON语法有效。文本值中不得出现未转义的ASCII双引号；将文本内所有ASCII双引号替换为全角「」引号（若仍使用ASCII双引号，必须转义为\\\"）。
            """;

    private static final Pattern PREFIX_PATTERN = Pattern.compile(
            "^(独家|快讯|突发|重磅|最新|紧急|速递|关注|热点|焦点|爆料|官方|刚刚|今日|明日|" +
            "早报|晚报|日报|午报|晨报|速报|头条|要闻| bulletin|breaking|exclusive)");
    private static final Pattern PUNCT_PATTERN = Pattern.compile(
            "[\\s\\p{Punct}\uFF01\uFF1F\u3002\u3001\uFF0C\uFF1B\uFF1A\u201C\u201D\u2018\u2019" +
            "\u3010\u3011\uFF08\uFF09\u300A\u300B\u300C\u300D\u300E\u300F\u3008\u3009" +
            "\u2026\u2014\\-|\u00B7~\uFF09]+");

    public record DedupResult(List<Article> articles, Map<Long, List<String>> clusterLinks) {
        public DedupResult {
            clusterLinks = clusterLinks != null ? new HashMap<>(clusterLinks) : new HashMap<>();
        }
    }

    public DedupResult deduplicate(List<Article> articles) {
        if (articles.size() <= 1) return new DedupResult(articles, Map.of());

        DedupResult titleResult = deduplicateByTitle(articles);
        int titleRemoved = articles.size() - titleResult.articles().size();
        if (titleRemoved > 0) {
            log.info("标题去重: {} → {} (移除 {} 篇)", articles.size(), titleResult.articles().size(), titleRemoved);
        }

        DedupResult semanticResult = deduplicateByAi(titleResult.articles(), titleResult.clusterLinks());
        int semanticRemoved = titleResult.articles().size() - semanticResult.articles().size();
        if (semanticRemoved > 0) {
            log.info("语义去重: {} → {} (移除 {} 篇)", titleResult.articles().size(), semanticResult.articles().size(), semanticRemoved);
        }

        return semanticResult;
    }

    String normalizeTitle(String title) {
        if (title == null) return "";
        String normalized = title.toLowerCase();
        normalized = PREFIX_PATTERN.matcher(normalized).replaceAll("");
        normalized = PUNCT_PATTERN.matcher(normalized).replaceAll("");
        return normalized.trim();
    }

    DedupResult deduplicateByTitle(List<Article> articles) {
        Map<String, Article> bestByNormalized = new LinkedHashMap<>();
        Map<Long, List<String>> clusterLinks = new HashMap<>();

        for (Article article : articles) {
            String normalized = normalizeTitle(article.getTitle());
            if (normalized.isEmpty()) {
                bestByNormalized.put("id_" + article.getId(), article);
                continue;
            }
            Article existing = bestByNormalized.get(normalized);
            if (existing == null) {
                bestByNormalized.put(normalized, article);
            } else if (isBetter(article, existing)) {
                List<String> links = new ArrayList<>();
                if (existing.getLink() != null) links.add(existing.getLink());
                List<String> existingLinks = clusterLinks.remove(existing.getId());
                if (existingLinks != null) links.addAll(existingLinks);
                if (!links.isEmpty()) {
                    clusterLinks.computeIfAbsent(article.getId(), k -> new ArrayList<>()).addAll(links);
                }
                bestByNormalized.put(normalized, article);
            } else {
                if (article.getLink() != null) {
                    clusterLinks.computeIfAbsent(existing.getId(), k -> new ArrayList<>()).add(article.getLink());
                }
            }
        }
        return new DedupResult(new ArrayList<>(bestByNormalized.values()), clusterLinks);
    }

    private DedupResult deduplicateByAi(List<Article> articles,
                                        Map<Long, List<String>> titleClusterLinks) {
        if (articles.size() <= 1) return new DedupResult(articles, titleClusterLinks);
        if (aiConfig.getKey() == null || aiConfig.getKey().isBlank()) {
            log.warn("AI语义去重失败，降级使用标题去重结果: AI未配置");
            return new DedupResult(articles, titleClusterLinks);
        }

        try {
            Map<Long, Article> articlesById = new LinkedHashMap<>();
            for (Article article : articles) {
                if (article.getId() == null || articlesById.put(article.getId(), article) != null) {
                    throw new IllegalArgumentException("文章id为空或重复");
                }
            }

            DisjointSet clusters = new DisjointSet(articlesById.keySet());
            processBatches(articles, AI_DEDUP_BATCH_SIZE, clusters);

            if (articles.size() > AI_DEDUP_BATCH_SIZE) {
                List<Article> representatives = selectRepresentatives(articles, clusters);
                representatives.sort(Comparator
                        .comparing((Article article) -> normalizeTitle(article.getTitle()))
                        .thenComparing(Article::getId));
                processBatches(representatives, AI_DEDUP_CROSS_BATCH_STRIDE, clusters);
            }

            DedupResult result = selectAndMerge(articles, clusters, titleClusterLinks);
            log.info("AI语义去重: {} 篇文章 → {} 个事件簇", articles.size(), result.articles().size());
            return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("AI语义去重失败，降级使用标题去重结果: {}", e.getMessage());
            return new DedupResult(articles, titleClusterLinks);
        }
    }

    private void processBatches(List<Article> articles, int stride, DisjointSet clusters)
            throws Exception {
        if (articles.size() <= 1) return;
        for (int start = 0; start < articles.size(); start += stride) {
            int end = Math.min(start + AI_DEDUP_BATCH_SIZE, articles.size());
            List<Article> batch = articles.subList(start, end);
            for (List<Long> duplicateIds : requestDuplicateClusters(batch)) {
                Long first = duplicateIds.get(0);
                for (int i = 1; i < duplicateIds.size(); i++) {
                    clusters.union(first, duplicateIds.get(i));
                }
            }
            if (end == articles.size()) break;
        }
    }

    private List<List<Long>> requestDuplicateClusters(List<Article> batch) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", aiConfig.getModel());
        requestBody.put("temperature", 0);
        ObjectNode responseFormat = requestBody.putObject("response_format");
        responseFormat.put("type", "json_object");

        ArrayNode messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", AI_DEDUP_SYSTEM_PROMPT);
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode inputArticles = input.putArray("articles");
        Set<Long> allowedIds = new HashSet<>();
        for (Article article : batch) {
            allowedIds.add(article.getId());
            ObjectNode item = inputArticles.addObject();
            item.put("id", article.getId());
            item.put("title", truncate(article.getTitle(), AI_DEDUP_TITLE_LIMIT));
            item.put("summary", truncate(article.getAiSummary(), AI_DEDUP_SUMMARY_LIMIT));
        }
        messages.addObject().put("role", "user").put("content", objectMapper.writeValueAsString(input));

        HttpResponse<String> response = AiChatClient.send(
                aiConfig, objectMapper, requestBody, AI_DEDUP_TIMEOUT, "AI语义去重");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("chat API返回HTTP " + response.statusCode());
        }
        return parseDuplicateClusters(response.body(), allowedIds);
    }

    private List<List<Long>> parseDuplicateClusters(String responseBody, Set<Long> allowedIds)
            throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalArgumentException("chat响应缺少choices");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalArgumentException("chat响应内容为空");
        }

        JsonNode result = objectMapper.readTree(content);
        JsonNode clustersNode = result.get("clusters");
        if (clustersNode == null || !clustersNode.isArray()) {
            throw new IllegalArgumentException("chat响应缺少clusters数组");
        }

        List<List<Long>> duplicateClusters = new ArrayList<>();
        for (JsonNode clusterNode : clustersNode) {
            if (!clusterNode.isArray()) {
                throw new IllegalArgumentException("clusters成员不是数组");
            }
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (JsonNode idNode : clusterNode) {
                if (!idNode.canConvertToLong()) {
                    throw new IllegalArgumentException("cluster包含无效文章id");
                }
                long id = idNode.longValue();
                if (!allowedIds.contains(id)) {
                    throw new IllegalArgumentException("cluster包含非输入文章id: " + id);
                }
                ids.add(id);
            }
            if (ids.size() < 2) {
                throw new IllegalArgumentException("重复簇必须至少包含两个不同文章id");
            }
            duplicateClusters.add(new ArrayList<>(ids));
        }
        return duplicateClusters;
    }

    private List<Article> selectRepresentatives(List<Article> articles, DisjointSet clusters) {
        Map<Long, Article> bestByRoot = new LinkedHashMap<>();
        for (Article article : articles) {
            Long root = clusters.find(article.getId());
            Article current = bestByRoot.get(root);
            if (current == null || isBetter(article, current)) {
                bestByRoot.put(root, article);
            }
        }
        return new ArrayList<>(bestByRoot.values());
    }

    private DedupResult selectAndMerge(List<Article> articles, DisjointSet clusters,
                                       Map<Long, List<String>> titleClusterLinks) {
        Map<Long, List<Article>> membersByRoot = new LinkedHashMap<>();
        for (Article article : articles) {
            membersByRoot.computeIfAbsent(clusters.find(article.getId()), key -> new ArrayList<>())
                    .add(article);
        }

        List<Article> result = new ArrayList<>();
        Map<Long, List<String>> clusterLinks = new HashMap<>();
        for (List<Article> members : membersByRoot.values()) {
            Article best = members.get(0);
            for (int i = 1; i < members.size(); i++) {
                if (isBetter(members.get(i), best)) best = members.get(i);
            }
            result.add(best);

            List<String> links = new ArrayList<>();
            List<String> bestTitleLinks = titleClusterLinks.get(best.getId());
            if (bestTitleLinks != null) links.addAll(bestTitleLinks);
            for (Article member : members) {
                if (member.getId().equals(best.getId())) continue;
                if (member.getLink() != null) links.add(member.getLink());
                List<String> memberTitleLinks = titleClusterLinks.get(member.getId());
                if (memberTitleLinks != null) links.addAll(memberTitleLinks);
            }
            if (!links.isEmpty()) clusterLinks.put(best.getId(), links);
        }
        return new DedupResult(result, clusterLinks);
    }

    private String truncate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private boolean isBetter(Article a1, Article a2) {
        int s1 = a1.getImportanceScore() != null ? a1.getImportanceScore() : 0;
        int s2 = a2.getImportanceScore() != null ? a2.getImportanceScore() : 0;
        if (s1 != s2) return s1 > s2;
        LocalDateTime t1 = a1.getPublishedAt() != null ? a1.getPublishedAt()
                : (a1.getFetchedAt() != null ? a1.getFetchedAt() : LocalDateTime.MIN);
        LocalDateTime t2 = a2.getPublishedAt() != null ? a2.getPublishedAt()
                : (a2.getFetchedAt() != null ? a2.getFetchedAt() : LocalDateTime.MIN);
        return t1.isAfter(t2);
    }

    private static final class DisjointSet {
        private final Map<Long, Long> parent = new HashMap<>();

        private DisjointSet(Set<Long> ids) {
            ids.forEach(id -> parent.put(id, id));
        }

        private Long find(Long id) {
            Long currentParent = parent.get(id);
            if (currentParent == null) throw new IllegalArgumentException("未知文章id: " + id);
            if (!currentParent.equals(id)) parent.put(id, find(currentParent));
            return parent.get(id);
        }

        private void union(Long first, Long second) {
            Long firstRoot = find(first);
            Long secondRoot = find(second);
            if (!firstRoot.equals(secondRoot)) parent.put(secondRoot, firstRoot);
        }
    }
}
