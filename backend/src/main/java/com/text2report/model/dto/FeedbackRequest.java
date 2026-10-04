package com.text2report.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Feedback submit request.
 */
@Data
public class FeedbackRequest {

    @Min(value = 1, message = "rating 不能小于 1")
    @Max(value = 5, message = "rating 不能大于 5")
    private Integer rating;

    /**
     * Backward-compatible field for current frontend mock contract.
     * good -> 5, bad -> 1.
     */
    private String feedback;

    private String comment;
}
