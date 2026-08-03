package com.newsfeed.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalTimeTest {

    @Test
    void convertsAbsoluteFeedInstantsToShanghaiWallTime() {
        assertThat(CanonicalTime.fromInstant(Instant.parse("2026-08-03T00:00:00Z")))
                .isEqualTo(LocalDateTime.of(2026, 8, 3, 8, 0));
        assertThat(CanonicalTime.fromInstant(Instant.parse("2026-08-02T23:00:00Z")))
                .isEqualTo(LocalDateTime.of(2026, 8, 3, 7, 0));
    }
}
