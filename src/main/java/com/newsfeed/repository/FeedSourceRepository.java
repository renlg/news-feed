package com.newsfeed.repository;

import com.newsfeed.model.FeedSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

    List<FeedSource> findByEnabledTrue();

    boolean existsByUrl(String url);

    @Query("SELECT DISTINCT fs FROM FeedSource fs LEFT JOIN fs.tags t " +
            "WHERE (:tagId IS NULL OR t.id = :tagId) " +
            "AND (:enabled IS NULL OR fs.enabled = :enabled)")
    Page<FeedSource> findByFilters(@Param("tagId") Long tagId,
                                   @Param("enabled") Boolean enabled,
                                   Pageable pageable);

    @Query("SELECT DISTINCT fs FROM FeedSource fs " +
            "WHERE (:tagIds IS NULL OR fs.id IN (" +
            "   SELECT fss.id FROM FeedSource fss JOIN fss.tags tt WHERE tt.id IN :tagIds" +
            ")) " +
            "AND (:enabled IS NULL OR fs.enabled = :enabled)")
    Page<FeedSource> findByFilters(@Param("tagIds") List<Long> tagIds,
                                   @Param("enabled") Boolean enabled,
                                   Pageable pageable);

    @Query("SELECT DISTINCT fs FROM FeedSource fs LEFT JOIN fs.tags t " +
            "WHERE (:tagId IS NULL OR t.id = :tagId) " +
            "AND (:enabled IS NULL OR fs.enabled = :enabled)")
    List<FeedSource> findByFilters(@Param("tagId") Long tagId,
                                   @Param("enabled") Boolean enabled);
}
