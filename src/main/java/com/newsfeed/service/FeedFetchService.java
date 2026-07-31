package com.newsfeed.service;

import com.newsfeed.model.FeedSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedFetchService {

    private final FeedSourceService feedSourceService;
    private final FeedFetchWorker feedFetchWorker;

    @Scheduled(fixedRate = 60000)
    public void fetchDueFeeds() {
        List<FeedSource> enabledSources = feedSourceService.findEnabled();
        LocalDateTime now = LocalDateTime.now();

        for (FeedSource source : enabledSources) {
            if (isDue(source, now)) {
                feedFetchWorker.processSource(source);
            }
        }
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
