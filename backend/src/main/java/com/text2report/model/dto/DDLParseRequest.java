package com.text2report.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for DDL parse operation.
 */
@Data
public class DDLParseRequest {

    @NotBlank(message = "DDL content is required")
    private String rawDdl;

    /**
     * Data source ID for the tables being parsed.
     * Used to associate parsed tables with a specific data source.
     */
    private Integer datasourceId;
}
