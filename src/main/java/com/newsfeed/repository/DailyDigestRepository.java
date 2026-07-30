package com.newsfeed.repository;

import com.newsfeed.model.DailyDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface DailyDigestRepository extends JpaRepository<DailyDigest, Long> {

    Optional<DailyDigest> findByDigestDate(String digestDate);

    boolean existsByDigestDate(String digestDate);

    @Transactional
    void deleteByDigestDate(String digestDate);
}
