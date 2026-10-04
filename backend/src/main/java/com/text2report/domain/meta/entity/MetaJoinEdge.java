package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Join relationship entity
 */
@Data
@TableName("meta_join_edges")
public class MetaJoinEdge {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Source table ID
     */
    private Integer fromTableId;

    /**
     * Target table ID
     */
    private Integer toTableId;

    /**
     * Source column
     */
    private String fromColumn;

    /**
     * Target column
     */
    private String toColumn;

    /**
     * Join type: inner, left, right
     */
    private String joinType;

    /**
     * Relationship description
     */
    private String description;

    /**
     * Active status
     */
    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
