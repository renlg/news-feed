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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;

    @Transactional
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

        Pageable pageable = PageRequest.of(page, size, sortObj);
        return articleRepository.search(keyword, sourceId, category, pageable);
    }

    public List<String> getCategories() {
        List<String> rawCategories = articleRepository.findDistinctCategories();
        return rawCategories.stream()
                .filter(c -> c != null && !c.trim().isEmpty())
                .flatMap(c -> java.util.Arrays.stream(c.split(",")))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }
}
