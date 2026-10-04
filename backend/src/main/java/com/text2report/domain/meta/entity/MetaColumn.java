package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Column metadata entity
 */
@Data
@TableName("meta_columns")
public class MetaColumn {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Table ID
     */
    private Integer tableId;

    /**
     * Column name
     */
    private String columnName;

    /**
     * Display label
     */
    private String label;

    /**
     * Data type
     */
    private String dataType;

    /**
     * Description
     */
    private String description;

    /**
     * Sensitivity level: normal, sensitive, restricted
     */
    private String sensitivity;

    /**
     * Comment source: ddl, manual
     */
    private String commentSource;

    /**
     * Whether this column is a metric (for aggregation)
     */
    private Boolean isMetric;

    /**
     * Aggregation type if metric: sum, avg, count, min, max
     */
    private String aggregationType;

    /**
     * Display order
     */
    private Integer sortOrder;

    /**
     * Active status
     */
    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
