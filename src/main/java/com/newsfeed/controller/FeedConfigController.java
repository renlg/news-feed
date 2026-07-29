package com.newsfeed.controller;

import com.newsfeed.model.FeedSource;
import com.newsfeed.service.FeedFetchService;
import com.newsfeed.service.FeedSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/feeds")
@RequiredArgsConstructor
public class FeedConfigController {

    private final FeedSourceService feedSourceService;
    private final FeedFetchService feedFetchService;

    @GetMapping
    public String listFeeds(Model model,
                            @RequestParam(value = "success", required = false) String successMsg,
                            @RequestParam(value = "error", required = false) String errorMsg) {
        model.addAttribute("sources", feedSourceService.findAll());

        Map<Long, Long> articleCounts = new HashMap<>();
        for (FeedSource source : feedSourceService.findAll()) {
            articleCounts.put(source.getId(),
                    feedSourceService.countArticlesBySourceId(source.getId()));
        }
        model.addAttribute("articleCounts", articleCounts);

        if (successMsg != null) model.addAttribute("successMsg", successMsg);
        if (errorMsg != null) model.addAttribute("errorMsg", errorMsg);

        return "feeds";
    }

    @PostMapping
    public String addFeed(@ModelAttribute FeedSource source, RedirectAttributes redirectAttributes) {
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
            feedSourceService.save(source);
            redirectAttributes.addAttribute("success", "Feed source added successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to add feed: " + e.getMessage());
        }
        return "redirect:/feeds";
    }

    @PutMapping("/{id}")
    public String updateFeed(@PathVariable Long id,
                             @ModelAttribute FeedSource source,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.findById(id).ifPresent(existing -> {
                existing.setName(source.getName());
                existing.setUrl(source.getUrl());
                existing.setProtocol(source.getProtocol());
                existing.setFetchIntervalMinutes(source.getFetchIntervalMinutes());
                feedSourceService.save(existing);
            });
            redirectAttributes.addAttribute("success", "Feed source updated successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to update feed: " + e.getMessage());
        }
        return "redirect:/feeds";
    }

    @DeleteMapping("/{id}")
    public String deleteFeed(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.delete(id);
            redirectAttributes.addAttribute("success", "Feed source deleted successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to delete feed: " + e.getMessage());
        }
        return "redirect:/feeds";
    }

    @PostMapping("/{id}/toggle")
    public String toggleFeed(@PathVariable Long id,
                             @RequestParam boolean enabled,
                             RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.toggleEnabled(id, enabled);
            redirectAttributes.addAttribute("success",
                    "Feed " + (enabled ? "enabled" : "disabled") + " successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to toggle feed: " + e.getMessage());
        }
        return "redirect:/feeds";
    }

    @PostMapping("/{id}/fetch")
    public String fetchNow(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            feedSourceService.findById(id).ifPresent(feedFetchService::processSource);
            redirectAttributes.addAttribute("success", "Fetch triggered for feed source");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to trigger fetch: " + e.getMessage());
        }
        return "redirect:/feeds";
    }
}
