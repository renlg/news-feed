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
            "WHERE (:country IS NULL OR fs.country = :country) " +
            "AND (:tagId IS NULL OR t.id = :tagId) " +
            "AND (:enabled IS NULL OR fs.enabled = :enabled)")
    Page<FeedSource> findByFilters(@Param("country") String country,
                                   @Param("tagId") Long tagId,
                                   @Param("enabled") Boolean enabled,
                                   Pageable pageable);

    @Query("SELECT DISTINCT fs FROM FeedSource fs LEFT JOIN fs.tags t " +
            "WHERE (:country IS NULL OR fs.country = :country) " +
            "AND (:tagId IS NULL OR t.id = :tagId) " +
            "AND (:enabled IS NULL OR fs.enabled = :enabled)")
    List<FeedSource> findByFilters(@Param("country") String country,
                                   @Param("tagId") Long tagId,
                                   @Param("enabled") Boolean enabled);

    @Query("SELECT DISTINCT fs.country FROM FeedSource fs WHERE fs.country IS NOT NULL ORDER BY fs.country")
    List<String> findDistinctCountries();
}
