package com.newsfeed.controller;

import com.newsfeed.config.HttpUrlSafety;
import com.newsfeed.dto.ArticleDTO;
import com.newsfeed.dto.PageResponse;
import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;
import com.newsfeed.service.ArticleService;
import com.newsfeed.service.FeedSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ArticleService articleService;
    private final FeedSourceService feedSourceService;
    private final HttpUrlSafety httpUrlSafety;

    @GetMapping
    public String search(@RequestParam(value = "q", required = false) String keyword,
                         @RequestParam(value = "sourceId", required = false) Long sourceId,
                         @RequestParam(value = "category", required = false) String category,
                         @RequestParam(value = "sort", defaultValue = "newest") String sort,
                         @RequestParam(value = "page", defaultValue = "0") int page,
                         @RequestParam(value = "size", defaultValue = "10") int size,
                         Model model) {

        if ("__NULL__".equals(category)) {
            category = "__NO_CATEGORY__";
        }

        Page<Article> articles = articleService.search(keyword, sourceId, category, page, size, sort);
        model.addAttribute("articles", articles);
        model.addAttribute("sources", feedSourceService.findAll());
        model.addAttribute("categories", articleService.getCategories());
        model.addAttribute("q", keyword != null ? keyword : "");
        model.addAttribute("sourceId", sourceId);
        model.addAttribute("category", category);
        model.addAttribute("sort", sort);
        model.addAttribute("size", size);

        return "search";
    }

    @GetMapping("/api")
    @ResponseBody
    @Transactional(readOnly = true)
    public PageResponse<ArticleDTO> searchApi(
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "sourceId", required = false) Long sourceId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "sort", defaultValue = "newest") String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        String normalizedCategory = "__NULL__".equals(category) ? "__NO_CATEGORY__" : category;
        return PageResponse.from(articleService.search(keyword, sourceId, normalizedCategory, page, size, sort),
                article -> new ArticleDTO(article.getId(), article.getTitle(),
                        httpUrlSafety.safeHttpUrl(article.getLink()), article.getSummary(), article.getAuthor(),
                        article.getCategory(), article.getAiCategoryDisplayName(), article.getAiProcessed(),
                        article.getImportanceScore(), article.getAiSummary(), article.getPublishedAt(),
                        article.getFetchedAt(), article.getFeedSourceId(),
                        article.getFeedSource() == null ? null : article.getFeedSource().getName()));
    }
}
