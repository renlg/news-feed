package com.newsfeed.controller;

import com.newsfeed.model.Tag;
import com.newsfeed.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public String listTags(Model model,
                           @RequestParam(value = "success", required = false) String successMsg,
                           @RequestParam(value = "error", required = false) String errorMsg) {
        model.addAttribute("tags", tagService.findAll());
        if (successMsg != null) model.addAttribute("successMsg", successMsg);
        if (errorMsg != null) model.addAttribute("errorMsg", errorMsg);
        return "tags";
    }

    @PostMapping
    public String addTag(@ModelAttribute Tag tag, RedirectAttributes redirectAttributes) {
        try {
            if (tag.getColor() == null || tag.getColor().isBlank()) {
                tag.setColor("#6c757d");
            }
            tagService.save(tag);
            redirectAttributes.addAttribute("success", "Tag added successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to add tag: " + e.getMessage());
        }
        return "redirect:/tags";
    }

    @PostMapping("/{id}/delete")
    public String deleteTag(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            tagService.delete(id);
            redirectAttributes.addAttribute("success", "Tag deleted successfully");
        } catch (Exception e) {
            redirectAttributes.addAttribute("error", "Failed to delete tag: " + e.getMessage());
        }
        return "redirect:/tags";
    }
}
