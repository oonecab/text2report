package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmAvailabilityVO {
    private Boolean configured;
    private String message;
    private Integer providerId;
    private String providerKey;
    private String displayName;
    private String modelCode;
    private String vendorType;
    private Integer routingVersion;
    private Integer configVersion;
}
