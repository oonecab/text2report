package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.result.ApiResult;
import com.text2report.domain.vector.entity.MetaEmbedding;
import com.text2report.model.dto.EmbeddingSyncResult;
import com.text2report.service.EmbeddingService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/embeddings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class EmbeddingController {

    private final EmbeddingService embeddingService;

    @PostMapping("/sync")
    public ApiResult<EmbeddingSyncResult> syncAll() {
        return ApiResult.success(embeddingService.syncAll()).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Long>> stats() {
        return ApiResult.success(embeddingService.getStats()).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/search")
    public ApiResult<List<MetaEmbedding>> search(@RequestBody SearchRequest request) {
        return ApiResult.success(
                embeddingService.searchSimilar(request.getQuery(), request.getSourceType(), request.getTopK())
        ).withRequestId(TraceContext.getRequestId());
    }

    @Data
    public static class SearchRequest {
        @NotBlank
        private String query;
        @NotBlank
        private String sourceType;
        private Integer topK = 5;
    }
}
