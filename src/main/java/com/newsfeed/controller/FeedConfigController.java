package com.newsfeed.controller;

import com.newsfeed.model.FeedSource;
import com.newsfeed.model.Tag;
import com.newsfeed.service.FeedFetchService;
import com.newsfeed.service.FeedSourceService;
import com.newsfeed.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/feeds")
@RequiredArgsConstructor
public class FeedConfigController {

    private final FeedSourceService feedSourceService;
    private final FeedFetchService feedFetchService;
    private final TagService tagService;

    @GetMapping
    public String listFeeds(Model model,
                            @RequestParam(value = "page", defaultValue = "0") int page,
                            @RequestParam(value = "size", defaultValue = "10") int size,
                            @RequestParam(value = "country", required = false) String country,
                            @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                            @RequestParam(value = "enabled", required = false) Boolean enabled,
                            @RequestParam(value = "success", required = false) String successMsg,
                            @RequestParam(value = "error", required = false) String errorMsg) {
        // Normalize: convert empty string params to null so Thymeleaf @{...} doesn't include them
        if (country != null && country.isBlank()) {
            country = null;
        }
        if (tagIds != null && tagIds.isEmpty()) {
            tagIds = null;
        }

        Page<FeedSource> sourcePage = feedSourceService.findAll(country, tagIds, enabled, PageRequest.of(page, size));
        model.addAttribute("sources", sourcePage.getContent());
        model.addAttribute("sourcePage", sourcePage);
        model.addAttribute("country", country);
        model.addAttribute("tagIds", tagIds);
        model.addAttribute("enabled", enabled);
        model.addAttribute("tags", tagService.findAll());
        model.addAttribute("countries", feedSourceService.findDistinctCountries());

        Map<Long, Long> articleCounts = new HashMap<>();
        for (FeedSource source : sourcePage.getContent()) {
            articleCounts.put(source.getId(),
                    feedSourceService.countArticlesBySourceId(source.getId()));
        }
        model.addAttribute("articleCounts", articleCounts);

        if (successMsg != null) model.addAttribute("successMsg", successMsg);
        if (errorMsg != null) model.addAttribute("errorMsg", errorMsg);

        return "feeds";
    }

    @PostMapping
    public String addFeed(@ModelAttribute FeedSource source,
                          @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                          @RequestParam(value = "page", defaultValue = "0") int page,
                          @RequestParam(value = "country", required = false) String country,
                          @RequestParam(value = "filterEnabled", required = false) Boolean filterEnabled,
                          RedirectAttributes redirectAttributes) {
        try {
            if (source.getProtocol() == null || source.getProtocol().isBlank()) {
                source.setProtocol("RSS");
            }
            if (source.getFetchIntervalMinutes() == null) {
                source.setFetchIntervalMinutes(15);
            }
            if (source.getEnabled() == null) {
                source.setEnabled(true);
            }
            if (tagIds != null && !tagIds.isEmpty()) {
                Set<Tag> tags = new HashSet<>();
                for (Long tid : tagIds) {
                    tagService.findById(tid).ifPresent(tags::add);
                }
                source.setTags(tags);
            }
            feedSourceService.save(source);
            redirectAttributes.addAttribute("success", "Feed source added successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to add feed: " + e.getMessage());
        }
        redirectAttributes.addAttribute("page", page);
        return "redirect:/feeds" + buildFilterParams(country, tagIds, filterEnabled);
    }

    @PutMapping("/{id}")
    public String updateFeed(@PathVariable Long id,
                             @ModelAttribute FeedSource source,
                             @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "country", required = false) String country,
                             @RequestParam(value = "filterEnabled", required = false) Boolean filterEnabled,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.findById(id).ifPresent(existing -> {
                existing.setName(source.getName());
                existing.setUrl(source.getUrl());
                existing.setProtocol(source.getProtocol());
                existing.setCountry(source.getCountry());
                existing.setFetchIntervalMinutes(source.getFetchIntervalMinutes());
                existing.setEnabled(source.getEnabled() != null && source.getEnabled());
                if (tagIds != null && !tagIds.isEmpty()) {
                    Set<Tag> tags = new HashSet<>();
                    for (Long tid : tagIds) {
                        tagService.findById(tid).ifPresent(tags::add);
                    }
                    existing.setTags(tags);
                } else {
                    existing.setTags(new HashSet<>());
                }
                feedSourceService.save(existing);
            });
            redirectAttributes.addAttribute("success", "Feed source updated successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to update feed: " + e.getMessage());
        }
        redirectAttributes.addAttribute("page", page);
        return "redirect:/feeds" + buildFilterParams(country, tagIds, filterEnabled);
    }

    @DeleteMapping("/{id}")
    public String deleteFeed(@PathVariable Long id,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "country", required = false) String country,
                             @RequestParam(value = "tagId", required = false) Long tagId,
                             @RequestParam(value = "enabled", required = false) Boolean enabled,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.delete(id);
            redirectAttributes.addAttribute("success", "Feed source deleted successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to delete feed: " + e.getMessage());
        }
        return "redirect:/feeds?page=" + page + buildFilterParams(country, tagId, enabled);
    }

    @PostMapping("/{id}/toggle")
    public String toggleFeed(@PathVariable Long id,
                             @RequestParam boolean enabled,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "country", required = false) String country,
                             @RequestParam(value = "tagId", required = false) Long tagId,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.toggleEnabled(id, enabled);
            redirectAttributes.addAttribute("success",
                    "Feed " + (enabled ? "enabled" : "disabled") + " successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to toggle feed: " + e.getMessage());
        }
        return "redirect:/feeds?page=" + page + buildFilterParams(country, tagId, null);
    }

    @PostMapping("/{id}/fetch")
    public String fetchNow(@PathVariable Long id,
                           @RequestParam(value = "page", defaultValue = "0") int page,
                           @RequestParam(value = "country", required = false) String country,
                           @RequestParam(value = "tagId", required = false) Long tagId,
                           @RequestParam(value = "enabled", required = false) Boolean enabled,
                           RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.findById(id).ifPresent(feedFetchService::processSource);
            redirectAttributes.addAttribute("success", "Fetch triggered for feed source");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to trigger fetch: " + e.getMessage());
        }
        return "redirect:/feeds?page=" + page + buildFilterParams(country, tagId, enabled);
    }

    private String buildFilterParams(String country, Long tagId, Boolean enabled) {
        StringBuilder sb = new StringBuilder();
        if (country != null && !country.isBlank()) {
            sb.append("&country=").append(country);
        }
        if (tagId != null) {
            sb.append("&tagId=").append(tagId);
        }
        if (enabled != null) {
            sb.append("&enabled=").append(enabled);
        }
        return sb.toString();
    }
}
