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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequestMapping("/financial")
@RequiredArgsConstructor
public class FinancialReportController {

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "secCode", "secCode",
            "secName", "secName",
            "reportPeriod", "reportPeriod",
            "totalOperateIncome", "totalOperateIncome",
            "parentNetProfit", "parentNetProfit",
            "basicEps", "basicEps",
            "weightAvgRoe", "weightAvgRoe",
            "ystz", "ystz",
            "sjltz", "sjltz",
            "noticeDate", "noticeDate"
    );

    private final FinancialReportRepository financialReportRepository;
    private final FinancialReportFetchService financialReportFetchService;

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
        return "redirect:/astocks";
    }

    @PostMapping("/backfill")
    public String backfill(RedirectAttributes redirectAttributes) {
        try {
            FinancialReportFetchService.FetchResult result =
                    financialReportFetchService.fetchHistoricalBackfill();
            if (result.alreadyRunning()) {
                redirectAttributes.addFlashAttribute("successMsg", "财报抓取任务正在运行，请稍后刷新");
            } else {
                redirectAttributes.addFlashAttribute("successMsg",
                        "财报历史补全完成：新增 " + result.added() + " 条，更新 " + result.updated() + " 条");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMsg", "财报历史补全失败：" + e.getMessage());
        }
        return "redirect:/astocks";
    }

    private Page<FinancialReport> findReports(String reportPeriod, String keyword, int page, int size,
                                              String sortKey, String direction) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Sort sort;
        if (SORT_FIELDS.containsKey(sortKey)) {
            Sort.Direction sortDirection = "desc".equals(direction)
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            sort = Sort.by(sortDirection, SORT_FIELDS.get(sortKey))
                    .and(Sort.by(Sort.Direction.DESC, "id"));
        } else {
            sort = Sort.by(Sort.Direction.DESC, "noticeDate")
                    .and(Sort.by(Sort.Direction.DESC, "id"));
        }
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);
        return financialReportRepository.search(reportPeriod, keyword, pageable);
    }

    private boolean isValidSortKey(String sortKey) {
        return sortKey != null && SORT_FIELDS.containsKey(sortKey);
    }
}
