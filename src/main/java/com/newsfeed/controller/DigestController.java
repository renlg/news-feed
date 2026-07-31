package com.newsfeed.controller;

import com.newsfeed.model.DailyDigest;
import com.newsfeed.repository.DailyDigestRepository;
import com.newsfeed.service.DailyDigestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/digest")
@RequiredArgsConstructor
public class DigestController {

    private final DailyDigestService digestService;

    @GetMapping
    public String latestDigest(@RequestParam(defaultValue = "0") int page, Model model) {
        var pagedResult = digestService.getDigestSummariesPaged(page, 30);
        List<DailyDigestRepository.DigestSummary> digestSummaries = pagedResult.getContent();
        model.addAttribute("digests", digestSummaries);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", pagedResult.getTotalPages());

        if (!digestSummaries.isEmpty()) {
            String latestDate = digestSummaries.get(0).getDigestDate();
            digestService.getDigestByDate(latestDate).ifPresent(d -> model.addAttribute("digest", d));
            model.addAttribute("selectedDate", latestDate);
        }

        return "digest";
    }

    @GetMapping("/{date}")
    public String digestByDate(@PathVariable String date, Model model) {
        digestService.getDigestByDate(date).ifPresent(d -> model.addAttribute("digest", d));
        model.addAttribute("digests", digestService.getAllDigestSummaries());
        model.addAttribute("selectedDate", date);
        return "digest";
    }

    @PostMapping("/generate")
    public String generateDigest(RedirectAttributes redirectAttributes) {
        try {
            digestService.forceGenerateDigestAsync();
            redirectAttributes.addFlashAttribute("successMsg", "正在后台生成新闻摘要，请稍后刷新页面查看");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMsg", "生成失败: " + e.getMessage());
        }
        return "redirect:/digest";
    }

    @GetMapping("/generation-status")
    @ResponseBody
    public String generationStatus() {
        return digestService.getGenerationStatus();
    }

    @GetMapping("/generation-error")
    @ResponseBody
    public String generationError() {
        return digestService.getGenerationError();
    }

    @PostMapping("/delete/{date}")
    public String deleteDigest(@PathVariable String date, RedirectAttributes redirectAttributes) {
        digestService.deleteDigestByDate(date);
        redirectAttributes.addFlashAttribute("successMsg", "已删除 " + date + " 的摘要");
        return "redirect:/digest";
    }
}
