package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DDL upload audit trail entity
 */
@Data
@TableName("meta_ddl_uploads")
public class MetaDdlUpload {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Original filename
     */
    private String filename;

    /**
     * File size in bytes
     */
    private Long fileSize;

    /**
     * Parsed table count
     */
    private Integer tableCount;

    /**
     * Parsed column count
     */
    private Integer columnCount;

    /**
     * Upload status: pending, success, failed
     */
    private String status;

    /**
     * Error message if failed
     */
    private String errorMessage;

    /**
     * User ID who uploaded
     */
    private Integer uploadedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
