package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.result.ApiResult;
import com.text2report.common.result.PageResult;
import com.text2report.model.dto.AuditLogDetailVO;
import com.text2report.model.dto.AuditLogVO;
import com.text2report.model.dto.AuditStatsVO;
import com.text2report.service.AuditQueryService;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Admin audit management APIs.
 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService auditQueryService;

    @GetMapping("/logs")
    public ApiResult<PageResult<AuditLogVO>> getAuditLogs(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) int pageSize,
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) Integer orgId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minConfidence,
            @RequestParam(required = false) BigDecimal maxConfidence,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication
    ) {
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        return ApiResult.success(
                        auditQueryService.getAuditLogs(page, pageSize, userId, orgId, status,
                                startDateTime, endDateTime, minConfidence, maxConfidence, authentication.getName())
                )
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/stats")
    public ApiResult<AuditStatsVO> getAuditStats(@RequestParam(defaultValue = "30") @Min(1) int days,
                                                 Authentication authentication) {
        return ApiResult.success(auditQueryService.getAuditStats(days, authentication.getName()))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/logs/{requestId}")
    public ApiResult<AuditLogDetailVO> getAuditLogDetail(@PathVariable String requestId,
                                                         Authentication authentication) {
        return ApiResult.success(auditQueryService.getAuditLogDetail(requestId, authentication.getName()))
                .withRequestId(TraceContext.getRequestId());
    }
}
