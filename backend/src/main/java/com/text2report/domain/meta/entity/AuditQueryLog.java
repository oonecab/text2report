package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Query audit log entity
 */
@Data
@TableName("audit_query_logs")
public class AuditQueryLog {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Request ID
     */
    private String requestId;

    /**
     * User ID
     */
    private Integer userId;

    /**
     * Organization ID
     */
    private Integer orgId;

    /**
     * Natural language question
     */
    private String question;

    /**
     * Sanitized prompt context used for SQL generation.
     */
    private String promptContext;

    /**
     * Generated SQL (null if blocked)
     */
    private String sql;

    /**
     * Query summary text.
     */
    private String summary;

    /**
     * Recommended chart for rendering.
     */
    private String recommendedChart;

    /**
     * Execution status: pending, success, blocked, error
     */
    private String status;

    /**
     * Error message if failed
     */
    private String errorMessage;

    /**
     * Execution time in ms
     */
    private Long executionTimeMs;

    /**
     * Rows returned
     */
    private Integer rowsReturned;

    /**
     * LLM confidence score
     */
    private Double confidence;

    /**
     * User feedback: thumbs_up, thumbs_down, null
     */
    private String feedback;

    /**
     * Feedback numeric rating.
     */
    private Integer feedbackRating;

    /**
     * Feedback comment
     */
    private String feedbackComment;

    /**
     * Feedback submit time.
     */
    private LocalDateTime feedbackAt;

    /**
     * Whether the query result was served from cache.
     */
    private Boolean cacheHit;

    /**
     * Favorite flag.
     */
    private Boolean isFavorite;

    /**
     * Favorite time.
     */
    private LocalDateTime favoriteAt;

    /**
     * Retry count
     */
    private Integer retryCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
