package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.result.ApiResult;
import com.text2report.common.result.PageResult;
import com.text2report.model.dto.FavoriteItem;
import com.text2report.model.dto.FeedbackRequest;
import com.text2report.model.dto.HistoryItem;
import com.text2report.model.dto.QueryRequest;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.service.FavoriteService;
import com.text2report.service.HistoryService;
import com.text2report.service.OrchestratorService;
import com.text2report.service.FeedbackService;
import org.springframework.format.annotation.DateTimeFormat;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

/**
 * Query controller.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {

    private final OrchestratorService orchestratorService;
    private final HistoryService historyService;
    private final FeedbackService feedbackService;
    private final FavoriteService favoriteService;

    @PostMapping
    public ApiResult<QueryResultDTO> submitQuery(@Valid @RequestBody QueryRequest request) {
        log.info("Received query request, orgId={}", request.getOrgId());
        return ApiResult.success(maskSqlIfNeeded(orchestratorService.query(request.getQuestion(), request.getOrgId())))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/history")
    public ApiResult<PageResult<HistoryItem>> getHistory(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer current,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer orgId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) BigDecimal minConfidence,
            @RequestParam(required = false) BigDecimal maxConfidence
    ) {
        int actualPage = page != null ? page : (current != null ? current : 1);
        return ApiResult.success(historyService.getHistory(actualPage, pageSize, keyword, orgId, username, startDate, endDate,
                        minConfidence, maxConfidence))
                .withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/{requestId}/feedback")
    public ApiResult<Void> submitFeedback(@PathVariable String requestId, @Valid @RequestBody FeedbackRequest request) {
        feedbackService.submitFeedback(requestId, request);
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/favorites")
    public ApiResult<List<FavoriteItem>> getFavorites() {
        return ApiResult.success(favoriteService.getFavorites()).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/{requestId}/favorite")
    public ApiResult<Void> addFavorite(@PathVariable String requestId) {
        favoriteService.addFavorite(requestId);
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @DeleteMapping("/{requestId}/favorite")
    public ApiResult<Void> removeFavorite(@PathVariable String requestId) {
        favoriteService.removeFavorite(requestId);
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    private QueryResultDTO maskSqlIfNeeded(QueryResultDTO result) {
        if (result == null || "ADMIN".equalsIgnoreCase(TraceContext.getRole())) {
            return result;
        }

        return QueryResultDTO.builder()
                .requestId(result.getRequestId())
                .sql(null)
                .isFavorited(result.getIsFavorited())
                .table(result.getTable())
                .resultProfile(result.getResultProfile())
                .summary(result.getSummary())
                .warnings(result.getWarnings())
                .audit(result.getAudit())
                .build();
    }
}
