package com.text2report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM-related infrastructure properties.
 *
 * Chat model providers are configured through meta_llm_provider and
 * meta_llm_routing_policy, not through application.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {

    /**
     * Embedding model settings.
     */
    private String embeddingModel = "bge-m3";
    private int embeddingDimensions = 1024;
}
