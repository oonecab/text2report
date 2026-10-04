package com.text2report.service;

import com.text2report.model.dto.MetadataContext;
import com.text2report.model.dto.QueryContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Scores answer confidence from metadata coverage and repair history.
 */
@Service
public class QueryConfidenceService {

    public BigDecimal resolveConfidence(QueryContext queryContext) {
        if (queryContext.getConfidence() != null) {
            return queryContext.getConfidence();
        }

        double score = 0.55d;
        MetadataContext metadataContext = queryContext.getMetadataContext();
        if (metadataContext != null) {
            if (metadataContext.getExamples() != null && !metadataContext.getExamples().isEmpty()) {
                score += 0.15d;
            }
            if (metadataContext.getGlossary() != null && !metadataContext.getGlossary().isEmpty()) {
                score += 0.10d;
            }
            if (metadataContext.getTables() != null && !metadataContext.getTables().isEmpty()) {
                score += 0.10d;
            }
            if (metadataContext.getJoinEdges() != null && !metadataContext.getJoinEdges().isEmpty()) {
                score += 0.05d;
            }
        }

        score -= Math.min(queryContext.getRetryCount(), queryContext.getMaxRetries()) * 0.15d;
        score = Math.max(0.05d, Math.min(0.95d, score));

        BigDecimal confidence = BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
        queryContext.setConfidence(confidence);
        return confidence;
    }
}
