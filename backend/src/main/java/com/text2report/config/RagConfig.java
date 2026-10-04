package com.text2report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for hybrid RAG retrieval.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagConfig {

    private double similarityThresholdExample = 0.65;
    private double similarityThresholdGlossary = 0.60;
    private double similarityThresholdTable = 0.55;
    private int topKExample = 5;
    private int topKGlossary = 5;
    private int topKTable = 3;
    private boolean fallbackToKeyword = true;
}
