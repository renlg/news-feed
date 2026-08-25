package com.newsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsfeed.config.CanonicalTime;
import com.newsfeed.model.FinancialReport;
import com.newsfeed.repository.FinancialReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialReportFetchService {

    private static final String API_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";
    private static final int PAGE_SIZE = 50;
    private static final int REPORT_PERIODS_TO_FETCH = 3;
    private static final long PAGE_DELAY_MILLIS = 250L;
    private static final LocalDate HISTORICAL_BACKFILL_START = LocalDate.of(2020, 3, 31);

    private final FinancialReportRepository financialReportRepository;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    @Value("${financial.backfill.enabled:true}")
    private boolean historicalBackfillEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Scheduled(
            fixedDelayString = "${financial.fetch.interval-ms:21600000}",
            initialDelayString = "${financial.fetch.initial-delay-ms:60000}"
    )
    @Async
    public void scheduledFetch() {
        try {
            fetchReports();
        } catch (Exception e) {
            log.error("财报抓取失败", e);
        }
    }

    public FetchResult fetchReports() {
        if (!fetching.compareAndSet(false, true)) {
            return FetchResult.busy();
        }

        int added = 0;
        int updated = 0;
        int processed = 0;
        try {
            LocalDate earliestReportDate = earliestOfLatestCompletedPeriods(
                    CanonicalTime.today(), REPORT_PERIODS_TO_FETCH);
            int pageNumber = 1;
            int totalPages = 1;
            int totalRows = 0;

            while (pageNumber <= totalPages) {
                JsonNode result = fetchPage(pageNumber, earliestReportDate);
                totalRows = result.path("count").asInt(0);
                totalPages = Math.max(1, (totalRows + PAGE_SIZE - 1) / PAGE_SIZE);
                JsonNode rows = result.path("data");

                log.info("财报抓取: 第{}页/{}页, 共{}条", pageNumber, totalPages, totalRows);
                if (!rows.isArray() || rows.isEmpty()) {
                    break;
                }

                FetchResult pageResult = upsertRows(rows);
                added += pageResult.added();
                updated += pageResult.updated();
                processed += pageResult.processed();

                pageNumber++;
                if (pageNumber <= totalPages) {
                    pauseBetweenPages();
                }
            }

            log.info("财报抓取完成: 新增{}条, 更新{}条", added, updated);
            return new FetchResult(false, added, updated, processed);
        } finally {
            fetching.set(false);
        }
    }

    public FetchResult fetchHistoricalBackfill() {
        if (!historicalBackfillEnabled) {
            throw new IllegalStateException("财报历史回填已通过配置禁用");
        }
        if (!fetching.compareAndSet(false, true)) {
            return FetchResult.busy();
        }

        int added = 0;
        int updated = 0;
        int processed = 0;
        try {
            List<LocalDate> periodEnds = completedQuarterEnds(
                    HISTORICAL_BACKFILL_START, CanonicalTime.today());
            for (int periodIndex = 0; periodIndex < periodEnds.size(); periodIndex++) {
                LocalDate periodEnd = periodEnds.get(periodIndex);
                log.info("财报补历史: 第{}期/共{}期, 报告期 {}",
                        periodIndex + 1, periodEnds.size(), periodEnd);

                int pageNumber = 1;
                int totalPages = 1;
                while (pageNumber <= totalPages) {
                    JsonNode result = fetchPageForPeriod(pageNumber, periodEnd);
                    int totalRows = result.path("count").asInt(0);
                    totalPages = Math.max(1, (totalRows + PAGE_SIZE - 1) / PAGE_SIZE);
                    JsonNode rows = result.path("data");

                    log.info("财报补历史: 报告期 {}, 第{}页/{}页, 共{}条",
                            periodEnd, pageNumber, totalPages, totalRows);
                    if (!rows.isArray() || rows.isEmpty()) {
                        break;
                    }

                    FetchResult pageResult = upsertRows(rows);
                    added += pageResult.added();
                    updated += pageResult.updated();
                    processed += pageResult.processed();

                    pageNumber++;
                    if (pageNumber <= totalPages) {
                        pauseBetweenPages();
                    }
                }
            }

            log.info("财报补历史完成: 新增{}条, 更新{}条", added, updated);
            return new FetchResult(false, added, updated, processed);
        } finally {
            fetching.set(false);
        }
    }

    public boolean isFetching() {
        return fetching.get();
    }

    public boolean isHistoricalBackfillEnabled() {
        return historicalBackfillEnabled;
    }

    private JsonNode fetchPage(int pageNumber, LocalDate earliestReportDate) {
        String filter = "(REPORTDATE>='" + earliestReportDate + "')";
        return fetchPage(pageNumber, filter);
    }

    private JsonNode fetchPageForPeriod(int pageNumber, LocalDate periodEnd) {
        String filter = "(REPORTDATE='" + periodEnd + "')";
        return fetchPage(pageNumber, filter);
    }

    private JsonNode fetchPage(int pageNumber, String filter) {
        String query = "reportName=" + encode("RPT_LICO_FN_CPD") +
                "&columns=" + encode("ALL") +
                "&filter=" + encode(filter) +
                "&pageNumber=" + pageNumber +
                "&pageSize=" + PAGE_SIZE +
                "&sortColumns=" + encode("NOTICE_DATE") +
                "&sortTypes=-1";

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL + "?" + query))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://data.eastmoney.com/")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("EastMoney HTTP status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("success").asBoolean(false) || root.path("result").isMissingNode()
                    || root.path("result").isNull()) {
                throw new IllegalStateException("EastMoney API returned an unsuccessful response: " +
                        root.path("message").asText("unknown error"));
            }
            return root.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("财报抓取被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("读取 EastMoney 财报数据失败", e);
        }
    }

    private FetchResult upsertRows(JsonNode rows) {
        int added = 0;
        int updated = 0;
        int processed = 0;
        for (JsonNode row : rows) {
            String secCode = text(row, "SECURITY_CODE");
            LocalDate reportDate = date(row, "REPORTDATE");
            if (secCode == null || reportDate == null) {
                continue;
            }

            FinancialReport report = financialReportRepository
                    .findBySecCodeAndReportDate(secCode, reportDate)
                    .orElseGet(FinancialReport::new);
            boolean isNew = report.getId() == null;
            updateReport(report, row, secCode, reportDate);
            financialReportRepository.save(report);
            if (isNew) {
                added++;
            } else {
                updated++;
            }
            processed++;
        }
        return new FetchResult(false, added, updated, processed);
    }

    private void updateReport(FinancialReport report, JsonNode row, String secCode, LocalDate reportDate) {
        report.setSecCode(secCode);
        report.setSecName(text(row, "SECURITY_NAME_ABBR"));
        report.setSecuCode(text(row, "SECUCODE"));
        report.setReportDate(reportDate);
        report.setReportPeriod(text(row, "QDATE"));
        report.setReportType(reportType(row));
        report.setTotalOperateIncome(number(row, "TOTAL_OPERATE_INCOME"));
        report.setParentNetProfit(number(row, "PARENT_NETPROFIT"));
        report.setBasicEps(number(row, "BASIC_EPS"));
        report.setDeductBasicEps(number(row, "DEDUCT_BASIC_EPS"));
        report.setWeightAvgRoe(number(row, "WEIGHTAVG_ROE"));
        report.setBps(number(row, "BPS"));
        report.setMgjyxjje(number(row, "MGJYXJJE"));
        report.setYstz(number(row, "YSTZ"));
        report.setSjltz(number(row, "SJLTZ"));
        report.setNoticeDate(date(row, "NOTICE_DATE"));
        report.setFetchedAt(CanonicalTime.now());
    }

    private String reportType(JsonNode row) {
        String dataType = text(row, "DATATYPE");
        if (dataType != null) {
            int yearSuffix = dataType.indexOf('年');
            String type = yearSuffix >= 0 ? dataType.substring(yearSuffix + 1).trim() : dataType.trim();
            if (!type.isEmpty()) {
                return type;
            }
        }

        String period = text(row, "QDATE");
        if (period == null || period.length() < 2) {
            return null;
        }
        return switch (period.substring(period.length() - 2)) {
            case "Q1" -> "一季报";
            case "Q2" -> "半年报";
            case "Q3" -> "三季报";
            case "Q4" -> "年报";
            default -> null;
        };
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private Double number(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        try {
            return Double.valueOf(value.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate date(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (RuntimeException e) {
            log.warn("无法解析 EastMoney 日期 {}={}", field, value);
            return null;
        }
    }

    private void pauseBetweenPages() {
        try {
            Thread.sleep(PAGE_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("财报抓取被中断", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static LocalDate earliestOfLatestCompletedPeriods(LocalDate today, int periodCount) {
        int currentQuarter = (today.getMonthValue() - 1) / 3;
        int quarterIndex = today.getYear() * 4 + currentQuarter;
        int quarterEndMonth = (currentQuarter + 1) * 3;
        LocalDate currentQuarterEnd = YearMonth.of(today.getYear(), quarterEndMonth).atEndOfMonth();
        if (today.isBefore(currentQuarterEnd)) {
            quarterIndex--;
        }

        int earliestIndex = quarterIndex - Math.max(periodCount - 1, 0);
        int year = Math.floorDiv(earliestIndex, 4);
        int quarter = Math.floorMod(earliestIndex, 4);
        return YearMonth.of(year, (quarter + 1) * 3).atEndOfMonth();
    }

    static List<LocalDate> completedQuarterEnds(LocalDate firstPeriodEnd, LocalDate today) {
        LocalDate latestPeriodEnd = earliestOfLatestCompletedPeriods(today, 1);
        List<LocalDate> periodEnds = new ArrayList<>();
        YearMonth period = YearMonth.from(firstPeriodEnd);
        YearMonth latest = YearMonth.from(latestPeriodEnd);
        while (!period.isAfter(latest)) {
            periodEnds.add(period.atEndOfMonth());
            period = period.plusMonths(3);
        }
        return periodEnds;
    }

    public record FetchResult(boolean alreadyRunning, int added, int updated, int processed) {
        public static FetchResult busy() {
            return new FetchResult(true, 0, 0, 0);
        }
    }
}
