package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit log for model configuration management.
 */
@Data
@TableName("meta_llm_config_audit_log")
public class MetaLlmConfigAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private UUID requestId;
    private Integer userId;
    private Integer providerId;
    private String operationType;
    private String targetType;
    private String targetId;
    private String status;
    private String detail;
    private String ipAddress;
    private LocalDateTime createdAt;
}
