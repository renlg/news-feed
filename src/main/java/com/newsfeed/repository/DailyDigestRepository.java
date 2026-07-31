package com.newsfeed.repository;

import com.newsfeed.model.DailyDigest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyDigestRepository extends JpaRepository<DailyDigest, Long> {

    Optional<DailyDigest> findByDigestDate(String digestDate);

    boolean existsByDigestDate(String digestDate);

    @Transactional
    void deleteByDigestDate(String digestDate);

    interface DigestSummary {
        Long getId();
        String getDigestDate();
        LocalDateTime getGeneratedAt();
    }

    List<DigestSummary> findAllByOrderByDigestDateDesc();

    Page<DigestSummary> findAllByOrderByDigestDateDesc(Pageable pageable);
}
