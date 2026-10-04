package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Routes an LLM capability to a provider.
 */
@Data
@TableName("meta_llm_routing_policy")
public class MetaLlmRoutingPolicy {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private String capabilityCode;
    private Integer providerId;
    private Integer fallbackProviderId;
    private Boolean active;
    private Integer routingVersion;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
