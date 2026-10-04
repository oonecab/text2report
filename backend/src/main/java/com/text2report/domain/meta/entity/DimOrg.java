package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Metadata organization entity backed by dim_org.
 */
@Data
@TableName("dim_org")
public class DimOrg {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String orgName;
    private Integer parentId;
    private Integer orgLevel;
    private String orgCode;
    private Boolean isActive;
    private LocalDateTime syncedAt;
}
