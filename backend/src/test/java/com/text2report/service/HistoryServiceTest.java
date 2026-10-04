package com.text2report.service;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.result.PageResult;
import com.text2report.model.dto.HistoryItem;
import com.text2report.model.dto.QueryResultDTO;
import com.text2report.model.dto.ResultProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock
    private com.text2report.domain.meta.mapper.AuditQueryLogMapper auditQueryLogMapper;
    @Mock
    private QueryCacheService queryCacheService;
    @Mock
    private UserService userService;
    @Mock
    private OrgService orgService;

    private HistoryService historyService;

    @BeforeEach
    void setUp() {
        historyService = new HistoryService(auditQueryLogMapper, queryCacheService, userService, orgService);
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-1");
        traceInfo.setUserId(7);
        traceInfo.setRole("USER");
        TraceContext.set(traceInfo);
        lenient().when(queryCacheService.getCachedResult(anyString(), any())).thenReturn(Optional.of(sampleCachedResult()));
        lenient().when(userService.resolveAccessibleOrgIds(7)).thenReturn(List.of(2, 3, 4));
        lenient().when(orgService.resolveSubtreeIds(2)).thenReturn(List.of(2, 3));
        lenient().when(orgService.resolveSubtreeIds(99)).thenReturn(List.of(99));
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("Should return paged history data")
    void shouldReturnPagedHistoryData() {
        when(auditQueryLogMapper.selectHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(sampleHistoryItem(), sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(2L);

        PageResult<HistoryItem> page = historyService.getHistory(1, 20, null, null, null);

        assertEquals(2, page.getList().size());
        assertEquals(2L, page.getTotal());
    }

    @Test
    @DisplayName("Should pass keyword filter to mapper")
    void shouldPassKeywordFilterToMapper() {
        when(auditQueryLogMapper.selectHistory(eq(7), anyList(), any(), eq("毛利"), any(), any(), any(), any(), eq(10), eq(0)))
                .thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(eq(7), anyList(), any(), eq("毛利"), any(), any(), any(), any())).thenReturn(1L);

        historyService.getHistory(1, 10, "毛利", null, null);

        verify(auditQueryLogMapper).selectHistory(eq(7), eq(List.of(2, 3, 4)), isNull(), eq("毛利"), any(), any(), any(), any(), eq(10), eq(0));
    }

    @Test
    @DisplayName("Should convert date range and pass to mapper")
    void shouldConvertDateRangeAndPassToMapper() {
        when(auditQueryLogMapper.selectHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any(), eq(10), eq(0)))
                .thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        historyService.getHistory(1, 10, null, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 10));

        verify(auditQueryLogMapper).selectHistory(eq(7), eq(List.of(2, 3, 4)), isNull(), isNull(),
                eq(LocalDateTime.of(2026, 4, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 4, 11, 0, 0)),
                isNull(), isNull(), eq(10), eq(0));
    }

    @Test
    @DisplayName("Should scope normal user to own history")
    void shouldScopeNormalUserToOwnHistory() {
        when(auditQueryLogMapper.selectHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        historyService.getHistory(1, 20, null, null, null);

        verify(auditQueryLogMapper).selectHistory(eq(7), eq(List.of(2, 3, 4)), any(), any(), any(), any(), any(), any(), eq(20), eq(0));
    }

    @Test
    @DisplayName("Should allow admin to view all history")
    void shouldAllowAdminToViewAllHistory() {
        TraceContext.get().setRole("ADMIN");
        when(auditQueryLogMapper.selectHistory(isNull(), isNull(), any(), any(), any(), any(), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(isNull(), isNull(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        historyService.getHistory(1, 20, null, null, null);

        verify(auditQueryLogMapper).selectHistory(isNull(), isNull(), any(), any(), any(), any(), any(), any(), eq(20), eq(0));
    }

    @Test
    @DisplayName("Should hide SQL for non admin users")
    void shouldHideSqlForNonAdminUsers() {
        when(auditQueryLogMapper.selectHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        PageResult<HistoryItem> page = historyService.getHistory(1, 20, null, null, null);

        assertNull(page.getList().get(0).getSql());
        assertNull(page.getList().get(0).getResponse().getSql());
    }

    @Test
    @DisplayName("Should propagate favorite flag to response payload")
    void shouldPropagateFavoriteFlagToResponsePayload() {
        HistoryItem favoriteItem = sampleHistoryItem();
        favoriteItem.setIsFavorite(true);
        when(auditQueryLogMapper.selectHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any(), eq(20), eq(0)))
                .thenReturn(List.of(favoriteItem));
        when(auditQueryLogMapper.countHistory(eq(7), anyList(), any(), any(), any(), any(), any(), any())).thenReturn(1L);

        PageResult<HistoryItem> page = historyService.getHistory(1, 20, null, null, null);

        assertTrue(Boolean.TRUE.equals(page.getList().get(0).getResponse().getIsFavorited()));
    }

    @Test
    @DisplayName("Should filter manager history by accessible selected org subtree and confidence")
    void shouldFilterManagerHistoryByAccessibleSelectedOrgSubtreeAndConfidence() {
        TraceContext.get().setRole("MANAGER");
        when(auditQueryLogMapper.selectHistory(isNull(), eq(List.of(2, 3)), any(), any(), any(), any(),
                eq(new BigDecimal("0.70")), eq(new BigDecimal("0.90")), eq(20), eq(0)))
                .thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(isNull(), eq(List.of(2, 3)), any(), any(), any(), any(),
                eq(new BigDecimal("0.70")), eq(new BigDecimal("0.90")))).thenReturn(1L);

        historyService.getHistory(1, 20, null, 2, null, null, null,
                new BigDecimal("0.70"), new BigDecimal("0.90"));

        verify(auditQueryLogMapper).selectHistory(isNull(), eq(List.of(2, 3)), any(), any(), any(), any(),
                eq(new BigDecimal("0.70")), eq(new BigDecimal("0.90")), eq(20), eq(0));
    }

    @Test
    @DisplayName("Should pass username filter for manager history")
    void shouldPassUsernameFilterForManagerHistory() {
        TraceContext.get().setRole("MANAGER");
        when(auditQueryLogMapper.selectHistory(isNull(), eq(List.of(2, 3, 4)), eq("demo"), any(), any(), any(),
                any(), any(), eq(20), eq(0))).thenReturn(List.of(sampleHistoryItem()));
        when(auditQueryLogMapper.countHistory(isNull(), eq(List.of(2, 3, 4)), eq("demo"), any(), any(), any(),
                any(), any())).thenReturn(1L);

        historyService.getHistory(1, 20, null, null, "demo", null, null, null, null);

        verify(auditQueryLogMapper).selectHistory(isNull(), eq(List.of(2, 3, 4)), eq("demo"), any(), any(), any(),
                any(), any(), eq(20), eq(0));
    }

    @Test
    @DisplayName("Should reject manager history filter outside accessible orgs")
    void shouldRejectManagerHistoryFilterOutsideAccessibleOrgs() {
        TraceContext.get().setRole("MANAGER");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> historyService.getHistory(1, 20, null, 99, null, null, null, null, null));

        assertEquals("无权查看该组织的查询历史", exception.getMessage());
    }

    private HistoryItem sampleHistoryItem() {
        return HistoryItem.builder()
                .requestId("req-history-1")
                .question("子公司A毛利")
                .sql("SELECT org_name FROM dim_org")
                .summary("summary")
                .confidence(new BigDecimal("0.90"))
                .rowCount(1)
                .executionMs(12L)
                .status("success")
                .recommendedChart("bar")
                .createdAt(LocalDateTime.now())
                .orgId(2)
                .orgName("子公司A")
                .isFavorite(false)
                .retryCount(0)
                .feedback("good")
                .build();
    }

    private QueryResultDTO sampleCachedResult() {
        return QueryResultDTO.builder()
                .requestId("req-cache")
                .sql("SELECT org_name FROM dim_org")
                .isFavorited(false)
                .table(QueryResultDTO.TableData.builder().columns(List.of()).rows(List.of()).build())
                .resultProfile(ResultProfile.builder().recommendedChart("bar").rowCount(1).columnCount(0).columnNames(List.of()).hasData(true).build())
                .summary("summary")
                .warnings(List.of())
                .audit(QueryResultDTO.AuditInfo.builder().confidence(new BigDecimal("0.90")).retryCount(0).executionMs(12L).build())
                .build();
    }
}
