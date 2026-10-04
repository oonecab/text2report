package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Data source registry entity
 */
@Data
@TableName("meta_datasource")
public class MetaDatasource {

    @TableId(type = IdType.AUTO)
    private Integer id;

    /**
     * Data source name
     */
    private String name;

    /**
     * Database type: sqlserver, postgresql, mysql
     */
    private String dbType;

    /**
     * JDBC connection URL
     */
    private String jdbcUrl;

    /**
     * Connection username
     */
    private String username;

    /**
     * Connection password (encrypted)
     */
    private String password;

    /**
     * Description
     */
    private String description;

    /**
     * Read-only flag
     */
    private Boolean readOnly;

    /**
     * Active status
     */
    private Boolean active;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
