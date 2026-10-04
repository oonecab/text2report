package com.text2report.model.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Favorite query item DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class FavoriteItem {

    private String requestId;
    private String question;
    private Integer orgId;
    private String orgName;
    private String recommendedChart;
    private Integer rowCount;
    private LocalDateTime favoritedAt;
    private LocalDateTime lastRunAt;
}
