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
@Table(name = "financial_report", uniqueConstraints = {
        @UniqueConstraint(name = "uk_financial_report_code_date", columnNames = {"sec_code", "report_date"})
})
public class FinancialReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sec_code", nullable = false, length = 16)
    private String secCode;

    @Column(name = "sec_name", length = 128)
    private String secName;

    @Column(name = "secu_code", length = 24)
    private String secuCode;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "report_period", length = 16)
    private String reportPeriod;

    @Column(name = "report_type", length = 32)
    private String reportType;

    @Column(name = "total_operate_income")
    private Double totalOperateIncome;

    @Column(name = "parent_net_profit")
    private Double parentNetProfit;

    @Column(name = "basic_eps")
    private Double basicEps;

    @Column(name = "deduct_basic_eps")
    private Double deductBasicEps;

    @Column(name = "weight_avg_roe")
    private Double weightAvgRoe;

    private Double bps;

    private Double mgjyxjje;

    private Double ystz;

    private Double sjltz;

    @Column(name = "notice_date")
    private LocalDate noticeDate;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = CanonicalTime.now();
        }
        if (fetchedAt == null) {
            fetchedAt = CanonicalTime.now();
        }
    }
}
