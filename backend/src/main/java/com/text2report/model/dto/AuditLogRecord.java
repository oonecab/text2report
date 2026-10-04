package com.text2report.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Raw audit log record loaded from database for admin views.
 */
@Data
public class AuditLogRecord {

    private String requestId;
    private Integer userId;
    private Integer orgId;
    private String orgName;
    private String question;
    private String generatedSql;
    private String promptContext;
    private String status;
    private Long executionMs;
    private Integer rowCount;
    private BigDecimal confidence;
    private Integer retryCount;
    private Boolean cacheHit;
    private Integer feedbackRating;
    private LocalDateTime createdAt;
    private String errorMessage;
}
