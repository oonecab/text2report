package com.text2report.service;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.PageResult;
import com.text2report.model.dto.HistoryItem;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.model.dto.ResultProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Query history retrieval service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final com.text2report.domain.meta.mapper.AuditQueryLogMapper auditQueryLogMapper;
    private final QueryCacheService queryCacheService;
    private final UserService userService;
    private final OrgService orgService;

    public PageResult<HistoryItem> getHistory(int page,
                                              int pageSize,
                                              String keyword,
                                              Integer orgId,
                                              String username,
                                              LocalDate startDate,
                                              LocalDate endDate,
                                              BigDecimal minConfidence,
                                              BigDecimal maxConfidence) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        HistoryScope scope = resolveHistoryScope(orgId);
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;
        int offset = (safePage - 1) * safePageSize;

        List<HistoryItem> items = auditQueryLogMapper.selectHistory(
                scope.userId(),
                scope.orgIds(),
                username,
                keyword,
                startDateTime,
                endDateTime,
                minConfidence,
                maxConfidence,
                safePageSize,
                offset
        );

        items.forEach(this::enrichHistoryItem);
        long total = auditQueryLogMapper.countHistory(scope.userId(), scope.orgIds(), username, keyword, startDateTime, endDateTime,
                minConfidence, maxConfidence);
        return PageResult.of(items, total, safePage, safePageSize);
    }

    public PageResult<HistoryItem> getHistory(int page, int pageSize, String keyword, LocalDate startDate, LocalDate endDate) {
        return getHistory(page, pageSize, keyword, null, null, startDate, endDate, null, null);
    }

    private void enrichHistoryItem(HistoryItem item) {
        QueryResultDTO cached = queryCacheService.getCachedResult(item.getQuestion(), item.getOrgId()).orElse(null);
        QueryResultDTO response = buildResponse(item, cached);
        if (!isAdmin()) {
            item.setSql(null);
            response.setSql(null);
        } else {
            response.setSql(item.getSql());
        }
        item.setResponse(response);
    }

    private QueryResultDTO buildResponse(HistoryItem item, QueryResultDTO cached) {
        ResultProfile profile;
        QueryResultDTO.TableData tableData;
        if (cached != null) {
            profile = cached.getResultProfile();
            tableData = cached.getTable();
        } else {
            profile = ResultProfile.builder()
                    .recommendedChart(item.getRecommendedChart() != null ? item.getRecommendedChart() : "table")
                    .rowCount(item.getRowCount() != null ? item.getRowCount() : 0)
                    .columnCount(0)
                    .columnNames(List.of())
                    .hasData(item.getRowCount() != null && item.getRowCount() > 0)
                    .build();
            tableData = QueryResultDTO.TableData.builder().columns(List.of()).rows(List.of()).build();
        }

        if (profile != null && profile.getRecommendedChart() == null) {
            profile.setRecommendedChart("table");
        }

        return QueryResultDTO.builder()
                .requestId(item.getRequestId())
                .sql(item.getSql())
                .isFavorited(Boolean.TRUE.equals(item.getIsFavorite()))
                .table(tableData)
                .resultProfile(profile)
                .summary(item.getSummary())
                .warnings(cached != null ? cached.getWarnings() : List.of())
                .audit(QueryResultDTO.AuditInfo.builder()
                        .confidence(item.getConfidence())
                        .retryCount(item.getRetryCount() != null ? item.getRetryCount() : 0)
                        .executionMs(item.getExecutionMs() != null ? item.getExecutionMs() : 0L)
                        .build())
                .build();
    }

    private Integer requireUserId() {
        Integer userId = TraceContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或Token失效");
        }
        return userId;
    }

    private boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(TraceContext.getRole());
    }

    private boolean isManager() {
        return "MANAGER".equalsIgnoreCase(TraceContext.getRole());
    }

    private HistoryScope resolveHistoryScope(Integer requestedOrgId) {
        Integer currentUserId = requireUserId();
        if (isAdmin()) {
            return new HistoryScope(null, requestedOrgId != null ? orgService.resolveSubtreeIds(requestedOrgId) : null);
        }

        List<Integer> accessibleOrgIds = userService.resolveAccessibleOrgIds(currentUserId);
        if (accessibleOrgIds == null || accessibleOrgIds.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号缺少可查看的组织范围");
        }

        List<Integer> scopedOrgIds = accessibleOrgIds;
        if (requestedOrgId != null) {
            List<Integer> requestedSubtreeIds = orgService.resolveSubtreeIds(requestedOrgId);
            Set<Integer> intersection = new LinkedHashSet<>(accessibleOrgIds);
            intersection.retainAll(requestedSubtreeIds);
            if (intersection.isEmpty()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该组织的查询历史");
            }
            scopedOrgIds = intersection.stream().sorted().toList();
        }

        return new HistoryScope(isManager() ? null : currentUserId, scopedOrgIds);
    }

    private record HistoryScope(Integer userId, List<Integer> orgIds) {
    }
}
