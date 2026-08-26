package com.newsfeed.astock;

import com.newsfeed.model.FinancialReport;
import com.newsfeed.model.MajorEvent;
import com.newsfeed.repository.FinancialReportRepository;
import com.newsfeed.repository.MajorEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/astocks")
@RequiredArgsConstructor
public class AStockController {

    private final AStockService aStockService;
    private final FinancialReportRepository financialReportRepository;
    private final MajorEventRepository majorEventRepository;

    @GetMapping
    public String list(@RequestParam(value = "board", defaultValue = AStockService.ALL_BOARDS) String board,
                       @RequestParam(value = "q", required = false) String keyword,
                       @RequestParam(value = "page", defaultValue = "0") int page,
                       @RequestParam(value = "size", defaultValue = "50") int size,
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

    @GetMapping("/{secCode}")
    public String detail(@PathVariable String secCode, Model model) {
        AStockService.Stock stock = aStockService.findStock(secCode)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "股票不存在"));
        List<FinancialReport> financialReports =
                financialReportRepository.findBySecCodeOrderByReportDateDesc(secCode);
        List<MajorEvent> majorEvents =
                majorEventRepository.findBySecCodeOrderByEventDateDescIdDesc(secCode);

        model.addAttribute("stock", stock);
        model.addAttribute("klines", aStockService.findKlines(secCode, 60));
        model.addAttribute("valuation", aStockService.findLatestValuation(secCode).orElse(null));
        model.addAttribute("moneyFlows", aStockService.findMoneyFlows(secCode, 30));
        model.addAttribute("holderNumbers", aStockService.findHolderNumbers(secCode, 12));
        model.addAttribute("margins", aStockService.findMargins(secCode, 30));
        model.addAttribute("consensus", aStockService.findLatestConsensus(secCode).orElse(null));
        model.addAttribute("forecasts", aStockService.findForecasts(secCode, 20));
        model.addAttribute("northboundHoldings", aStockService.findNorthboundHoldings(secCode, 12));
        model.addAttribute("financialReports", financialReports);
        model.addAttribute("majorEvents", majorEvents);
        return "astock-detail";
    }
}
