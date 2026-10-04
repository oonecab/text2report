package com.text2report.domain.vector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Vector store entity for metadata embeddings.
 */
@Data
@TableName("meta_embedding")
public class MetaEmbedding {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String sourceType;
    private Integer sourceId;
    private String contentText;
    private float[] embedding;
    private String modelVersion;

    @TableField(exist = false)
    private Double similarity;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
