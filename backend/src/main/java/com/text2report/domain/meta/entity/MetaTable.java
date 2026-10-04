package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Table metadata entity
 */
@Data
@TableName("meta_tables")
public class MetaTable {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Data source ID
     */
    private Integer datasourceId;

    /**
     * Schema name
     */
    private String schemaName;

    /**
     * Table name
     */
    private String tableName;

    /**
     * Display label
     */
    private String label;

    /**
     * Description
     */
    private String description;

    /**
     * Table type: fact, dimension
     */
    private String tableType;

    /**
     * Business domain
     */
    private String domain;

    /**
     * Active status
     */
    private Boolean active;

    /**
     * Whether this table is available for production query/RAG.
     */
    @TableField("published")
    private Boolean published = false;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
