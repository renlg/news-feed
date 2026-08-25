package com.newsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newsfeed.config.CanonicalTime;
import com.newsfeed.model.MajorEvent;
import com.newsfeed.repository.MajorEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MajorEventFetchService {

    private static final String LIST_API_URL = "http://www.cninfo.com.cn/new/hisAnnouncement/query";
    private static final String PDF_BASE_URL = "http://static.cninfo.com.cn/";
    private static final List<String> COLUMNS = List.of("sse", "szse");
    private static final int PAGE_SIZE = 50;
    private static final long PAGE_DELAY_MILLIS = 300L;

    private final MajorEventRepository majorEventRepository;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    @Value("${major-event.pdf.base-dir:/opt/news-feed/events-pdf}")
    private String pdfBaseDir;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Scheduled(
            fixedDelayString = "${major-event.fetch.interval-ms:3600000}",
            initialDelayString = "${major-event.fetch.initial-delay-ms:120000}"
    )
    @Async
    public void scheduledFetch() {
        try {
            fetchRecent(false);
        } catch (Exception e) {
            log.error("重大事件抓取失败", e);
        }
    }

    public FetchResult fetchRecent(boolean backfill) {
        if (!fetching.compareAndSet(false, true)) {
            return FetchResult.busy();
        }

        int added = 0;
        int updated = 0;
        int processed = 0;
        LocalDate endDate = CanonicalTime.today();
        LocalDate startDate = backfill ? endDate.minusDays(365) : endDate.minusDays(1);
        try {
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                for (String column : COLUMNS) {
                    FetchResult result = fetchDate(column, date);
                    added += result.added();
                    updated += result.updated();
                    processed += result.processed();
                }
            }
            log.info("重大事件抓取完成: 新增{}条, 更新{}条", added, updated);
            return new FetchResult(false, added, updated, processed);
        } finally {
            fetching.set(false);
        }
    }

    public boolean isFetching() {
        return fetching.get();
    }

    public Path resolvePdfPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path base = Path.of(pdfBaseDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private FetchResult fetchDate(String column, LocalDate date) {
        int added = 0;
        int updated = 0;
        int processed = 0;
        int pageNumber = 1;
        int totalPages = 1;

        while (pageNumber <= totalPages) {
            JsonNode root = fetchPage(column, date, pageNumber);
            int total = root.path("totalAnnouncement").asInt(0);
            totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            JsonNode announcements = root.path("announcements");
            log.info("重大事件抓取: 日期 {}, 市场 {}, 第{}页/{}页, 共{}条",
                    date, column, pageNumber, totalPages, total);

            if (!announcements.isArray() || announcements.isEmpty()) {
                break;
            }
            FetchResult pageResult = upsertAnnouncements(announcements);
            added += pageResult.added();
            updated += pageResult.updated();
            processed += pageResult.processed();

            pageNumber++;
            if (pageNumber <= totalPages) {
                pauseBetweenPages();
            }
        }
        return new FetchResult(false, added, updated, processed);
    }

    private JsonNode fetchPage(String column, LocalDate date, int pageNumber) {
        String form = "pageNum=" + pageNumber +
                "&pageSize=" + PAGE_SIZE +
                "&column=" + encode(column) +
                "&tabName=fulltext" +
                "&plate=&stock=&searchkey=&secid=&category=&trade=" +
                "&seDate=" + encode(date + "~" + date) +
                "&sortName=&sortType=&isHLtitle=true";

        HttpRequest request = HttpRequest.newBuilder(URI.create(LIST_API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "http://www.cninfo.com.cn/")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("CNINFO HTTP status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重大事件抓取被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("读取巨潮资讯公告失败", e);
        }
    }

    private FetchResult upsertAnnouncements(JsonNode announcements) {
        int added = 0;
        int updated = 0;
        int processed = 0;
        for (JsonNode announcement : announcements) {
            String secCode = text(announcement, "secCode");
            String title = normalizedTitle(text(announcement, "announcementTitle"));
            LocalDate eventDate = eventDate(announcement);
            String category = classify(title);
            if (secCode == null || title == null || eventDate == null || category == null) {
                continue;
            }

            Optional<MajorEvent> existing = majorEventRepository.findBySecCodeAndTitle(secCode, title);
            MajorEvent event = existing.orElseGet(MajorEvent::new);
            boolean isNew = event.getId() == null;
            String adjunctUrl = text(announcement, "adjunctUrl");
            String pdfUrl = fullPdfUrl(adjunctUrl);

            event.setSecCode(secCode);
            event.setSecName(text(announcement, "secName"));
            event.setTitle(title);
            event.setEventDate(eventDate);
            event.setCategory(category);
            event.setPdfUrl(pdfUrl);
            event.setFetchedAt(CanonicalTime.now());

            if ((event.getPdfPath() == null || event.getPdfPath().isBlank()) && pdfUrl != null) {
                try {
                    event.setPdfPath(downloadPdf(pdfUrl, secCode, title, eventDate));
                } catch (RuntimeException e) {
                    log.warn("重大事件 PDF 下载失败: {} {} - {}", secCode, title, e.getMessage());
                }
            }
            majorEventRepository.save(event);
            if (isNew) {
                added++;
            } else {
                updated++;
            }
            processed++;
        }
        return new FetchResult(false, added, updated, processed);
    }

    private String downloadPdf(String pdfUrl, String secCode, String title, LocalDate eventDate) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(pdfUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/pdf")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "http://www.cninfo.com.cn/")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP status " + response.statusCode());
            }
            if (!startsWithPdfSignature(body)) {
                throw new IllegalStateException("响应内容不是 PDF");
            }

            String safeCode = secCode.replaceAll("[^0-9A-Za-z_-]", "_");
            String relativePath = eventDate.getYear() + "/" + safeCode + "_" + eventDate + "_" +
                    shortHash(secCode + "\n" + title) + ".pdf";
            Path destination = resolvePdfPath(relativePath);
            if (destination == null) {
                throw new IllegalStateException("PDF 存储路径无效");
            }
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), ".major-event-", ".tmp");
            try {
                Files.write(temporary, body);
                try {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return relativePath;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF 下载被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("保存 PDF 失败", e);
        }
    }

    static String classify(String title) {
        if (title == null) {
            return null;
        }
        if (containsAny(title, "增持", "减持")) return "增持减持";
        if (title.contains("质押")) return "质押";
        if (containsAny(title, "重组", "并购", "资产重组")) return "重组";
        if (title.contains("收购")) return "收购";
        if (containsAny(title, "诉讼", "仲裁")) return "诉讼";
        if (containsAny(title, "股权变动", "权益变动", "控制权")) return "股权变动";
        if (containsAny(title, "重大合同", "中标")) return "重大合同";
        if (title.contains("回购")) return "回购";
        if (containsAny(title, "分红", "利润分配")) return "分红";
        if (title.contains("业绩预告")) return "业绩预告";
        if (title.contains("业绩快报")) return "业绩快报";
        if (containsAny(title, "被立案", "立案调查", "处罚")) return "立案处罚";
        return null;
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private LocalDate eventDate(JsonNode announcement) {
        JsonNode value = announcement.get("announcementTime");
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(value.asLong()).atZone(CanonicalTime.ZONE).toLocalDate();
        } catch (RuntimeException e) {
            log.warn("无法解析巨潮公告时间: {}", value);
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String result = value.asText().trim();
        return result.isEmpty() ? null : result;
    }

    private String normalizedTitle(String title) {
        if (title == null) {
            return null;
        }
        String withoutTags = title.replaceAll("<[^>]+>", "");
        String normalized = HtmlUtils.htmlUnescape(withoutTags).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String fullPdfUrl(String adjunctUrl) {
        if (adjunctUrl == null) {
            return null;
        }
        if (adjunctUrl.startsWith("http://") || adjunctUrl.startsWith("https://")) {
            return adjunctUrl;
        }
        return PDF_BASE_URL + (adjunctUrl.startsWith("/") ? adjunctUrl.substring(1) : adjunctUrl);
    }

    private boolean startsWithPdfSignature(byte[] bytes) {
        byte[] signature = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (bytes == null || bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void pauseBetweenPages() {
        try {
            Thread.sleep(PAGE_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重大事件抓取被中断", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record FetchResult(boolean alreadyRunning, int added, int updated, int processed) {
        public static FetchResult busy() {
            return new FetchResult(true, 0, 0, 0);
        }
    }
}
