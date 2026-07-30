package com.newsfeed.config;

import com.newsfeed.repository.FeedSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final FeedSourceRepository feedSourceRepository;

    @Override
    public void run(String... args) {
        long count = feedSourceRepository.count();
        log.info("Feed sources check complete. Total: {}", count);
        if (count == 0) {
            log.info("No feed sources found. Add sources via the UI at /feeds.");
        }
    }
}
