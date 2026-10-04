package com.text2report.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserOrgGrantRequest {
    @NotNull
    private Integer userId;
    @NotNull
    private Integer orgId;
    private String scope;
    private Boolean isPrimary;
}
