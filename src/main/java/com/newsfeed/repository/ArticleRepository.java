package com.newsfeed.repository;

import com.newsfeed.model.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
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
           "AND (:sourceId IS NULL OR a.feedSourceId = :sourceId)")
    Page<Article> search(@Param("q") String keyword,
                         @Param("sourceId") Long sourceId,
                         Pageable pageable);

    @Query("SELECT a FROM Article a WHERE " +
           "(:q IS NULL OR :q = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "OR LOWER(a.content) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "AND (:sourceId IS NULL OR a.feedSourceId = :sourceId)")
    List<Article> search(@Param("q") String keyword,
                         @Param("sourceId") Long sourceId,
                         Sort sort);

    List<Article> findByPublishedAtBefore(LocalDateTime cutoff);

    long countByPublishedAtBefore(LocalDateTime cutoff);

    @Transactional
    void deleteByPublishedAtBefore(LocalDateTime cutoff);
}
