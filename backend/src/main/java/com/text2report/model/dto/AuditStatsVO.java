package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Audit statistics summary for admin dashboard.
 */
@Data
@Builder
public class AuditStatsVO {

    private long totalQueries;
    private long successCount;
    private long failedCount;
    private long avgExecutionMs;
    private long avgLatencyMs;
    private BigDecimal avgConfidence;
    private BigDecimal cacheHitRate;
    private BigDecimal successRate;
    private List<TopUser> topUsers;
    private List<DailyTrend> dailyTrend;

    @Data
    @Builder
    public static class TopUser {
        private String userId;
        private String username;
        private String displayName;
        private long count;
    }

    @Data
    @Builder
    public static class DailyTrend {
        private LocalDate date;
        private long count;
        private long avgMs;
    }
}
