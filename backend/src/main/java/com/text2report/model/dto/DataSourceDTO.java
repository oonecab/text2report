package com.text2report.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Data Transfer Object for DataSource create/update operations.
 */
@Data
public class DataSourceDTO {

    private Integer id;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Database type is required")
    private String dbType;

    @NotBlank(message = "JDBC URL is required")
    private String jdbcUrl;

    @NotBlank(message = "Username is required")
    private String username;

    private String password;

    private String description;

    private Boolean readOnly;

    private Boolean active;
}
