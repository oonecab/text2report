package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.domain.meta.entity.SysUser;
import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.domain.meta.mapper.SysUserMapper;
import com.text2report.model.dto.AuditLogRecord;
import com.text2report.model.dto.AuditStatsAggregate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock
    private AuditQueryLogMapper auditQueryLogMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private UserService userService;

    @InjectMocks
    private AuditQueryService auditQueryService;

    @Test
    @DisplayName("MANAGER 查询审计列表时强制带入可访问组织范围")
    void managerListLogsUsesAccessibleOrgScope() {
        SysUser manager = user(10, "wanger", "MANAGER");
        when(sysUserMapper.selectByUsername("wanger")).thenReturn(manager);
        when(userService.resolveAccessibleOrgIds(10)).thenReturn(List.of(2, 5));
        when(auditQueryLogMapper.selectAuditLogs(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of());

        auditQueryService.getAuditLogs(1, 10, null, null, null,
                null, null, null, null, "wanger");

        ArgumentCaptor<List<Integer>> orgScopeCaptor = ArgumentCaptor.forClass(List.class);
        verify(auditQueryLogMapper).selectAuditLogs(
                eq(null), eq(null), orgScopeCaptor.capture(), eq(null),
                eq(null), eq(null), eq(null), eq(null), eq(10), eq(0)
        );
        verify(auditQueryLogMapper).countAuditLogs(
                eq(null), eq(null), orgScopeCaptor.capture(), eq(null),
                eq(null), eq(null), eq(null), eq(null)
        );
        assertEquals(List.of(2, 5), orgScopeCaptor.getAllValues().get(0));
        assertEquals(List.of(2, 5), orgScopeCaptor.getAllValues().get(1));
    }

    @Test
    @DisplayName("MANAGER 指定越权组织筛选时拒绝访问")
    void managerListLogsRejectsOutOfScopeOrgFilter() {
        SysUser manager = user(10, "wanger", "MANAGER");
        when(sysUserMapper.selectByUsername("wanger")).thenReturn(manager);
        when(userService.resolveAccessibleOrgIds(10)).thenReturn(List.of(2, 5));

        assertThrows(BusinessException.class, () -> auditQueryService.getAuditLogs(1, 10, null, 9, null,
                null, null, null, null, "wanger"));
    }

    @Test
    @DisplayName("MANAGER 查看审计详情时强制带入可访问组织范围")
    void managerDetailUsesAccessibleOrgScope() {
        SysUser manager = user(10, "wanger", "MANAGER");
        AuditLogRecord record = new AuditLogRecord();
        record.setRequestId("req-1");
        record.setOrgId(5);
        record.setStatus("success");
        record.setConfidence(new BigDecimal("0.90"));
        record.setCreatedAt(LocalDateTime.of(2026, 4, 21, 10, 0));
        when(sysUserMapper.selectByUsername("wanger")).thenReturn(manager);
        when(userService.resolveAccessibleOrgIds(10)).thenReturn(List.of(2, 5));
        when(auditQueryLogMapper.selectAuditLogDetail("req-1", List.of(2, 5))).thenReturn(record);

        auditQueryService.getAuditLogDetail("req-1", "wanger");

        verify(auditQueryLogMapper).selectAuditLogDetail("req-1", List.of(2, 5));
    }

    @Test
    @DisplayName("MANAGER 查询审计统计时强制带入可访问组织范围")
    void managerStatsUsesAccessibleOrgScope() {
        SysUser manager = user(10, "wanger", "MANAGER");
        AuditStatsAggregate aggregate = new AuditStatsAggregate();
        aggregate.setTotalQueries(0L);
        when(sysUserMapper.selectByUsername("wanger")).thenReturn(manager);
        when(userService.resolveAccessibleOrgIds(10)).thenReturn(List.of(2, 5));
        when(auditQueryLogMapper.selectAuditStats(any(), eq(List.of(2, 5)))).thenReturn(aggregate);
        when(auditQueryLogMapper.selectTopUsers(any(), eq(List.of(2, 5)), eq(5))).thenReturn(List.of());
        when(auditQueryLogMapper.selectDailyTrend(any(), eq(List.of(2, 5)))).thenReturn(List.of());

        auditQueryService.getAuditStats(30, "wanger");

        verify(auditQueryLogMapper).selectAuditStats(any(), eq(List.of(2, 5)));
        verify(auditQueryLogMapper).selectTopUsers(any(), eq(List.of(2, 5)), eq(5));
        verify(auditQueryLogMapper).selectDailyTrend(any(), eq(List.of(2, 5)));
    }

    @Test
    @DisplayName("ADMIN 查询审计时不追加组织范围")
    void adminListLogsIsUnrestricted() {
        SysUser admin = user(1, "admin", "ADMIN");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);
        when(auditQueryLogMapper.selectAuditLogs(any(), any(), any(), any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of());

        auditQueryService.getAuditLogs(1, 10, null, null, null,
                null, null, null, null, "admin");

        verify(auditQueryLogMapper).selectAuditLogs(
                eq(null), eq(null), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(null), eq(10), eq(0)
        );
    }

    private SysUser user(Integer id, String username, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }
}
