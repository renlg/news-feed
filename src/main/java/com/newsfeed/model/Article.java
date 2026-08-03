package com.newsfeed.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import com.newsfeed.config.CanonicalTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "article")
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String link;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private String author;

    @Column(columnDefinition = "TEXT")
    private String category;

    // AI处理字段
    @Column(name = "ai_category")
    private String aiCategory;

    @Column(name = "importance_score")
    private Integer importanceScore;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "ai_processed")
    @Builder.Default
    private Boolean aiProcessed = false;

    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    @Column(nullable = false)
    private Long feedSourceId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = CanonicalTime.now();
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feedSourceId", insertable = false, updatable = false)
    private FeedSource feedSource;
}
