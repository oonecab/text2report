package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Business glossary entity
 */
@Data
@TableName("meta_glossary")
public class MetaGlossary {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Business term
     */
    private String term;

    /**
     * Definition
     */
    private String definition;

    /**
     * SQL snippet for this term
     */
    private String sqlSnippet;

    /**
     * Comma-separated aliases
     */
    private String aliases;

    /**
     * Organization scope (null for global)
     */
    private Integer orgScope;

    /**
     * Active status
     */
    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
