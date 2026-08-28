package com.newsfeed.dto;

import java.time.LocalDate;

public record MajorEventDTO(
        Long id,
        LocalDate eventDate,
        String category,
        String title,
        String pdfPath,
        String pdfUrl) {
}
