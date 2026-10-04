package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.SysUser;
import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.domain.meta.mapper.SysUserMapper;
import com.text2report.model.dto.AuditDailyTrendStat;
import com.text2report.model.dto.AuditLogDetailVO;
import com.text2report.model.dto.AuditLogRecord;
import com.text2report.model.dto.AuditLogVO;
import com.text2report.model.dto.AuditStatsAggregate;
import com.text2report.model.dto.AuditStatsVO;
import com.text2report.model.dto.AuditTopUserStat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin audit log query service.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.70");

    private final AuditQueryLogMapper auditQueryLogMapper;
    private final SysUserMapper sysUserMapper;
    private final UserService userService;

    public PageResult<AuditLogVO> getAuditLogs(int page,
                                               int pageSize,
                                               Integer userId,
                                               Integer orgId,
                                               String status,
                                               LocalDateTime startDate,
                                               LocalDateTime endDate,
                                               BigDecimal minConfidence,
                                               BigDecimal maxConfidence) {
        return getAuditLogs(page, pageSize, userId, orgId, status, startDate, endDate,
                minConfidence, maxConfidence, null);
    }

    public PageResult<AuditLogVO> getAuditLogs(int page,
                                               int pageSize,
                                               Integer userId,
                                               Integer orgId,
                                               String status,
                                               LocalDateTime startDate,
                                               LocalDateTime endDate,
                                               BigDecimal minConfidence,
                                               BigDecimal maxConfidence,
                                               String operatorUsername) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        int offset = (safePage - 1) * safePageSize;
        AuditScope auditScope = resolveAuditScope(operatorUsername, orgId);

        List<AuditLogVO> list = auditQueryLogMapper.selectAuditLogs(
                        userId, orgId, auditScope.allowedOrgIds(), status, startDate, endDate,
                        minConfidence, maxConfidence, safePageSize, offset
                ).stream()
                .map(this::toAuditLogVO)
                .toList();
        long total = auditQueryLogMapper.countAuditLogs(userId, orgId, auditScope.allowedOrgIds(),
                status, startDate, endDate, minConfidence, maxConfidence);
        return PageResult.of(list, total, safePage, safePageSize);
    }

    public AuditLogDetailVO getAuditLogDetail(String requestId) {
        return getAuditLogDetail(requestId, null);
    }

    public AuditLogDetailVO getAuditLogDetail(String requestId, String operatorUsername) {
        AuditScope auditScope = resolveAuditScope(operatorUsername, null);
        AuditLogRecord record = auditQueryLogMapper.selectAuditLogDetail(requestId, auditScope.allowedOrgIds());
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "审计日志不存在");
        }

        String normalizedStatus = normalizeStatus(record);
        List<String> warnings = "low_confidence".equals(normalizedStatus)
                ? List.of("LLM 置信度较低，结果仅供参考")
                : List.of();

        UserProfile userProfile = resolveUserProfile(record.getUserId());
        return AuditLogDetailVO.builder()
                .requestId(record.getRequestId())
                .userId(record.getUserId() != null ? String.valueOf(record.getUserId()) : null)
                .username(userProfile.username())
                .displayName(userProfile.displayName())
                .orgId(record.getOrgId() != null ? String.valueOf(record.getOrgId()) : null)
                .orgName(record.getOrgName())
                .question(record.getQuestion())
                .sqlSnippet(buildSqlSnippet(record.getGeneratedSql()))
                .generatedSql(record.getGeneratedSql())
                .status(normalizedStatus)
                .confidence(defaultConfidence(record.getConfidence()))
                .promptTokens(0)
                .completionTokens(0)
                .latencyMs(defaultLong(record.getExecutionMs()))
                .executionMs(defaultLong(record.getExecutionMs()))
                .rowCount(defaultInteger(record.getRowCount()))
                .createdAt(record.getCreatedAt())
                .fullSql(record.getGeneratedSql())
                .promptSummary(record.getPromptContext() != null ? record.getPromptContext() : record.getQuestion())
                .warnings(warnings)
                .retryCount(defaultInteger(record.getRetryCount()))
                .errorMessage(record.getErrorMessage())
                .cacheHit(Boolean.TRUE.equals(record.getCacheHit()))
                .feedbackRating(record.getFeedbackRating())
                .build();
    }

    public AuditStatsVO getAuditStats(int days) {
        return getAuditStats(days, null);
    }

    public AuditStatsVO getAuditStats(int days, String operatorUsername) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(Math.max(days, 1));
        AuditScope auditScope = resolveAuditScope(operatorUsername, null);
        AuditStatsAggregate aggregate = auditQueryLogMapper.selectAuditStats(startDate, auditScope.allowedOrgIds());

        long totalQueries = defaultLong(aggregate != null ? aggregate.getTotalQueries() : null);
        long successCount = defaultLong(aggregate != null ? aggregate.getSuccessCount() : null);
        long failedCount = defaultLong(aggregate != null ? aggregate.getFailedCount() : null);
        long avgExecutionMs = defaultLong(aggregate != null ? aggregate.getAvgExecutionMs() : null);
        BigDecimal avgConfidence = aggregate != null && aggregate.getAvgConfidence() != null
                ? aggregate.getAvgConfidence().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal cacheHitRate = totalQueries == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(defaultLong(aggregate.getCacheHitCount()))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalQueries), 2, RoundingMode.HALF_UP);
        BigDecimal successRate = totalQueries == 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(successCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalQueries), 2, RoundingMode.HALF_UP);

        List<AuditStatsVO.TopUser> topUsers = auditQueryLogMapper.selectTopUsers(startDate, auditScope.allowedOrgIds(), 5).stream()
                .map(this::toTopUser)
                .toList();
        List<AuditStatsVO.DailyTrend> dailyTrend = auditQueryLogMapper.selectDailyTrend(startDate, auditScope.allowedOrgIds()).stream()
                .map(stat -> AuditStatsVO.DailyTrend.builder()
                        .date(stat.getDate())
                        .count(defaultLong(stat.getCount()))
                        .avgMs(defaultLong(stat.getAvgMs()))
                        .build())
                .toList();

        return AuditStatsVO.builder()
                .totalQueries(totalQueries)
                .successCount(successCount)
                .failedCount(failedCount)
                .avgExecutionMs(avgExecutionMs)
                .avgLatencyMs(avgExecutionMs)
                .avgConfidence(avgConfidence)
                .cacheHitRate(cacheHitRate)
                .successRate(successRate)
                .topUsers(topUsers)
                .dailyTrend(dailyTrend)
                .build();
    }

    private AuditScope resolveAuditScope(String operatorUsername, Integer requestedOrgId) {
        if (operatorUsername == null || operatorUsername.isBlank()) {
            return AuditScope.unrestricted();
        }
        SysUser operator = sysUserMapper.selectByUsername(operatorUsername);
        if (operator == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if ("ADMIN".equalsIgnoreCase(operator.getRole())) {
            return AuditScope.unrestricted();
        }
        if (!"MANAGER".equalsIgnoreCase(operator.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看审计日志");
        }

        List<Integer> allowedOrgIds = userService.resolveAccessibleOrgIds(operator.getId());
        if (allowedOrgIds == null || allowedOrgIds.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号缺少可查看的组织范围");
        }
        if (requestedOrgId != null && !allowedOrgIds.contains(requestedOrgId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该组织的审计日志");
        }
        return new AuditScope(allowedOrgIds);
    }

    private AuditLogVO toAuditLogVO(AuditLogRecord record) {
        UserProfile userProfile = resolveUserProfile(record.getUserId());
        String normalizedStatus = normalizeStatus(record);
        return AuditLogVO.builder()
                .requestId(record.getRequestId())
                .userId(record.getUserId() != null ? String.valueOf(record.getUserId()) : null)
                .username(userProfile.username())
                .displayName(userProfile.displayName())
                .orgId(record.getOrgId() != null ? String.valueOf(record.getOrgId()) : null)
                .orgName(record.getOrgName())
                .question(record.getQuestion())
                .sqlSnippet(buildSqlSnippet(record.getGeneratedSql()))
                .generatedSql(record.getGeneratedSql())
                .status(normalizedStatus)
                .confidence(defaultConfidence(record.getConfidence()))
                .promptTokens(0)
                .completionTokens(0)
                .latencyMs(defaultLong(record.getExecutionMs()))
                .executionMs(defaultLong(record.getExecutionMs()))
                .rowCount(defaultInteger(record.getRowCount()))
                .retryCount(defaultInteger(record.getRetryCount()))
                .cacheHit(Boolean.TRUE.equals(record.getCacheHit()))
                .feedbackRating(record.getFeedbackRating())
                .createdAt(record.getCreatedAt())
                .build();
    }

    private AuditStatsVO.TopUser toTopUser(AuditTopUserStat stat) {
        UserProfile userProfile = resolveUserProfile(stat.getUserId());
        return AuditStatsVO.TopUser.builder()
                .userId(stat.getUserId() != null ? String.valueOf(stat.getUserId()) : null)
                .username(userProfile.username())
                .displayName(userProfile.displayName())
                .count(defaultLong(stat.getCount()))
                .build();
    }

    private String normalizeStatus(AuditLogRecord record) {
        if ("success".equalsIgnoreCase(record.getStatus())
                && record.getConfidence() != null
                && record.getConfidence().compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            return "low_confidence";
        }
        if ("blocked".equalsIgnoreCase(record.getStatus()) || "error".equalsIgnoreCase(record.getStatus())) {
            return "failed";
        }
        return record.getStatus();
    }

    private String buildSqlSnippet(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String singleLine = sql.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= 80) {
            return singleLine;
        }
        return singleLine.substring(0, 80) + "...";
    }

    private UserProfile resolveUserProfile(Integer userId) {
        if (userId == null) {
            return new UserProfile("unknown", "未知用户");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user != null) {
            return new UserProfile(user.getUsername(),
                    user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        }
        return new UserProfile("user-" + userId, "用户" + userId);
    }

    private BigDecimal defaultConfidence(BigDecimal confidence) {
        return confidence != null ? confidence : BigDecimal.ZERO;
    }

    private long defaultLong(Long value) {
        return value != null ? value : 0L;
    }

    private int defaultInteger(Integer value) {
        return value != null ? value : 0;
    }

    private record UserProfile(String username, String displayName) {
    }

    private record AuditScope(List<Integer> allowedOrgIds) {
        private static AuditScope unrestricted() {
            return new AuditScope(null);
        }
    }
}
