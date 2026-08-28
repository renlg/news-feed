package com.newsfeed.dto;

import java.time.LocalDateTime;
import java.util.List;

public record FeedSourceDTO(
        Long id,
        String name,
        String url,
        String safeUrl,
        Boolean enabled,
        String protocol,
        Integer fetchIntervalMinutes,
        Boolean aiCategorize,
        LocalDateTime createdAt,
        LocalDateTime lastFetchedAt,
        List<TagDTO> tags,
        long articleCount) {

    public record TagDTO(Long id, String name, String color) {
    }
}
