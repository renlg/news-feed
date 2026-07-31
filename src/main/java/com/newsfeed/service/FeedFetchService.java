package com.newsfeed.service;

import com.newsfeed.model.FeedSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedFetchService {

    private final FeedSourceService feedSourceService;
    private final FeedFetchWorker feedFetchWorker;

    private final Set<Long> fetchingSourceIds = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Scheduled(fixedRate = 60000)
    public void fetchDueFeeds() {
        List<FeedSource> enabledSources = feedSourceService.findEnabled();
        LocalDateTime now = LocalDateTime.now();

        for (FeedSource source : enabledSources) {
            if (fetchingSourceIds.contains(source.getId())) {
                log.debug("Feed source {} is still being fetched, skipping", source.getId());
                continue;
            }
            if (isDue(source, now)) {
                fetchingSourceIds.add(source.getId());
                feedFetchWorker.processSource(source);
            }
        }
    }

    public void markFetching(Long sourceId) {
        fetchingSourceIds.add(sourceId);
    }

    public void unmarkFetching(Long sourceId) {
        fetchingSourceIds.remove(sourceId);
    }

    private boolean isDue(FeedSource source, LocalDateTime now) {
        if (source.getLastFetchedAt() == null) {
            return true;
        }
        int interval = source.getFetchIntervalMinutes() != null
                ? source.getFetchIntervalMinutes() : 15;
        return source.getLastFetchedAt().plusMinutes(interval).isBefore(now);
    }
}
