package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.config.SqlGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlGuardServiceTest {

    private SqlGuardService sqlGuardService;

    @BeforeEach
    void setUp() {
        SqlGuardProperties props = new SqlGuardProperties();
        props.setBlockSelectStar(true);
        props.setDefaultLimit(1000);
        props.setQueryTimeoutSeconds(30);
        sqlGuardService = new SqlGuardService(props);
    }

    @Test
    @DisplayName("合法业务 SQL 应允许并自动注入 LIMIT")
    void shouldAllowBusinessQueries() {
        List<String> sqls = List.of(
                """
                SELECT product_id, SUM(gross_profit)
                FROM fact_gross_profit_detail
                WHERE org_id IN (1,2)
                GROUP BY product_id
                """,
                """
                SELECT d.product_name, SUM(f.gross_profit)
                FROM fact_gross_profit_detail f
                JOIN dim_product d ON f.product_id = d.product_id
                WHERE f.org_id IN (1,2)
                GROUP BY d.product_name
                """,
                """
                WITH monthly AS (
                  SELECT period_month, SUM(gross_profit) AS gp
                  FROM fact_gross_profit_detail
                  WHERE org_id IN (1,2)
                  GROUP BY period_month
                )
                SELECT period_month, gp FROM monthly
                """
        );

        for (String sql : sqls) {
            String validated = sqlGuardService.validateAndInject(sql);
            assertNotNull(validated);
            assertTrue(validated.contains("LIMIT 1000") || validated.contains("LIMIT 1000;"));
        }
    }

    @Test
    @DisplayName("危险 PostgreSQL 函数和系统对象应被拦截")
    void shouldBlockPostgresRiskPatterns() {
        List<String> blockedSql = List.of(
                "SELECT pg_read_file('postgresql.conf')",
                "SELECT pg_catalog.pg_read_file('postgresql.conf')",
                "SELECT \"pg_catalog\".\"pg_read_file\"('postgresql.conf')",
                "SELECT pg_read_binary_file('server.key')",
                "SELECT pg_stat_file('postgresql.conf')",
                "SELECT pg_ls_dir('.')",
                "SELECT pg_sleep(10)",
                "SELECT pg_sleep_for(interval '10 seconds')",
                "SELECT current_setting('data_directory')",
                "SELECT set_config('statement_timeout', '0', false)",
                "SELECT pg_cancel_backend(pg_backend_pid())",
                "SELECT pg_terminate_backend(pg_backend_pid())",
                "SELECT pg_advisory_lock(1)",
                "SELECT lo_export(123, '/tmp/x')",
                "SELECT dblink_connect('host=127.0.0.1 dbname=postgres')",
                "SELECT org_id FROM pg_class",
                "SELECT org_id FROM pg_catalog.pg_class",
                "SELECT table_name FROM information_schema.tables",
                "SELECT pid FROM pg_stat_activity",
                "WITH x AS (SELECT pg_read_file('postgresql.conf')) SELECT v FROM x",
                "SELECT product_id FROM fact_gross_profit_detail ORDER BY pg_sleep(5)",
                "SELECT product_id FROM fact_gross_profit_detail GROUP BY product_id HAVING current_setting('server_version') IS NOT NULL",
                "SELECT product_id FROM fact_gross_profit_detail UNION SELECT oid FROM pg_class"
        );

        for (String sql : blockedSql) {
            BusinessException exception = assertThrows(BusinessException.class, () -> sqlGuardService.validateAndInject(sql));
            assertEquals(ErrorCode.SQL_GUARD_BLOCKED, exception.getErrorCode(), sql);
        }
    }

    @Test
    @DisplayName("无效 SQL 应返回 SQL_PARSE_ERROR")
    void shouldReturnSqlParseErrorForInvalidSql() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> sqlGuardService.validateAndInject("THIS IS COMPLETELY INVALID SQL"));
        assertEquals(ErrorCode.SQL_PARSE_ERROR, exception.getErrorCode());
    }

    @Test
    @DisplayName("空 SQL 应返回 VALIDATION_ERROR")
    void shouldRejectEmptySql() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> sqlGuardService.validateAndInject(" "));
        assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
    }

    @Test
    @DisplayName("应继续拦截 SELECT *")
    void shouldBlockSelectStar() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> sqlGuardService.validateAndInject("SELECT * FROM fact_gross_profit_detail"));
        assertEquals(ErrorCode.SQL_GUARD_BLOCKED, exception.getErrorCode());
    }

    @Test
    @DisplayName("组织范围校验通过")
    void shouldAllowAuthorizedOrgScope() {
        String sql = """
                SELECT product_id, SUM(gross_profit)
                FROM fact_gross_profit_detail
                WHERE org_id IN (1,2)
                GROUP BY product_id
                LIMIT 1000
                """;

        assertEquals(sql, sqlGuardService.validateOrgScope(sql, List.of(1, 2)));
    }

    @Test
    @DisplayName("缺少事实表组织范围时应自动注入允许的 org_id 列表")
    void shouldInjectMissingFactOrgScope() {
        String sql = """
                SELECT f.period_month, SUM(f.gross_profit) AS total_gross_profit
                FROM fact_gross_profit_detail f
                JOIN dim_org dept ON f.org_id = dept.id
                JOIN dim_org parent ON dept.parent_id = parent.id
                WHERE parent.org_name = '子公司A'
                GROUP BY f.period_month
                ORDER BY f.period_month
                LIMIT 1000
                """;

        String scoped = sqlGuardService.enforceOrgScope(sql, List.of(11, 12, 13, 14));

        assertTrue(scoped.contains("f.org_id IN (11, 12, 13, 14)") || scoped.contains("f.org_id IN (11,12,13,14)"));
        assertEquals(scoped, sqlGuardService.validateOrgScope(scoped, List.of(11, 12, 13, 14)));
    }

    @Test
    @DisplayName("已有授权组织范围时不应重复注入")
    void shouldNotDuplicateExistingOrgScope() {
        String sql = "SELECT product_id FROM fact_gross_profit_detail WHERE org_id IN (1, 2) LIMIT 1000";

        String scoped = sqlGuardService.enforceOrgScope(sql, List.of(1, 2));

        assertEquals(sql, scoped);
    }

    @Test
    @DisplayName("显式未授权组织范围即使补充授权过滤也应继续拦截")
    void shouldStillBlockExplicitUnauthorizedOrgScopeAfterInjection() {
        String sql = "SELECT product_id FROM fact_gross_profit_detail WHERE org_id IN (9) LIMIT 1000";

        String scoped = sqlGuardService.enforceOrgScope(sql, List.of(1, 2));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> sqlGuardService.validateOrgScope(scoped, List.of(1, 2)));

        assertEquals(ErrorCode.SQL_ORG_SCOPE_VIOLATION, exception.getErrorCode());
        assertEquals("SQL 包含未授权组织范围", exception.getMessage());
    }

    @Test
    @DisplayName("组织维表查询应使用 dim_org.id 注入组织范围")
    void shouldInjectDimOrgIdScope() {
        String sql = "SELECT o.org_name FROM dim_org o WHERE o.is_active = true LIMIT 1000";

        String scoped = sqlGuardService.enforceOrgScope(sql, List.of(1, 2));

        assertTrue(scoped.contains("o.id IN (1, 2)") || scoped.contains("o.id IN (1,2)"));
        assertEquals(scoped, sqlGuardService.validateOrgScope(scoped, List.of(1, 2)));
    }

    @Test
    @DisplayName("缺少组织范围时返回 SQL_ORG_SCOPE_VIOLATION")
    void shouldBlockMissingOrgScope() {
        String sql = "SELECT product_id, gross_profit FROM fact_gross_profit_detail LIMIT 1000";
        BusinessException exception = assertThrows(BusinessException.class,
                () -> sqlGuardService.validateOrgScope(sql, List.of(1, 2)));
        assertEquals(ErrorCode.SQL_ORG_SCOPE_VIOLATION, exception.getErrorCode());
    }

    @Test
    @DisplayName("未授权组织范围时返回 SQL_ORG_SCOPE_VIOLATION")
    void shouldBlockUnauthorizedOrgScope() {
        String sql = "SELECT product_id, gross_profit FROM fact_gross_profit_detail WHERE org_id IN (1,9) LIMIT 1000";
        BusinessException exception = assertThrows(BusinessException.class,
                () -> sqlGuardService.validateOrgScope(sql, List.of(1, 2)));
        assertEquals(ErrorCode.SQL_ORG_SCOPE_VIOLATION, exception.getErrorCode());
    }
}
