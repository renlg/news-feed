package com.newsfeed.service;

import com.newsfeed.model.Article;
import com.newsfeed.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleRetentionService {

    private final ArticleRepository articleRepository;
    private final FetchLogService fetchLogService;

    @Value("${newsfeed.retention.days:30}")
    private int retentionDays;

    @Value("${newsfeed.backup.dir:./data/backups}")
    private String backupDir;

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOldArticles() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Article> oldArticles = articleRepository.findByPublishedAtBefore(cutoff);

        if (oldArticles.isEmpty()) {
            log.info("No articles older than {} days to clean up", retentionDays);
            return;
        }

        log.info("Found {} articles older than {} days, backing up and deleting...", oldArticles.size(), retentionDays);

        Map<String, List<Article>> grouped = new LinkedHashMap<>();
        for (Article a : oldArticles) {
            String yearMonth = formatYearMonth(a.getPublishedAt() != null ? a.getPublishedAt() : a.getFetchedAt());
            grouped.computeIfAbsent(yearMonth, k -> new ArrayList<>()).add(a);
        }

        try {
            Path backupPath = Paths.get(backupDir);
            Files.createDirectories(backupPath);

            for (Map.Entry<String, List<Article>> entry : grouped.entrySet()) {
                String yearMonth = entry.getKey();
                backupArticles(yearMonth, entry.getValue());
            }

            articleRepository.deleteByPublishedAtBefore(cutoff);
            articleRepository.flush();
            log.info("Deleted {} old articles", oldArticles.size());
        } catch (Exception e) {
            log.error("Failed to clean up old articles: {}", e.getMessage(), e);
        }

        fetchLogService.cleanupOldLogs(retentionDays + 30);
    }

    private void backupArticles(String yearMonth, List<Article> articles) throws IOException {
        Path zipPath = Paths.get(backupDir, "articles_" + yearMonth + ".zip");
        Path csvPath = Paths.get(backupDir, "articles_" + yearMonth + ".csv");

        if (Files.exists(csvPath)) {
            appendArticlesToCsv(csvPath, articles);
        } else {
            writeArticlesToCsv(csvPath, articles);
        }

        createZipFromCsv(zipPath, csvPath, yearMonth);
        log.info("Backed up {} articles to {}", articles.size(), zipPath.getFileName());
    }

    private void writeArticlesToCsv(Path path, List<Article> articles) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("id,title,link,summary,author,published_at,fetched_at,feed_source_id");
            writer.newLine();
            for (Article a : articles) {
                writeArticleRow(writer, a);
            }
        }
    }

    private void appendArticlesToCsv(Path path, List<Article> articles) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (Article a : articles) {
                writeArticleRow(writer, a);
            }
        }
    }

    private void writeArticleRow(BufferedWriter writer, Article a) throws IOException {
        writer.write(String.join(",",
                escape(String.valueOf(a.getId())),
                escape(a.getTitle()),
                escape(a.getLink()),
                escape(a.getSummary() != null ? a.getSummary() : ""),
                escape(a.getAuthor() != null ? a.getAuthor() : ""),
                a.getPublishedAt() != null ? a.getPublishedAt().toString() : "",
                a.getFetchedAt() != null ? a.getFetchedAt().toString() : "",
                String.valueOf(a.getFeedSourceId())
        ));
        writer.newLine();
    }

    private String escape(String field) {
        if (field == null) return "";
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    private void createZipFromCsv(Path zipPath, Path csvPath, String yearMonth) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()));
             InputStream is = Files.newInputStream(csvPath)) {
            ZipEntry entry = new ZipEntry("articles_" + yearMonth + ".csv");
            zos.putNextEntry(entry);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            zos.closeEntry();
        }
    }

    private String formatYearMonth(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    public List<BackupFile> listBackups() {
        try {
            Path backupPath = Paths.get(backupDir);
            if (!Files.exists(backupPath)) {
                return Collections.emptyList();
            }

            return Files.list(backupPath)
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        String yearMonth = name.replace("articles_", "").replace(".zip", "");
                        try {
                            long size = Files.size(p);
                            return new BackupFile(yearMonth, name, size);
                        } catch (IOException e) {
                            return new BackupFile(yearMonth, name, 0);
                        }
                    })
                    .sorted(Comparator.comparing(BackupFile::getYearMonth).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list backups: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public Path getBackupPath(String yearMonth) {
        if (yearMonth == null || !yearMonth.matches("^\\d{4}-\\d{2}$")) {
            throw new IllegalArgumentException("Invalid yearMonth format, expected yyyy-MM");
        }
        return Paths.get(backupDir, "articles_" + yearMonth + ".zip");
    }

    public static class BackupFile {
        private final String yearMonth;
        private final String fileName;
        private final long size;

        public BackupFile(String yearMonth, String fileName, long size) {
            this.yearMonth = yearMonth;
            this.fileName = fileName;
            this.size = size;
        }

        public String getYearMonth() { return yearMonth; }
        public String getFileName() { return fileName; }
        public long getSize() { return size; }
        public String getFormattedSize() {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
    }
}
