package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.text2report.domain.meta.entity.AuditQueryLog;
import com.text2report.model.dto.AuditDailyTrendStat;
import com.text2report.model.dto.AuditLogRecord;
import com.text2report.model.dto.AuditStatsAggregate;
import com.text2report.model.dto.AuditTopUserStat;
import com.text2report.model.dto.FavoriteItem;
import com.text2report.model.dto.HistoryItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

/**
 * Audit query log mapper
 */
@Mapper
public interface AuditQueryLogMapper extends BaseMapper<AuditQueryLog> {

    /**
     * Upsert audit log - Fix: P0-2竞态消除
     */
    void upsert(@Param("requestId") String requestId,
                @Param("userId") Integer userId,
                @Param("orgId") Integer orgId,
                @Param("question") String question,
                @Param("promptContext") String promptContext,
                @Param("sql") String sql,
                @Param("summary") String summary,
                @Param("recommendedChart") String recommendedChart,
                @Param("status") String status,
                @Param("errorMessage") String errorMessage,
                @Param("executionTimeMs") Long executionTimeMs,
                @Param("rowsReturned") Integer rowsReturned,
                @Param("confidence") Double confidence,
                @Param("feedback") String feedback,
                @Param("feedbackRating") Integer feedbackRating,
                @Param("feedbackComment") String feedbackComment,
                @Param("feedbackAt") java.time.LocalDateTime feedbackAt,
                @Param("cacheHit") Boolean cacheHit,
                @Param("isFavorite") Boolean isFavorite,
                @Param("favoriteAt") java.time.LocalDateTime favoriteAt,
                @Param("retryCount") Integer retryCount,
                @Param("createdAt") java.time.LocalDateTime createdAt,
                @Param("updatedAt") java.time.LocalDateTime updatedAt);

    void updateLlmAudit(@Param("requestId") String requestId,
                        @Param("llmProviderId") Integer llmProviderId,
                        @Param("llmProviderKey") String llmProviderKey,
                        @Param("llmVendorType") String llmVendorType,
                        @Param("llmModelCode") String llmModelCode,
                        @Param("llmRoutingVersion") Integer llmRoutingVersion);

    List<HistoryItem> selectHistory(@Param("userId") Integer userId,
                                    @Param("orgIds") List<Integer> orgIds,
                                    @Param("username") String username,
                                    @Param("keyword") String keyword,
                                    @Param("startDate") LocalDateTime startDate,
                                    @Param("endDate") LocalDateTime endDate,
                                    @Param("minConfidence") BigDecimal minConfidence,
                                    @Param("maxConfidence") BigDecimal maxConfidence,
                                    @Param("pageSize") int pageSize,
                                    @Param("offset") int offset);

    long countHistory(@Param("userId") Integer userId,
                      @Param("orgIds") List<Integer> orgIds,
                      @Param("username") String username,
                      @Param("keyword") String keyword,
                      @Param("startDate") LocalDateTime startDate,
                      @Param("endDate") LocalDateTime endDate,
                      @Param("minConfidence") BigDecimal minConfidence,
                      @Param("maxConfidence") BigDecimal maxConfidence);

    List<FavoriteItem> selectFavorites(@Param("userId") Integer userId);

    List<AuditLogRecord> selectAuditLogs(@Param("userId") Integer userId,
                                         @Param("orgId") Integer orgId,
                                         @Param("allowedOrgIds") List<Integer> allowedOrgIds,
                                         @Param("status") String status,
                                         @Param("startDate") LocalDateTime startDate,
                                         @Param("endDate") LocalDateTime endDate,
                                         @Param("minConfidence") java.math.BigDecimal minConfidence,
                                         @Param("maxConfidence") java.math.BigDecimal maxConfidence,
                                         @Param("pageSize") int pageSize,
                                         @Param("offset") int offset);

    long countAuditLogs(@Param("userId") Integer userId,
                        @Param("orgId") Integer orgId,
                        @Param("allowedOrgIds") List<Integer> allowedOrgIds,
                        @Param("status") String status,
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate,
                        @Param("minConfidence") java.math.BigDecimal minConfidence,
                        @Param("maxConfidence") java.math.BigDecimal maxConfidence);

    AuditLogRecord selectAuditLogDetail(@Param("requestId") String requestId,
                                        @Param("allowedOrgIds") List<Integer> allowedOrgIds);

    AuditStatsAggregate selectAuditStats(@Param("startDate") LocalDateTime startDate,
                                         @Param("allowedOrgIds") List<Integer> allowedOrgIds);

    List<AuditTopUserStat> selectTopUsers(@Param("startDate") LocalDateTime startDate,
                                          @Param("allowedOrgIds") List<Integer> allowedOrgIds,
                                          @Param("limit") int limit);

    List<AuditDailyTrendStat> selectDailyTrend(@Param("startDate") LocalDateTime startDate,
                                               @Param("allowedOrgIds") List<Integer> allowedOrgIds);
}
