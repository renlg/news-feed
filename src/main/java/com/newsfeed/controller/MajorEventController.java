package com.newsfeed.controller;

import com.newsfeed.model.MajorEvent;
import com.newsfeed.repository.MajorEventRepository;
import com.newsfeed.service.MajorEventFetchService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
@RequestMapping("/events")
@RequiredArgsConstructor
public class MajorEventController {

    private final MajorEventRepository majorEventRepository;
    private final MajorEventFetchService majorEventFetchService;

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

}
