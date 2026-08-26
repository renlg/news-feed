package com.newsfeed.repository;

import com.newsfeed.model.MajorEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MajorEventRepository extends JpaRepository<MajorEvent, Long> {

    Optional<MajorEvent> findBySecCodeAndTitle(String secCode, String title);

    List<MajorEvent> findBySecCodeOrderByEventDateDescIdDesc(String secCode);

    List<MajorEvent> findByCategory(String category);

    long countByCategory(String category);

    @Query("SELECT e FROM MajorEvent e WHERE e.secCode = :secCode " +
           "AND (:category IS NULL OR :category = '' OR e.category = :category) " +
           "ORDER BY e.eventDate DESC, e.id DESC")
    Page<MajorEvent> findPageBySecCode(@Param("secCode") String secCode,
                                       @Param("category") String category,
                                       Pageable pageable);

    @Query("SELECT DISTINCT e.category FROM MajorEvent e WHERE e.secCode = :secCode " +
           "AND e.category IS NOT NULL ORDER BY e.category")
    List<String> findDistinctCategoriesBySecCode(@Param("secCode") String secCode);

    @Query("SELECT e FROM MajorEvent e WHERE " +
           "(:category IS NULL OR :category = '' OR e.category = :category) AND " +
           "(:keyword IS NULL OR :keyword = '' OR LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(e.secName) LIKE LOWER(CONCAT('%', :keyword, '%'))) ")
    Page<MajorEvent> search(@Param("category") String category,
                            @Param("keyword") String keyword,
                            Pageable pageable);
}
