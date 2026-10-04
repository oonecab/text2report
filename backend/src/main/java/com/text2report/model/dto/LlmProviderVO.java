package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class LlmProviderVO {
    private Integer id;
    private String providerKey;
    private String displayName;
    private String providerMode;
    private String vendorType;
    private String deploymentType;
    private String modelCode;
    private String baseUrl;
    private String host;
    private Integer port;
    private String endpointPath;
    private String authType;
    private String jsonMode;
    private Integer timeoutSeconds;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Boolean enabled;
    private Boolean isDefault;
    private Integer priority;
    private Integer configVersion;
    private String secretMask;
    private String lastTestStatus;
    private String lastTestMessage;
    private LocalDateTime lastTestedAt;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
