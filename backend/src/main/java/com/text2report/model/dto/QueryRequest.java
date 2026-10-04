package com.text2report.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Query submit request DTO.
 */
@Data
public class QueryRequest {

    @NotBlank(message = "question 不能为空")
    private String question;

    @NotNull(message = "orgId 不能为空")
    private Integer orgId;
}
