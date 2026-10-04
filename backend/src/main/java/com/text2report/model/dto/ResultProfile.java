package com.text2report.model.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result profile DTO - ONLY contains metadata about query results, never the actual data rows.
 *
 * Rule 1 Compliance: LLM only sees this profile, never raw result rows.
 *
 * Fix: P0-1 LLMService contract isolation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ResultProfile {

    /**
     * Number of rows returned
     */
    private int rowCount;

    /**
     * Column names from the result set
     */
    private List<String> columnNames;

    /**
     * Number of columns returned
     */
    private int columnCount;

    /**
     * Recommended chart type based on data shape
     */
    private String recommendedChart;

    /**
     * Whether the result set has any data
     */
    private boolean hasData;

    /**
     * Create a profile from query result columns and row count
     */
    public static ResultProfile fromColumnsAndRows(List<java.util.Map<String, String>> columns, int rowCount) {
        return ResultProfile.builder()
                .rowCount(rowCount)
                .columnNames(columns != null ? columns.stream()
                        .map(c -> c.get("key"))
                        .toList() : List.of())
                .columnCount(columns != null ? columns.size() : 0)
                .hasData(rowCount > 0)
                .recommendedChart(deriveChartType(columns, rowCount))
                .build();
    }

    /**
     * Derive chart type based on column metadata and row count
     */
    private static String deriveChartType(List<java.util.Map<String, String>> columns, int rowCount) {
        if (rowCount == 0) {
            return "kpi";
        }
        if (rowCount == 1) {
            return "kpi";
        }
        if (columns != null && columns.size() <= 2) {
            return "bar";
        }
        return "table";
    }
}
