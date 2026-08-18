package com.newsfeed.service;

import com.newsfeed.model.Article;
import com.newsfeed.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final ArticleRepository articleRepository;

    private volatile List<String> cachedCategories;
    private volatile long cachedCategoriesTimestamp;

    /**
     * Persists articles using their unique link. AI-enabled sources are categorized later by
     * ArticleAiService together with scoring and summary generation.
     */
    public int saveArticles(List<Article> articles) {
        int saved = 0;
        for (Article article : articles) {
            if (!articleRepository.existsByLink(article.getLink())) {
                articleRepository.save(article);
                saved++;
            }
        }
        return saved;
    }

    public Page<Article> search(String keyword, Long sourceId, String category, int page, int size, String sort) {
        Sort sortObj;
        if ("oldest".equals(sort)) {
            sortObj = Sort.by(Sort.Direction.ASC, "publishedAt");
        } else if ("title".equals(sort)) {
            sortObj = Sort.by(Sort.Direction.ASC, "title");
        } else {
            sortObj = Sort.by(Sort.Direction.DESC, "publishedAt");
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, sortObj);
        return articleRepository.search(keyword, sourceId, category, pageable);
    }

    public List<String> getCategories() {
        List<String> local = cachedCategories;
        if (local != null && System.currentTimeMillis() - cachedCategoriesTimestamp < CACHE_TTL_MS) {
            return local;
        }
        synchronized (this) {
            if (cachedCategories != null && System.currentTimeMillis() - cachedCategoriesTimestamp < CACHE_TTL_MS) {
                return cachedCategories;
            }
            List<String> fresh = Collections.unmodifiableList(new ArrayList<>(
                    articleRepository.findDistinctAiCategoryDisplayNames().stream()
                            .filter(c -> c != null && !c.trim().isEmpty())
                            .distinct()
                            .sorted()
                            .toList()
            ));
            cachedCategories = fresh;
            cachedCategoriesTimestamp = System.currentTimeMillis();
            return fresh;
        }
    }
}
