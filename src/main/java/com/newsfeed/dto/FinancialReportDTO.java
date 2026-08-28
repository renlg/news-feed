package com.newsfeed.dto;

import java.time.LocalDate;

public record FinancialReportDTO(
        Long id,
        String reportPeriod,
        String reportType,
        LocalDate noticeDate,
        Double totalOperateIncome,
        Double parentNetProfit,
        Double basicEps,
        Double weightAvgRoe,
        Double ystz,
        Double sjltz) {
}
