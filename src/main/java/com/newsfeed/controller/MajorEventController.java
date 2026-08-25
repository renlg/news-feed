package com.newsfeed.controller;

import com.newsfeed.model.MajorEvent;
import com.newsfeed.repository.MajorEventRepository;
import com.newsfeed.service.MajorEventFetchService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/events")
@RequiredArgsConstructor
public class MajorEventController {

    private static final List<String> CATEGORIES = List.of(
            "增持减持", "质押", "重组", "收购", "诉讼", "股权变动", "重大合同",
            "回购", "分红", "业绩预告", "业绩快报", "立案处罚"
    );

    private final MajorEventRepository majorEventRepository;
    private final MajorEventFetchService majorEventFetchService;

    @GetMapping
    public String list(@RequestParam(value = "q", required = false) String keyword,
                       @RequestParam(value = "category", required = false) String category,
                       @RequestParam(value = "page", defaultValue = "0") int page,
                       @RequestParam(value = "size", defaultValue = "25") int size,
                       Model model) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "eventDate")
                        .and(Sort.by(Sort.Direction.DESC, "id")));
        Page<MajorEvent> events = majorEventRepository.search(category, keyword, pageable);

        model.addAttribute("events", events);
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("category", category != null ? category : "");
        model.addAttribute("q", keyword != null ? keyword : "");
        model.addAttribute("size", events.getSize());
        model.addAttribute("fetching", majorEventFetchService.isFetching());
        return "events";
    }

    @GetMapping("/detail")
    public String detail(@RequestParam("id") Long id, Model model) {
        MajorEvent event = majorEventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "重大事件不存在"));
        model.addAttribute("event", event);
        return "events-detail";
    }

    @GetMapping("/pdf/{id}")
    public ResponseEntity<Resource> pdf(@PathVariable Long id) {
        MajorEvent event = majorEventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "重大事件不存在"));
        Path path = majorEventFetchService.resolvePdfPath(event.getPdfPath());
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new ResponseStatusException(NOT_FOUND, "PDF 文件不存在");
        }

        Resource resource = new FileSystemResource(path);
        String filename = event.getSecCode() + "_" + event.getEventDate() + ".pdf";
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(path.toFile().length())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    @PostMapping("/fetch")
    public String fetchNow(RedirectAttributes redirectAttributes) {
        try {
            MajorEventFetchService.FetchResult result = majorEventFetchService.fetchRecent(false);
            addResultMessage(result, false, redirectAttributes);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMsg", "重大事件抓取失败：" + e.getMessage());
        }
        return "redirect:/events";
    }

    @PostMapping("/backfill")
    public String backfill(RedirectAttributes redirectAttributes) {
        try {
            MajorEventFetchService.FetchResult result = majorEventFetchService.fetchRecent(true);
            addResultMessage(result, true, redirectAttributes);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMsg", "重大事件回填失败：" + e.getMessage());
        }
        return "redirect:/events";
    }

    private void addResultMessage(MajorEventFetchService.FetchResult result, boolean backfill,
                                  RedirectAttributes redirectAttributes) {
        if (result.alreadyRunning()) {
            redirectAttributes.addFlashAttribute("successMsg", "重大事件抓取任务正在运行，请稍后刷新");
            return;
        }
        String action = backfill ? "最近1年回填完成" : "抓取完成";
        redirectAttributes.addFlashAttribute("successMsg",
                "重大事件" + action + "：新增 " + result.added() + " 条，更新 " + result.updated() + " 条");
    }
}
