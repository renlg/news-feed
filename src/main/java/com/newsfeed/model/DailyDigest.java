package com.newsfeed.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "daily_digest")
public class DailyDigest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String digestDate;

    @Column(columnDefinition = "TEXT")
    private String aiCategory;

    @Column(columnDefinition = "TEXT")
    private String techCategory;

    @Column(columnDefinition = "TEXT")
    private String domesticCategory;

    @Column(columnDefinition = "TEXT")
    private String japanCategory;

    @Column(columnDefinition = "TEXT")
    private String internationalCategory;

    @Column(columnDefinition = "TEXT")
    private String rawAiArticles;

    @Column(columnDefinition = "TEXT")
    private String rawTechArticles;

    @Column(columnDefinition = "TEXT")
    private String rawDomesticArticles;

    @Column(columnDefinition = "TEXT")
    private String rawJapanArticles;

    @Column(columnDefinition = "TEXT")
    private String rawInternationalArticles;

    private LocalDateTime createdAt;

    private LocalDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
