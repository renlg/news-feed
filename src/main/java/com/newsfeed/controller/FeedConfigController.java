package com.newsfeed.controller;

import com.newsfeed.model.FeedSource;
import com.newsfeed.model.Tag;
import com.newsfeed.service.FeedFetchWorker;
import com.newsfeed.service.FeedSourceService;
import com.newsfeed.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final FeedFetchWorker feedFetchWorker;
    private final TagService tagService;

    @GetMapping
    public String listFeeds(Model model,
                            @RequestParam(value = "page", defaultValue = "0") int page,
                            @RequestParam(value = "size", defaultValue = "10") int size,
                            @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                            @RequestParam(value = "enabled", required = false) Boolean enabled,
                            @RequestParam(value = "success", required = false) String successMsg,
                            @RequestParam(value = "error", required = false) String errorMsg) {
        if (tagIds != null && tagIds.isEmpty()) {
            tagIds = null;
        }

        Page<FeedSource> sourcePage = feedSourceService.findAll(tagIds, enabled,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("sources", sourcePage.getContent());
        model.addAttribute("sourcePage", sourcePage);
        model.addAttribute("tagIds", tagIds);
        model.addAttribute("enabled", enabled);
        model.addAttribute("tags", tagService.findAll());

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
                          @RequestParam(value = "filterTagIds", required = false) String filterTagIdsStr,
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
            if (source.getAiCategorize() == null) {
                source.setAiCategorize(false);
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
        addFilterAttributes(redirectAttributes, parseTagIds(filterTagIdsStr), filterEnabled);
        return "redirect:/feeds";
    }

    @PutMapping("/{id}")
    public String updateFeed(@PathVariable Long id,
                             @ModelAttribute FeedSource source,
                             @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "filterTagIds", required = false) String filterTagIdsStr,
                             @RequestParam(value = "filterEnabled", required = false) Boolean filterEnabled,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.findById(id).ifPresent(existing -> {
                existing.setName(source.getName());
                existing.setUrl(source.getUrl());
                existing.setProtocol(source.getProtocol());
                existing.setFetchIntervalMinutes(source.getFetchIntervalMinutes());
                existing.setEnabled(source.getEnabled() != null && source.getEnabled());
                existing.setAiCategorize(source.getAiCategorize() != null && source.getAiCategorize());
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
        addFilterAttributes(redirectAttributes, parseTagIds(filterTagIdsStr), filterEnabled);
        return "redirect:/feeds";
    }

    @DeleteMapping("/{id}")
    public String deleteFeed(@PathVariable Long id,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                             @RequestParam(value = "enabled", required = false) Boolean enabled,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.delete(id);
            redirectAttributes.addAttribute("success", "Feed source deleted successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to delete feed: " + e.getMessage());
        }
        redirectAttributes.addAttribute("page", page);
        addFilterAttributes(redirectAttributes, tagIds, enabled);
        return "redirect:/feeds";
    }

    @PostMapping("/{id}/toggle")
    public String toggleFeed(@PathVariable Long id,
                             @RequestParam boolean enabled,
                             @RequestParam(value = "page", defaultValue = "0") int page,
                             @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.toggleEnabled(id, enabled);
            redirectAttributes.addAttribute("success",
                    "Feed " + (enabled ? "enabled" : "disabled") + " successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to toggle feed: " + e.getMessage());
        }
        redirectAttributes.addAttribute("page", page);
        addFilterAttributes(redirectAttributes, tagIds, null);
        return "redirect:/feeds";
    }

    @PostMapping("/{id}/fetch")
    public String fetchNow(@PathVariable Long id,
                           @RequestParam(value = "page", defaultValue = "0") int page,
                           @RequestParam(value = "tagIds", required = false) List<Long> tagIds,
                           @RequestParam(value = "enabled", required = false) Boolean enabled,
                           RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.findById(id).ifPresent(feedFetchWorker::processSource);
            redirectAttributes.addAttribute("success", "Fetch triggered for feed source");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to trigger fetch: " + e.getMessage());
        }
        redirectAttributes.addAttribute("page", page);
        addFilterAttributes(redirectAttributes, tagIds, enabled);
        return "redirect:/feeds";
    }

    private void addFilterAttributes(RedirectAttributes redirectAttributes, List<Long> tagIds, Boolean enabled) {
        if (tagIds != null && !tagIds.isEmpty()) {
            redirectAttributes.addAttribute("tagIds", tagIds);
        }
        if (enabled != null) {
            redirectAttributes.addAttribute("enabled", enabled);
        }
    }

    private List<Long> parseTagIds(String tagIdsStr) {
        if (tagIdsStr == null || tagIdsStr.isBlank()) return null;
        List<Long> result = new ArrayList<>();
        for (String s : tagIdsStr.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) {
                try {
                    result.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result.isEmpty() ? null : result;
    }
}
