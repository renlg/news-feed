package com.newsfeed.model;

import com.newsfeed.config.CanonicalTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "major_event", uniqueConstraints = {
        @UniqueConstraint(name = "uk_major_event_code_title", columnNames = {"sec_code", "title"})
})
public class MajorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sec_code", nullable = false, length = 16)
    private String secCode;

    @Column(name = "sec_name", length = 128)
    private String secName;

    @Column(nullable = false, length = 1024)
    private String title;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "pdf_path", length = 512)
    private String pdfPath;

    @Column(name = "pdf_url", length = 1024)
    private String pdfUrl;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @PrePersist
    protected void onCreate() {
        if (fetchedAt == null) {
            fetchedAt = CanonicalTime.now();
        }
    }
}
