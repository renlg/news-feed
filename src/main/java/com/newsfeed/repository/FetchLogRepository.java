package com.newsfeed.repository;

import com.newsfeed.model.FetchLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FetchLogRepository extends JpaRepository<FetchLog, Long> {

    @Query(value = "SELECT CAST(f.logged_at AS DATE) as d, COUNT(*) as cnt " +
           "FROM fetch_log f " +
           "WHERE f.status = 'FAILURE' AND f.logged_at >= :startDate " +
           "GROUP BY CAST(f.logged_at AS DATE) " +
           "ORDER BY d",
           nativeQuery = true)
    List<Object[]> countDailyFailures(@Param("startDate") LocalDateTime startDate);

    @Query(value = "SELECT CAST(f.logged_at AS DATE) as d, " +
           "SUM(CASE WHEN f.status = 'FAILURE' THEN 1 ELSE 0 END) as failures, " +
           "SUM(CASE WHEN f.status = 'SUCCESS' THEN 1 ELSE 0 END) as successes " +
           "FROM fetch_log f " +
           "WHERE f.logged_at >= :startDate " +
           "GROUP BY CAST(f.logged_at AS DATE) " +
           "ORDER BY d",
           nativeQuery = true)
    List<Object[]> dailyStats(@Param("startDate") LocalDateTime startDate);

    void deleteByLoggedAtBefore(LocalDateTime cutoff);

    @Query(value = "SELECT f.feed_source_id AS sourceId, f.feed_source_name AS sourceName, " +
           "COUNT(*) AS failureCount, " +
           "SUM(CASE WHEN f.status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCount " +
           "FROM fetch_log f " +
           "JOIN feed_source s ON f.feed_source_id = s.id " +
           "WHERE f.status = 'FAILURE' AND s.enabled = true AND f.logged_at >= :startDate " +
           "GROUP BY f.feed_source_id, f.feed_source_name " +
           "ORDER BY failureCount DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> failureRanking(@Param("startDate") LocalDateTime startDate,
                                  @Param("limit") int limit);
}
