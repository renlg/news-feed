package com.newsfeed.service;

import com.newsfeed.model.FeedSource;
import com.newsfeed.model.Tag;
import com.newsfeed.repository.ArticleRepository;
import com.newsfeed.repository.FeedSourceRepository;
import com.newsfeed.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FeedSourceService {

    private final FeedSourceRepository feedSourceRepository;
    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;

    public List<FeedSource> findAll() {
        return feedSourceRepository.findAll();
    }

    public Page<FeedSource> findAll(Pageable pageable) {
        return feedSourceRepository.findAll(pageable);
    }

    public Optional<FeedSource> findById(Long id) {
        return feedSourceRepository.findById(id);
    }

    public List<FeedSource> findEnabled() {
        return feedSourceRepository.findByEnabledTrue();
    }

    @Transactional
    public FeedSource save(FeedSource source) {
        return feedSourceRepository.save(source);
    }

    @Transactional
    public void delete(Long id) {
        articleRepository.deleteByFeedSourceId(id);
        feedSourceRepository.deleteById(id);
    }

    @Transactional
    public void toggleEnabled(Long id, boolean enabled) {
        feedSourceRepository.findById(id).ifPresent(source -> {
            source.setEnabled(enabled);
            feedSourceRepository.save(source);
        });
    }

    @Transactional
    public void updateLastFetchedAt(Long id) {
        feedSourceRepository.findById(id).ifPresent(source -> {
            source.setLastFetchedAt(java.time.LocalDateTime.now());
            feedSourceRepository.save(source);
        });
    }

    public long countArticlesBySourceId(Long sourceId) {
        return articleRepository.countByFeedSourceId(sourceId);
    }

    public Page<FeedSource> findAll(Long tagId, Pageable pageable) {
        return feedSourceRepository.findByFilters(tagId, null, pageable);
    }

    public Page<FeedSource> findAll(List<Long> tagIds, Boolean enabled, Pageable pageable) {
        if (tagIds != null && tagIds.size() == 1) {
            return feedSourceRepository.findByFilters(tagIds.get(0), enabled, pageable);
        }
        return feedSourceRepository.findByFilters(tagIds, enabled, pageable);
    }

    public List<FeedSource> findAll(Long tagId) {
        return feedSourceRepository.findByFilters(tagId, null);
    }

    public List<FeedSource> findAll(Long tagId, Boolean enabled) {
        return feedSourceRepository.findByFilters(tagId, enabled);
    }

    public List<Tag> findAllTags() {
        return tagRepository.findAllByOrderByName();
    }

    @Transactional
    public int enableAiCategorizeForAll() {
        List<FeedSource> sources = feedSourceRepository.findAll();
        int count = 0;
        for (FeedSource source : sources) {
            if (!Boolean.TRUE.equals(source.getAiCategorize())) {
                source.setAiCategorize(true);
                feedSourceRepository.save(source);
                count++;
            }
        }
        return count;
    }
}
