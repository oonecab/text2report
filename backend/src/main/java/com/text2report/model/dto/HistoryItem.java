package com.text2report.model.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Query history item DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HistoryItem {

    private String requestId;
    private Integer userId;
    private String username;
    private String displayName;
    private String question;
    private String sql;
    private String summary;
    private BigDecimal confidence;
    private Integer rowCount;
    private Long executionMs;
    private String status;
    private String recommendedChart;
    private LocalDateTime createdAt;
    private Integer orgId;
    private String orgName;
    private Boolean isFavorite;
    private Integer retryCount;
    private String feedback;
    private QueryResultDTO response;
}
