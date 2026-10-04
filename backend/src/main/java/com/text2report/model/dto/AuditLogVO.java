package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit log list item returned to admin UI.
 */
@Data
@Builder
public class AuditLogVO {

    private String requestId;
    private String userId;
    private String username;
    private String displayName;
    private String orgId;
    private String orgName;
    private String question;
    private String sqlSnippet;
    private String generatedSql;
    private String status;
    private BigDecimal confidence;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long latencyMs;
    private Long executionMs;
    private Integer rowCount;
    private Integer retryCount;
    private Boolean cacheHit;
    private Integer feedbackRating;
    private LocalDateTime createdAt;
}
