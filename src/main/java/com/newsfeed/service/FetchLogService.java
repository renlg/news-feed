package com.newsfeed.service;

import com.newsfeed.model.FetchLog;
import com.newsfeed.repository.FetchLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FetchLogService {

    private final FetchLogRepository fetchLogRepository;

    @Transactional
    public void logSuccess(Long sourceId, String name, int articlesSaved) {
        FetchLog logEntry = FetchLog.builder()
                .feedSourceId(sourceId)
                .feedSourceName(name)
                .status("SUCCESS")
                .articlesSaved(articlesSaved)
                .loggedAt(LocalDateTime.now())
                .build();
        fetchLogRepository.save(logEntry);
    }

    @Transactional
    public void logFailure(Long sourceId, String name, String errorMessage) {
        FetchLog logEntry = FetchLog.builder()
                .feedSourceId(sourceId)
                .feedSourceName(name)
                .status("FAILURE")
                .errorMessage(errorMessage != null && errorMessage.length() > 2000
                        ? errorMessage.substring(0, 2000) : errorMessage)
                .articlesSaved(0)
                .loggedAt(LocalDateTime.now())
                .build();
        fetchLogRepository.save(logEntry);
    }

    public List<DailyStat> getDailyStats(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        List<Object[]> raw = fetchLogRepository.dailyStats(startDate);

        Map<LocalDate, DailyStat> statMap = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            statMap.put(date, new DailyStat(date.toString(), 0, 0));
        }

        for (Object[] row : raw) {
            java.sql.Date sqlDate = (java.sql.Date) row[0];
            LocalDate date = sqlDate.toLocalDate();
            long failures = ((Number) row[1]).longValue();
            long successes = ((Number) row[2]).longValue();
            statMap.put(date, new DailyStat(date.toString(), failures, successes));
        }

        List<DailyStat> stats = new ArrayList<>(statMap.values());
        Collections.reverse(stats);
        return stats;
    }

    public List<SourceRank> getFailureRanking(int days, int limit) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        List<Object[]> raw = fetchLogRepository.failureRanking(startDate, limit);
        List<SourceRank> ranking = new ArrayList<>();
        for (Object[] row : raw) {
            Long sourceId = ((Number) row[0]).longValue();
            String sourceName = (String) row[1];
            long failureCount = ((Number) row[2]).longValue();
            long successCount = row[3] != null ? ((Number) row[3]).longValue() : 0;
            ranking.add(new SourceRank(sourceId, sourceName, failureCount, successCount));
        }
        return ranking;
    }

    @Transactional
    public void cleanupOldLogs(int daysToKeep) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysToKeep);
        fetchLogRepository.deleteByLoggedAtBefore(cutoff);
        log.info("Cleaned up fetch logs older than {}", cutoff);
    }

    public static class DailyStat {
        private final String date;
        private final long failures;
        private final long successes;

        public DailyStat(String date, long failures, long successes) {
            this.date = date;
            this.failures = failures;
            this.successes = successes;
        }

        public String getDate() { return date; }
        public long getFailures() { return failures; }
        public long getSuccesses() { return successes; }
        public long getTotal() { return failures + successes; }
    }

    public static class SourceRank {
        private final Long sourceId;
        private final String sourceName;
        private final long failureCount;
        private final long successCount;

        public SourceRank(Long sourceId, String sourceName, long failureCount, long successCount) {
            this.sourceId = sourceId;
            this.sourceName = sourceName;
            this.failureCount = failureCount;
            this.successCount = successCount;
        }

        public Long getSourceId() { return sourceId; }
        public String getSourceName() { return sourceName; }
        public long getFailureCount() { return failureCount; }
        public long getSuccessCount() { return successCount; }
        public long getTotal() { return failureCount + successCount; }
        public double getFailureRate() {
            return getTotal() > 0 ? (double) failureCount / getTotal() * 100 : 0;
        }
    }
}
