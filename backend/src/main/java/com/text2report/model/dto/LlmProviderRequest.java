package com.text2report.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LlmProviderRequest {
    @NotBlank(message = "providerKey is required")
    private String providerKey;
    @NotBlank(message = "displayName is required")
    private String displayName;
    @NotBlank(message = "providerMode is required")
    private String providerMode;
    @NotBlank(message = "vendorType is required")
    private String vendorType;
    private String deploymentType;
    @NotBlank(message = "modelCode is required")
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
    private String apiKey;
}
