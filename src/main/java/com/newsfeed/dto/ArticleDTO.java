package com.newsfeed.dto;

import java.time.LocalDateTime;

public record ArticleDTO(
        Long id,
        String title,
        String link,
        String summary,
        String author,
        String category,
        String aiCategoryDisplayName,
        Boolean aiProcessed,
        Integer importanceScore,
        String aiSummary,
        LocalDateTime publishedAt,
        LocalDateTime fetchedAt,
        Long feedSourceId,
        String feedSourceName) {
}
