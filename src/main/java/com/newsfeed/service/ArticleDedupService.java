package com.newsfeed.service;

import com.newsfeed.config.AiConfig;
import com.newsfeed.model.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleDedupService {

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final double EMBEDDING_SIMILARITY_THRESHOLD = 0.85;
    private static final double TITLE_SIMILARITY_THRESHOLD = 0.7;
    private static final int EMBEDDING_BATCH_SIZE = 100;

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

        DedupResult embeddingResult = deduplicateByEmbedding(titleResult.articles(), titleResult.clusterLinks());
        int embeddingRemoved = titleResult.articles().size() - embeddingResult.articles().size();
        if (embeddingRemoved > 0) {
            log.info("语义去重: {} → {} (移除 {} 篇)", titleResult.articles().size(), embeddingResult.articles().size(), embeddingRemoved);
        }

        return embeddingResult;
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

    DedupResult deduplicateByEmbedding(List<Article> articles, Map<Long, List<String>> titleClusterLinks) {
        if (articles.size() <= 1) return new DedupResult(articles, titleClusterLinks);
        if (aiConfig.getKey() == null || aiConfig.getKey().isBlank()) {
            log.info("AI未配置，跳过embedding去重");
            return new DedupResult(articles, titleClusterLinks);
        }

        try {
            List<String> inputs = articles.stream()
                    .map(a -> {
                        String title = a.getTitle() != null ? a.getTitle() : "";
                        String summary = a.getAiSummary() != null ? a.getAiSummary() : "";
                        return title + " " + summary;
                    })
                    .collect(Collectors.toList());

            List<float[]> embeddings = batchGetEmbeddings(inputs);
            if (embeddings == null || embeddings.size() != articles.size()) {
                log.warn("Embedding获取失败或不完整，降级使用标题去重结果");
                return new DedupResult(articles, titleClusterLinks);
            }

            return clusterAndSelect(articles, embeddings, titleClusterLinks);
        } catch (Exception e) {
            log.warn("Embedding去重失败，降级使用标题去重结果: {}", e.getMessage());
            return new DedupResult(articles, titleClusterLinks);
        }
    }

    private DedupResult clusterAndSelect(List<Article> articles, List<float[]> embeddings, Map<Long, List<String>> titleClusterLinks) {
        List<List<Integer>> clusters = new ArrayList<>();
        List<float[]> centers = new ArrayList<>();

        for (int i = 0; i < embeddings.size(); i++) {
            float[] emb = embeddings.get(i);
            int bestCluster = -1;
            double bestSim = -1;

            for (int c = 0; c < clusters.size(); c++) {
                double sim = cosineSimilarity(emb, centers.get(c));
                if (sim > EMBEDDING_SIMILARITY_THRESHOLD && sim > bestSim) {
                    bestSim = sim;
                    bestCluster = c;
                }
            }

            if (bestCluster >= 0) {
                clusters.get(bestCluster).add(i);
                centers.set(bestCluster, computeCenter(clusters.get(bestCluster), embeddings));
            } else {
                List<Integer> newCluster = new ArrayList<>();
                newCluster.add(i);
                clusters.add(newCluster);
                centers.add(emb.clone());
            }
        }

        log.info("Embedding聚类: {} 篇文章 → {} 个事件簇", articles.size(), clusters.size());

        Map<Long, List<String>> clusterLinks = new HashMap<>();
        List<Article> result = new ArrayList<>();
        for (List<Integer> cluster : clusters) {
            Article best = cluster.stream()
                    .map(articles::get)
                    .min((a1, a2) -> isBetter(a1, a2) ? -1 : 1)
                    .orElse(null);
            if (best == null) continue;
            result.add(best);

            List<String> links = new ArrayList<>();
            List<String> bestTitleLinks = titleClusterLinks.get(best.getId());
            if (bestTitleLinks != null) links.addAll(bestTitleLinks);

            for (int idx : cluster) {
                Article a = articles.get(idx);
                if (a.getId().equals(best.getId())) continue;
                if (a.getLink() != null) links.add(a.getLink());
                List<String> memberTitleLinks = titleClusterLinks.get(a.getId());
                if (memberTitleLinks != null) links.addAll(memberTitleLinks);
            }
            if (!links.isEmpty()) {
                clusterLinks.put(best.getId(), links);
            }
        }
        return new DedupResult(result, clusterLinks);
    }

    private List<float[]> batchGetEmbeddings(List<String> inputs) {
        List<float[]> allEmbeddings = new ArrayList<>();

        for (int i = 0; i < inputs.size(); i += EMBEDDING_BATCH_SIZE) {
            List<String> batch = inputs.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, inputs.size()));
            List<float[]> batchResult = callEmbeddingApi(batch);
            if (batchResult == null) return null;
            allEmbeddings.addAll(batchResult);
        }

        return allEmbeddings;
    }

    private List<float[]> callEmbeddingApi(List<String> inputs) {
        try {
            String modelName = aiConfig.getEmbeddingModel() != null && !aiConfig.getEmbeddingModel().isBlank()
                    ? aiConfig.getEmbeddingModel() : "text-embedding-3-small";

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            var inputArray = requestBody.putArray("input");
            inputs.forEach(inputArray::add);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String baseUrl = aiConfig.getBaseUrl().replaceAll("/+$", "");
            if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/embeddings"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + aiConfig.getKey())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Embedding API返回 {}: {}", response.statusCode(), response.body());
                return null;
            }

            return parseEmbeddings(response.body());
        } catch (Exception e) {
            log.warn("Embedding API调用失败: {}", e.getMessage());
            return null;
        }
    }

    private List<float[]> parseEmbeddings(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return null;

            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embeddingNode = item.get("embedding");
                if (embeddingNode == null || !embeddingNode.isArray()) return null;
                float[] vec = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    vec[i] = (float) embeddingNode.get(i).asDouble();
                }
                embeddings.add(vec);
            }
            return embeddings;
        } catch (Exception e) {
            log.warn("Embedding响应解析失败: {}", e.getMessage());
            return null;
        }
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

    static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private float[] computeCenter(List<Integer> indices, List<float[]> embeddings) {
        int dim = embeddings.get(indices.get(0)).length;
        float[] center = new float[dim];
        for (int idx : indices) {
            float[] emb = embeddings.get(idx);
            for (int j = 0; j < dim; j++) center[j] += emb[j];
        }
        for (int j = 0; j < dim; j++) center[j] /= indices.size();
        return center;
    }
}
