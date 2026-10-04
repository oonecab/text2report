package com.text2report.service.llm;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.ollama.OllamaEmbeddingClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Embedding provider backed by Spring AI Ollama.
 */
@Component
@RequiredArgsConstructor
public class BgeEmbeddingProvider {

    private final OllamaEmbeddingClient ollamaEmbeddingClient;
    private final LLMConfig llmConfig;

    public float[] generateEmbedding(String text) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Embedding text cannot be empty");
        }
        List<Double> vector = ollamaEmbeddingClient.embed(text);
        if (vector == null) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "BGE-M3 返回空向量");
        }
        float[] result = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            result[i] = vector.get(i).floatValue();
        }
        if (result.length != llmConfig.getEmbeddingDimensions()) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "BGE-M3 维度异常: " + result.length);
        }
        return result;
    }
}
