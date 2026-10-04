package com.text2report.model.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for DDL confirm and save operation.
 */
@Data
public class DDLConfirmRequest {

    private String uploadId;

    private Integer datasourceId;

    @NotEmpty(message = "At least one table is required")
    private List<ParsedTable> tables;

    @Data
    public static class ParsedTable {
        private String schemaName;
        private String tableName;
        private String suggestedLabel;
        private String description;
        private List<ParsedColumn> columns;
    }

    @Data
    public static class ParsedColumn {
        private String columnName;
        private String dataType;
        private String label;
        private String description;
        private String commentSource;
        private boolean needsReview;
    }
}
