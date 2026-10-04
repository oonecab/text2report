package com.text2report.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Query lifecycle context shared across orchestration steps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryContext {

    private String requestId;
    private String question;
    private Integer orgId;
    private Integer userId;
    private String intent;
    private MetadataContext metadataContext;
    private List<Integer> orgSubtreeIds;
    private String orgPath;
    private String prompt;
    private String metadataJson;
    private String generatedSql;
    private String validatedSql;
    private Integer llmProviderId;
    private String llmProviderKey;
    private String llmVendorType;
    private String llmModelCode;
    private Integer llmRoutingVersion;
    private int retryCount;
    private int maxRetries;
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    private long startTime;
    private BigDecimal confidence;
}
