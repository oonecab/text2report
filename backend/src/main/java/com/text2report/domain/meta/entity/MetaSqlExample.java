package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL example entity (question -> SQL pairs)
 */
@Data
@TableName("meta_sql_examples")
public class MetaSqlExample {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Natural language question
     */
    private String question;

    /**
     * Generated SQL
     */
    private String sqlQuery;

    /**
     * Business domain
     */
    private String domain;

    /**
     * Time grain: daily, monthly, quarterly, yearly
     */
    private String timeGrain;

    /**
     * Organization scope (null for global)
     */
    private Integer orgScope;

    /**
     * Usage count
     */
    private Integer usageCount;

    /**
     * Active status
     */
    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
