package com.newsfeed.controller;

import com.newsfeed.model.DailyDigest;
import com.newsfeed.service.DailyDigestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/digest")
@RequiredArgsConstructor
public class DigestController {

    private final DailyDigestService digestService;

    @GetMapping
    public String latestDigest(Model model) {
        List<DailyDigest> digests = digestService.getAllDigests();
        model.addAttribute("digests", digests);
        
        if (!digests.isEmpty()) {
            model.addAttribute("digest", digests.get(0));
            model.addAttribute("selectedDate", digests.get(0).getDigestDate());
        }
        
        return "digest";
    }

    @GetMapping("/{date}")
    public String digestByDate(@PathVariable String date, Model model) {
        digestService.getDigestByDate(date).ifPresent(d -> model.addAttribute("digest", d));
        model.addAttribute("digests", digestService.getAllDigests());
        model.addAttribute("selectedDate", date);
        return "digest";
    }

    @PostMapping("/generate")
    public String generateDigest(RedirectAttributes redirectAttributes) {
        try {
            digestService.forceGenerateDigest();
            redirectAttributes.addFlashAttribute("successMsg", "新闻摘要生成成功！");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMsg", "生成失败: " + e.getMessage());
        }
        return "redirect:/digest";
    }

    @PostMapping("/delete/{date}")
    public String deleteDigest(@PathVariable String date, RedirectAttributes redirectAttributes) {
        digestService.deleteDigestByDate(date);
        redirectAttributes.addFlashAttribute("successMsg", "已删除 " + date + " 的摘要");
        return "redirect:/digest";
    }
}
