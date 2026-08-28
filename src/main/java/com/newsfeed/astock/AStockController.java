package com.newsfeed.astock;

import com.newsfeed.dto.FinancialReportDTO;
import com.newsfeed.dto.MajorEventDTO;
import com.newsfeed.dto.PageResponse;
import com.newsfeed.repository.FinancialReportRepository;
import com.newsfeed.repository.MajorEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/astocks")
@RequiredArgsConstructor
public class AStockController {

    private static final Set<String> DETAIL_TABS = Set.of(
            "market", "valuation", "moneyflow", "holders", "margin",
            "consensus", "northbound", "financial", "events");

    private final AStockService aStockService;
    private final FinancialReportRepository financialReportRepository;
    private final MajorEventRepository majorEventRepository;

    @GetMapping
    public String list(@RequestParam(value = "board", defaultValue = AStockService.ALL_BOARDS) String board,
                       @RequestParam(value = "q", required = false) String keyword,
                       @RequestParam(value = "page", defaultValue = "0") int page,
                       @RequestParam(value = "size", defaultValue = "10") int size,
                       Model model) {
        String activeBoard = AStockService.BOARDS.contains(board) ? board : AStockService.ALL_BOARDS;
        AStockService.StockPage stocks = aStockService.findStocks(activeBoard, keyword, page, size);
        model.addAttribute("stocks", stocks);
        model.addAttribute("boards", AStockService.BOARDS);
        model.addAttribute("board", activeBoard);
        model.addAttribute("q", keyword == null ? "" : keyword.trim());
        model.addAttribute("size", stocks.size());
        return "astocks";
    }

    @GetMapping("/api")
    @ResponseBody
    public PageResponse<AStockService.Stock> listApi(
            @RequestParam(value = "board", defaultValue = AStockService.ALL_BOARDS) String board,
            @RequestParam(value = "q", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        String activeBoard = AStockService.BOARDS.contains(board) ? board : AStockService.ALL_BOARDS;
        AStockService.StockPage result = aStockService.findStocks(activeBoard, keyword, page, size);
        return new PageResponse<>(result.content(), result.number(), result.size(), result.totalElements(),
                result.totalPages(), result.isFirst(), result.isLast());
    }

    @GetMapping("/{secCode}")
    public String detail(@PathVariable String secCode,
                         @RequestParam(value = "fp", defaultValue = "0") int financialPage,
                         @RequestParam(value = "fs", defaultValue = "20") int financialSize,
                         @RequestParam(value = "ep", defaultValue = "0") int eventPage,
                         @RequestParam(value = "es", defaultValue = "20") int eventSize,
                         @RequestParam(value = "cat", required = false) String eventCategory,
                         @RequestParam(value = "tab", defaultValue = "market") String tab,
                         Model model) {
        AStockService.Stock stock = aStockService.findStock(secCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "股票不存在"));
        int normalizedFinancialPage = Math.max(financialPage, 0);
        int normalizedFinancialSize = normalizePageSize(financialSize);
        int normalizedEventPage = Math.max(eventPage, 0);
        int normalizedEventSize = normalizePageSize(eventSize);
        String normalizedCategory = eventCategory == null ? null : eventCategory.trim();
        String activeTab = DETAIL_TABS.contains(tab) ? tab : "market";

        model.addAttribute("stock", stock);
        model.addAttribute("klines", aStockService.findKlines(secCode, 250));
        model.addAttribute("minKlines", aStockService.findMinKlines(secCode, 120));
        model.addAttribute("valuation", aStockService.findLatestValuation(secCode).orElse(null));
        model.addAttribute("moneyFlows", aStockService.findMoneyFlows(secCode, 30));
        model.addAttribute("holderNumbers", aStockService.findHolderNumbers(secCode, 12));
        model.addAttribute("margins", aStockService.findMargins(secCode, 30));
        model.addAttribute("consensus", aStockService.findLatestConsensus(secCode).orElse(null));
        model.addAttribute("forecasts", aStockService.findForecasts(secCode, 20));
        model.addAttribute("northboundHoldings", aStockService.findNorthboundHoldings(secCode, 12));
        model.addAttribute("financialReports", financialReportRepository.findBySecCodeOrderByReportDateDesc(
                secCode, PageRequest.of(normalizedFinancialPage, normalizedFinancialSize)));
        model.addAttribute("financialSize", normalizedFinancialSize);
        model.addAttribute("majorEvents", majorEventRepository.findPageBySecCode(
                secCode, normalizedCategory, PageRequest.of(normalizedEventPage, normalizedEventSize)));
        model.addAttribute("eventCategories", majorEventRepository.findDistinctCategoriesBySecCode(secCode));
        model.addAttribute("eventCategory", normalizedCategory);
        model.addAttribute("eventSize", normalizedEventSize);
        model.addAttribute("activeTab", activeTab);
        return "astock-detail";
    }

    @GetMapping("/{secCode}/api/financial")
    @ResponseBody
    public PageResponse<FinancialReportDTO> financialApi(
            @PathVariable String secCode,
            @RequestParam(value = "fp", defaultValue = "0") int page,
            @RequestParam(value = "fs", defaultValue = "20") int size) {
        ensureStockExists(secCode);
        return PageResponse.from(financialReportRepository.findBySecCodeOrderByReportDateDesc(
                secCode, PageRequest.of(Math.max(page, 0), normalizePageSize(size))), row ->
                new FinancialReportDTO(row.getId(), row.getReportPeriod(), row.getReportType(),
                        row.getNoticeDate(), row.getTotalOperateIncome(), row.getParentNetProfit(),
                        row.getBasicEps(), row.getWeightAvgRoe(), row.getYstz(), row.getSjltz()));
    }

    @GetMapping("/{secCode}/api/events")
    @ResponseBody
    public PageResponse<MajorEventDTO> eventsApi(
            @PathVariable String secCode,
            @RequestParam(value = "ep", defaultValue = "0") int page,
            @RequestParam(value = "es", defaultValue = "20") int size,
            @RequestParam(value = "cat", required = false) String category) {
        ensureStockExists(secCode);
        String normalizedCategory = category == null ? null : category.trim();
        return PageResponse.from(majorEventRepository.findPageBySecCode(secCode, normalizedCategory,
                PageRequest.of(Math.max(page, 0), normalizePageSize(size))), row ->
                new MajorEventDTO(row.getId(), row.getEventDate(), row.getCategory(), row.getTitle(),
                        row.getPdfPath(), row.getPdfUrl()));
    }

    private void ensureStockExists(String secCode) {
        if (aStockService.findStock(secCode).isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "股票不存在");
        }
    }

    private int normalizePageSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
