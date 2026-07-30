package com.newsfeed.controller;

import com.newsfeed.service.ArticleRetentionService;
import com.newsfeed.service.FetchLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final FetchLogService fetchLogService;
    private final ArticleRetentionService articleRetentionService;

    @GetMapping
    public String report(Model model) {
        int days = 14;
        List<FetchLogService.DailyStat> stats = fetchLogService.getDailyStats(days);

        model.addAttribute("stats", stats);
        model.addAttribute("labels",
                stats.stream().map(FetchLogService.DailyStat::getDate).collect(Collectors.toList()));
        model.addAttribute("failureData",
                stats.stream().map(FetchLogService.DailyStat::getFailures).collect(Collectors.toList()));
        model.addAttribute("successData",
                stats.stream().map(FetchLogService.DailyStat::getSuccesses).collect(Collectors.toList()));

        long totalFailures = stats.stream().mapToLong(FetchLogService.DailyStat::getFailures).sum();
        long totalSuccesses = stats.stream().mapToLong(FetchLogService.DailyStat::getSuccesses).sum();
        model.addAttribute("totalFailures", totalFailures);
        model.addAttribute("totalSuccesses", totalSuccesses);

        List<FetchLogService.SourceRank> ranking = fetchLogService.getFailureRanking(days, 10);
        model.addAttribute("failureRanking", ranking);

        return "report";
    }

    @GetMapping("/backups")
    public String backups(Model model) {
        model.addAttribute("backups", articleRetentionService.listBackups());
        return "backups";
    }

    @GetMapping("/backups/{yearMonth}/download")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String yearMonth) {
        Path path = articleRetentionService.getBackupPath(yearMonth);

        if (!path.toFile().exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=articles_" + yearMonth + ".zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(path.toFile().length())
                .body(resource);
    }
}
