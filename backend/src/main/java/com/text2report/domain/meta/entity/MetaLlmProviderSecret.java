package com.text2report.domain.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Encrypted secret for an LLM provider.
 */
@Data
@TableName("meta_llm_provider_secret")
public class MetaLlmProviderSecret {

    @TableId(type = IdType.AUTO)
    private Integer id;
    private Integer providerId;
    private String secretType;
    private String secretCiphertext;
    private String secretMask;
    private String encryptionKeyVersion;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
}
