package com.text2report.model.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structured query result DTO aligned with frontend contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class QueryResultDTO {

    private String requestId;
    private String sql;
    private Boolean isFavorited;
    private TableData table;
    private ResultProfile resultProfile;
    private String summary;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    private AuditInfo audit;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TableData {
        @Builder.Default
        private List<ColumnMeta> columns = new ArrayList<>();
        @Builder.Default
        private List<Map<String, Object>> rows = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ColumnMeta {
        private String key;
        private String title;
        private String dataType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class AuditInfo {
        private BigDecimal confidence;
        private int retryCount;
        private long executionMs;
    }
}
