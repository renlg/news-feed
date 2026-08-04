package com.newsfeed.repository;

import com.newsfeed.model.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findByLink(String link);

    boolean existsByLink(String link);

    long countByFeedSourceId(Long feedSourceId);

    void deleteByFeedSourceId(Long feedSourceId);

    @Query("SELECT a FROM Article a WHERE " +
           "(:q IS NULL OR :q = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(a.content) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "AND (:sourceId IS NULL OR a.feedSourceId = :sourceId) " +
           "AND (:category IS NULL OR :category = '' " +
           "OR (:category = '__NO_CATEGORY__' AND (a.category IS NULL OR a.category = '')) " +
           "OR LOWER(CONCAT(',', a.category, ',')) LIKE LOWER(CONCAT('%,', :category, ',%')))")
    Page<Article> search(@Param("q") String keyword,
                         @Param("sourceId") Long sourceId,
                         @Param("category") String category,
                         Pageable pageable);

    @Query("SELECT a FROM Article a WHERE " +
           "(:q IS NULL OR :q = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(a.content) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "AND (:sourceId IS NULL OR a.feedSourceId = :sourceId) " +
           "AND (:category IS NULL OR :category = '' " +
           "OR (:category = '__NO_CATEGORY__' AND (a.category IS NULL OR a.category = '')) " +
           "OR LOWER(CONCAT(',', a.category, ',')) LIKE LOWER(CONCAT('%,', :category, ',%')))")
    List<Article> search(@Param("q") String keyword,
                         @Param("sourceId") Long sourceId,
                         @Param("category") String category,
                         Sort sort);

    @Query("SELECT DISTINCT a.category FROM Article a WHERE a.category IS NOT NULL ORDER BY a.category")
    List<String> findDistinctCategories();

    @Query("SELECT a FROM Article a WHERE COALESCE(a.publishedAt, a.fetchedAt) < :cutoff")
    List<Article> findByPublishedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    long countByPublishedAtBefore(LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM Article a WHERE COALESCE(a.publishedAt, a.fetchedAt) < :cutoff")
    void deleteByPublishedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT a FROM Article a WHERE a.fetchedAt >= :since AND a.fetchedAt < :until ORDER BY a.publishedAt DESC")
    List<Article> findByFetchedAtBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Query("SELECT a FROM Article a WHERE (a.aiProcessed = false OR a.aiProcessed IS NULL) " +
           "AND COALESCE(a.aiFailCount, 0) < 3 " +
           "AND a.feedSourceId IN (SELECT fs.id FROM FeedSource fs WHERE fs.aiCategorize = true)")
    List<Article> findUnprocessedArticles();

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Article a SET a.aiProcessed = false, a.aiCategory = NULL, a.aiCategoryName = NULL, " +
           "a.importanceScore = NULL, a.aiSummary = NULL, a.aiFailCount = 0 " +
           "WHERE a.aiProcessed = true AND a.fetchedAt >= :since AND a.fetchedAt < :until " +
           "AND a.feedSourceId IN (SELECT fs.id FROM FeedSource fs WHERE fs.aiCategorize = true)")
    int resetAiProcessingBetween(@Param("since") LocalDateTime since,
                                 @Param("until") LocalDateTime until);

    @Query("SELECT a FROM Article a WHERE a.aiCategory = :aiCategory AND a.fetchedAt >= :since ORDER BY a.importanceScore DESC")
    List<Article> findByAiCategoryAndFetchedAtAfter(@Param("aiCategory") String aiCategory, @Param("since") LocalDateTime since);

    @Query("SELECT a FROM Article a WHERE a.aiCategory = :aiCategory AND a.importanceScore > :minScore AND COALESCE(a.publishedAt, a.fetchedAt) >= :since AND COALESCE(a.publishedAt, a.fetchedAt) < :until ORDER BY a.importanceScore DESC")
    List<Article> findHighScoringByCategory(@Param("aiCategory") String aiCategory, @Param("minScore") int minScore, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Query("SELECT COUNT(a) FROM Article a WHERE a.aiProcessed = true AND a.feedSourceId IN (SELECT fs.id FROM FeedSource fs WHERE fs.aiCategorize = true)")
    long countProcessedFromAiSources();

    @Query("SELECT COUNT(a) FROM Article a WHERE (a.aiProcessed = false OR a.aiProcessed IS NULL) " +
           "AND COALESCE(a.aiFailCount, 0) < 3 " +
           "AND a.feedSourceId IN (SELECT fs.id FROM FeedSource fs WHERE fs.aiCategorize = true)")
    long countUnprocessedFromAiSources();

    @Query("SELECT COUNT(a) FROM Article a WHERE a.fetchedAt >= :since")
    long countArticlesSince(@Param("since") LocalDateTime since);
}
