package com.newsfeed.service.rss;

import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;
import com.newsfeed.service.AiCategoryService;
import com.newsfeed.service.FeedParser;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RssFeedParser implements FeedParser {

    private final AiCategoryService aiCategoryService;

    @Override
    public String supportedProtocol() {
        return "RSS";
    }

    @Override
    public List<Article> parse(String url, FeedSource source) {
        List<Article> articles = new ArrayList<>();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "NewsFeed/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            SAXBuilder saxBuilder = new SAXBuilder();
            saxBuilder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            saxBuilder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            try (InputStream is = response.body()) {
                Document jdomDoc = saxBuilder.build(new XmlReader(is));

                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(jdomDoc);

                for (SyndEntry entry : feed.getEntries()) {
                    String content = null;
                    if (entry.getContents() != null && !entry.getContents().isEmpty()) {
                        content = entry.getContents().get(0).getValue();
                    } else if (entry.getDescription() != null) {
                        content = entry.getDescription().getValue();
                    }

                    String summary = null;
                    if (entry.getDescription() != null) {
                        summary = entry.getDescription().getValue();
                    }

                    LocalDateTime publishedAt = LocalDateTime.now();
                    Date publishedDate = entry.getPublishedDate();
                    if (publishedDate == null) {
                        publishedDate = entry.getUpdatedDate();
                    }
                    if (publishedDate != null) {
                        publishedAt = publishedDate.toInstant()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime();
                    }

                    Article article = Article.builder()
                            .title(entry.getTitle())
                            .link(entry.getLink())
                            .content(content)
                            .summary(summary)
                            .author(entry.getAuthor())
                            .publishedAt(publishedAt)
                            .fetchedAt(LocalDateTime.now())
                            .feedSourceId(source.getId())
                            .build();

                    if (entry.getCategories() != null && !entry.getCategories().isEmpty()) {
                        String categories = entry.getCategories().stream()
                                .map(c -> c.getName())
                                .filter(name -> name != null && !name.trim().isEmpty())
                                .map(String::trim)
                                .distinct()
                                .reduce((a, b) -> a + "," + b)
                                .orElse("");
                        if (!categories.isEmpty()) {
                            article.setCategory(categories);
                        }
                    }

                    if (Boolean.TRUE.equals(source.getAiCategorize())
                            && (article.getCategory() == null || article.getCategory().isBlank())) {
                        String aiCategory = aiCategoryService.categorize(summary, content);
                        if (aiCategory != null && !aiCategory.isBlank()) {
                            article.setCategory(aiCategory);
                        }
                    }

                    articles.add(article);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse feed from {}: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to parse feed from " + url + ": " + e.getMessage(), e);
        }
        return articles;
    }
}
