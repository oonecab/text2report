package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmConnectionTestResult {
    private Boolean success;
    private String message;
    private Long latencyMs;
}
