package com.text2report.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LlmRoutingPolicyRequest {
    @NotNull(message = "providerId is required")
    private Integer providerId;
    private Integer fallbackProviderId;
    private Boolean active;
}
