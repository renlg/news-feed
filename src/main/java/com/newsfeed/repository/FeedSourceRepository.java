package com.newsfeed.repository;

import com.newsfeed.model.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

    List<FeedSource> findByEnabledTrue();
}
