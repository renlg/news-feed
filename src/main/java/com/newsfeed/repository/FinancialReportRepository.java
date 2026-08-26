package com.newsfeed.repository;

import com.newsfeed.model.FinancialReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FinancialReportRepository extends JpaRepository<FinancialReport, Long> {

    List<FinancialReport> findTop100ByOrderByNoticeDateDesc();

    Optional<FinancialReport> findBySecCodeAndReportDate(String secCode, LocalDate reportDate);

    List<FinancialReport> findBySecCodeOrderByReportDateDesc(String secCode);

    Page<FinancialReport> findAllByOrderByNoticeDateDesc(Pageable pageable);

    @Query("SELECT f FROM FinancialReport f WHERE " +
           "(:period IS NULL OR :period = '' OR f.reportPeriod = :period) AND " +
           "(:keyword IS NULL OR :keyword = '' OR LOWER(f.secCode) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(f.secName) LIKE LOWER(CONCAT('%', :keyword, '%'))) ")
    Page<FinancialReport> search(@Param("period") String reportPeriod,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);

    @Query("SELECT DISTINCT f.reportPeriod FROM FinancialReport f " +
           "WHERE f.reportPeriod IS NOT NULL ORDER BY f.reportPeriod DESC")
    List<String> findDistinctReportPeriods();
}
