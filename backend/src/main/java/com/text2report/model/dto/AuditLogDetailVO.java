package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Audit log detail returned to admin UI.
 */
@Data
@Builder
public class AuditLogDetailVO {

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
    private LocalDateTime createdAt;
    private String fullSql;
    private String promptSummary;
    private List<String> warnings;
    private Integer retryCount;
    private String errorMessage;
    private Boolean cacheHit;
    private Integer feedbackRating;
}
