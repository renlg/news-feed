package com.newsfeed.controller;

import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;
import com.newsfeed.service.ArticleAiService;
import com.newsfeed.service.ArticleService;
import com.newsfeed.service.FeedFetchWorker;
import com.newsfeed.service.FeedSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/feeds")
@RequiredArgsConstructor
public class FeedApiController {

    private final FeedSourceService feedSourceService;
    private final ArticleService articleService;
    private final FeedFetchWorker feedFetchWorker;
    private final ArticleAiService articleAiService;

    @GetMapping
    public ResponseEntity<?> listFeeds() {
        return ResponseEntity.ok(feedSourceService.findAll());
    }

    @PostMapping
    public ResponseEntity<?> addFeed(@RequestBody FeedSource source) {
        if (source.getProtocol() == null || source.getProtocol().isBlank()) {
            source.setProtocol("RSS");
        }
        if (source.getFetchIntervalMinutes() == null) {
            source.setFetchIntervalMinutes(15);
        }
        if (source.getEnabled() == null) {
            source.setEnabled(true);
        }
        FeedSource saved = feedSourceService.save(source);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateFeed(@PathVariable Long id, @RequestBody FeedSource source) {
        return feedSourceService.findById(id)
                .map(existing -> {
                    existing.setName(source.getName());
                    existing.setUrl(source.getUrl());
                    existing.setProtocol(source.getProtocol());
                    existing.setFetchIntervalMinutes(source.getFetchIntervalMinutes());
                    return ResponseEntity.ok(feedSourceService.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFeed(@PathVariable Long id) {
        feedSourceService.delete(id);
        return ResponseEntity.ok(Map.of("message", "Deleted successfully"));
    }

    @PostMapping("/{id}/fetch")
    public ResponseEntity<?> fetchNow(@PathVariable Long id) {
        return feedSourceService.findById(id)
                .map(source -> {
                    feedFetchWorker.processSource(source);
                    return ResponseEntity.ok(Map.of("message", "Fetch triggered"));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/articles/search")
    public ResponseEntity<?> searchArticles(
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "sourceId", required = false) Long sourceId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sort", defaultValue = "newest") String sort) {

        Page<Article> articles = articleService.search(keyword, sourceId, category, page, size, sort);
        return ResponseEntity.ok(articles);
    }

//    @PostMapping("/ai/process")
//    public ResponseEntity<?> triggerAiProcessing() {
//        int count = articleAiService.triggerProcessing();
//        if (count == 0) {
//            return ResponseEntity.ok(Map.of("message", "没有待处理的文章"));
//        }
//        return ResponseEntity.ok(Map.of("message", "已触发AI处理", "count", count));
//    }

//    @GetMapping("/ai/stats")
//    public ResponseEntity<?> getAiStats() {
//        return ResponseEntity.ok(articleAiService.getStats());
//    }

//    @PostMapping("/ai/reset")
//    public ResponseEntity<?> resetAiProcessing() {
//        int count = articleAiService.resetTodayProcessing();
//        return ResponseEntity.ok(Map.of("message", "已重置AI处理状态", "count", count));
//    }

//    @PostMapping("/ai/enable-all")
//    public ResponseEntity<?> enableAiCategorizeForAll() {
//        int count = feedSourceService.enableAiCategorizeForAll();
//        return ResponseEntity.ok(Map.of("message", "已启用AI分类", "count", count));
//    }
}
