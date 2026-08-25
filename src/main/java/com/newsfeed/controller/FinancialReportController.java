package com.newsfeed.controller;

import com.newsfeed.model.FinancialReport;
import com.newsfeed.repository.FinancialReportRepository;
import com.newsfeed.service.FinancialReportFetchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/financial")
@RequiredArgsConstructor
public class FinancialReportController {

    private final FinancialReportRepository financialReportRepository;
    private final FinancialReportFetchService financialReportFetchService;

    @GetMapping
    public String list(@RequestParam(value = "period", required = false) String reportPeriod,
                       @RequestParam(value = "q", required = false) String keyword,
                       @RequestParam(value = "page", defaultValue = "0") int page,
                       @RequestParam(value = "size", defaultValue = "25") int size,
                       Model model) {
        Page<FinancialReport> reports = findReports(reportPeriod, keyword, page, size);
        model.addAttribute("reports", reports);
        model.addAttribute("periods", financialReportRepository.findDistinctReportPeriods());
        model.addAttribute("period", reportPeriod != null ? reportPeriod : "");
        model.addAttribute("q", keyword != null ? keyword : "");
        model.addAttribute("size", reports.getSize());
        model.addAttribute("fetching", financialReportFetchService.isFetching());
        return "financial";
    }

    @GetMapping("/api/list")
    @ResponseBody
    public Page<FinancialReport> apiList(
            @RequestParam(value = "period", required = false) String reportPeriod,
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return findReports(reportPeriod, keyword, page, size);
    }

    @PostMapping("/fetch")
    public String fetchNow(RedirectAttributes redirectAttributes) {
        try {
            FinancialReportFetchService.FetchResult result = financialReportFetchService.fetchReports();
            if (result.alreadyRunning()) {
                redirectAttributes.addFlashAttribute("successMsg", "财报抓取任务正在运行，请稍后刷新");
            } else {
                redirectAttributes.addFlashAttribute("successMsg",
                        "财报抓取完成：新增 " + result.added() + " 条，更新 " + result.updated() + " 条");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMsg", "财报抓取失败：" + e.getMessage());
        }
        return "redirect:/financial";
    }

    private Page<FinancialReport> findReports(String reportPeriod, String keyword, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Sort sort = Sort.by(Sort.Direction.DESC, "noticeDate")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);
        return financialReportRepository.search(reportPeriod, keyword, pageable);
    }
}
