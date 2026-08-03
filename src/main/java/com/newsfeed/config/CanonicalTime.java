package com.newsfeed.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The application's canonical wall-clock timezone. Database LocalDateTime values are always
 * interpreted in this zone, so parsing, filtering, and rendering use the same calendar day.
 */
@Component("canonicalTime")
public final class CanonicalTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime fromInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    public static LocalDateTime at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(ZONE).toLocalDateTime();
    }

    public String format(LocalDateTime value) {
        return value == null ? "" : value.atZone(ZONE).format(DISPLAY_FORMAT);
    }
}
