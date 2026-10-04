package com.text2report.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Raw aggregate stats from audit log query.
 */
@Data
public class AuditStatsAggregate {

    private Long totalQueries;
    private Long successCount;
    private Long failedCount;
    private Long avgExecutionMs;
    private BigDecimal avgConfidence;
    private Long cacheHitCount;
}
