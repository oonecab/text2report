package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * System user organization grant entity.
 */
@Data
@TableName("sys_user_org")
public class SysUserOrg {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer userId;
    private Integer orgId;
    private String scope;
    private Boolean isPrimary;
    private String grantedBy;
    private LocalDateTime grantedAt;
}
