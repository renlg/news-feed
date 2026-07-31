package com.newsfeed.service;

import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FeedFetchWorker {

    private final FeedSourceService feedSourceService;
    private final ArticleService articleService;
    private final List<FeedParser> feedParsers;
    private final FetchLogService fetchLogService;
    private final FeedFetchService feedFetchService;

    @Value("${newsfeed.fetch.max-articles-per-feed:500}")
    private int maxArticlesPerFeed;

    private final Map<String, FeedParser> parserMap = new HashMap<>();

    public FeedFetchWorker(FeedSourceService feedSourceService,
                           ArticleService articleService,
                           List<FeedParser> feedParsers,
                           FetchLogService fetchLogService,
                           @Lazy FeedFetchService feedFetchService) {
        this.feedSourceService = feedSourceService;
        this.articleService = articleService;
        this.feedParsers = feedParsers;
        this.fetchLogService = fetchLogService;
        this.feedFetchService = feedFetchService;
    }

    private FeedParser getParser(String protocol) {
        if (parserMap.isEmpty()) {
            for (FeedParser parser : feedParsers) {
                parserMap.put(parser.supportedProtocol().toUpperCase(), parser);
            }
        }
        return parserMap.getOrDefault(protocol.toUpperCase(),
                parserMap.get("RSS"));
    }

    @Async("fetchExecutor")
    public void processSource(FeedSource source) {
        if (!Boolean.TRUE.equals(source.getEnabled())) {
            log.info("Feed '{}' is disabled, skipping fetch", source.getName());
            return;
        }
        feedFetchService.markFetching(source.getId());
        try {
            int saved = 0;
            try {
                FeedParser parser = getParser(source.getProtocol());
                List<Article> articles = parser.parse(source.getUrl(), source);

                if (!articles.isEmpty()) {
                    int totalParsed = articles.size();
                    LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
                    articles = articles.stream()
                            .filter(a -> a.getPublishedAt() != null && a.getPublishedAt().isAfter(cutoff))
                            .toList();
                    int skippedOld = totalParsed - articles.size();

                    int limit = Math.min(articles.size(), maxArticlesPerFeed);
                    saved = articleService.saveArticles(articles.subList(0, limit));
                    log.info("Feed '{}': parsed {} articles, skipped {} old, saved {} new",
                            source.getName(), totalParsed, skippedOld, saved);
                }

                feedSourceService.updateLastFetchedAt(source.getId());
                fetchLogService.logSuccess(source.getId(), source.getName(), saved);
            } catch (Exception e) {
                log.error("Error processing feed '{}': {}", source.getName(), e.getMessage(), e);
                fetchLogService.logFailure(source.getId(), source.getName(), e.getMessage());
            }
        } finally {
            feedFetchService.unmarkFetching(source.getId());
        }
    }
}
