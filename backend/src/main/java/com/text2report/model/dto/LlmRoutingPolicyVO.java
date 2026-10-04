package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LlmRoutingPolicyVO {
    private Integer id;
    private String capabilityCode;
    private Integer providerId;
    private String providerKey;
    private String providerName;
    private String modelCode;
    private Integer fallbackProviderId;
    private Boolean active;
    private Integer routingVersion;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
