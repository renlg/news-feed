package com.newsfeed.controller;

import com.newsfeed.model.Article;
import com.newsfeed.model.FeedSource;
import com.newsfeed.service.ArticleService;
import com.newsfeed.service.FeedSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ArticleService articleService;
    private final FeedSourceService feedSourceService;

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
}
