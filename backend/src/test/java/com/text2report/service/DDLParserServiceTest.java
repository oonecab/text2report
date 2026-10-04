package com.text2report.service;

import com.text2report.domain.meta.mapper.MetaColumnMapper;
import com.text2report.domain.meta.mapper.MetaDdlUploadMapper;
import com.text2report.domain.meta.mapper.MetaTableMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.model.dto.DDLConfirmRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DDLParserService
 */
@ExtendWith(MockitoExtension.class)
class DDLParserServiceTest {

    @Mock
    private MetaTableMapper metaTableMapper;

    @Mock
    private MetaColumnMapper metaColumnMapper;

    @Mock
    private MetaDdlUploadMapper metaDdlUploadMapper;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private QueryCacheService queryCacheService;

    @InjectMocks
    private DDLParserService ddlParserService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        var field = DDLParserService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(ddlParserService, objectMapper);
    }

    @Nested
    @DisplayName("parseDDL - line comments")
    class LineCommentTests {

        @Test
        @DisplayName("Should parse DDL with line comments")
        void shouldParseLineComments() {
            String ddl = """
                CREATE TABLE test_table (
                    id INTEGER, -- 主键ID
                    name VARCHAR(100), -- 用户名称
                    created_at TIMESTAMP -- 创建时间
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertNotNull(result.getTables());
            assertEquals(1, result.getTables().size());
            assertEquals("test_table", result.getTables().get(0).getTableName());
        }

        @Test
        @DisplayName("Should detect needsReview for columns without comments")
        void shouldDetectNeedsReview() {
            String ddl = """
                CREATE TABLE test_table (
                    id INTEGER,
                    name VARCHAR(100) -- 有注释
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertEquals(1, result.getTables().size());
            assertEquals(2, result.getTables().get(0).getColumns().size());
        }
    }

    @Nested
    @DisplayName("parseDDL - COMMENT ON syntax")
    class CommentOnTests {

        @Test
        @DisplayName("Should parse COMMENT ON COLUMN syntax")
        void shouldParseCommentOnSyntax() {
            String ddl = """
                CREATE TABLE test_table (
                    id INTEGER,
                    name VARCHAR(100)
                );

                COMMENT ON COLUMN test_table.name IS '用户名'
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertNotNull(result.getTables());
        }

        @Test
        @DisplayName("Should parse schema-qualified COMMENT ON COLUMN syntax")
        void shouldParseSchemaQualifiedCommentOnSyntax() {
            String ddl = """
                CREATE TABLE public.test_table (
                    id INTEGER,
                    name VARCHAR(100)
                );

                COMMENT ON COLUMN public.test_table.name IS '用户名'
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1, "test");

            assertNull(result.getTables().get(0).getSchemaName());
            var nameColumn = result.getTables().get(0).getColumns().stream()
                    .filter(column -> "name".equals(column.getColumnName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("用户名", nameColumn.getLabel());
        }

        @Test
        @DisplayName("Should parse PostgreSQL multiline constraints and generated columns")
        void shouldParsePostgresMultilineConstraintsAndGeneratedColumns() {
            String ddl = """
                create table public.fact_gross_profit_detail
                (
                    id           bigserial
                        primary key,
                    period_date  date                                not null,
                    period_month char(6)                             not null,
                    org_id       integer                             not null
                        references public.dim_org,
                    product_id   integer                             not null
                        references public.dim_product,
                    channel_id   integer                             not null
                        references public.dim_channel,
                    revenue      numeric(18, 2)                      not null
                        constraint fact_gross_profit_detail_revenue_check
                            check (revenue >= (0)::numeric),
                    cost         numeric(18, 2)                      not null
                        constraint fact_gross_profit_detail_cost_check
                            check (cost >= (0)::numeric),
                    qty          integer   default 0                 not null
                        constraint fact_gross_profit_detail_qty_check
                            check (qty >= 0),
                    gross_profit numeric(18, 2) generated always as ((revenue - cost)) stored,
                    created_at   timestamp default CURRENT_TIMESTAMP not null
                );

                comment on table public.fact_gross_profit_detail is '毛利经营事实表，存储某月、某部门、某产品、某渠道下的收入、成本、数量等经营明细数据。';

                comment on column public.fact_gross_profit_detail.id is '事实表主键ID，自增生成。';
                comment on column public.fact_gross_profit_detail.period_date is '账期日期，通常取自然月第一天，如 2024-01-01。';
                comment on column public.fact_gross_profit_detail.period_month is '账期月份，格式为 YYYYMM，如 202401。';
                comment on column public.fact_gross_profit_detail.org_id is '组织ID，关联 dim_org.id，一般指向部门级组织。';
                comment on column public.fact_gross_profit_detail.product_id is '产品ID，关联 dim_product.product_id。';
                comment on column public.fact_gross_profit_detail.channel_id is '渠道ID，关联 dim_channel.channel_id。';
                comment on column public.fact_gross_profit_detail.revenue is '销售收入，模拟含税或口径统一后的营业收入金额。';
                comment on column public.fact_gross_profit_detail.cost is '销售成本，与收入口径一致，用于计算毛利。';
                comment on column public.fact_gross_profit_detail.qty is '销售数量或业务数量，用于辅助分析。';
                comment on column public.fact_gross_profit_detail.gross_profit is '毛利金额，由 revenue - cost 自动生成。';
                comment on column public.fact_gross_profit_detail.created_at is '记录创建时间。';
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1, "test");

            assertEquals(1, result.getTables().size());
            var table = result.getTables().get(0);
            assertEquals("fact_gross_profit_detail", table.getTableName());
            assertNull(table.getSchemaName());
            assertEquals("毛利经营事实表，存储某月、某部门、某产品、某渠道下的收入、成本、数量等经营明细数据。", table.getDescription());
            assertEquals(11, table.getColumns().size());

            var columnNames = table.getColumns().stream().map(DDLConfirmRequest.ParsedColumn::getColumnName).toList();
            assertEquals(java.util.List.of(
                    "id", "period_date", "period_month", "org_id", "product_id", "channel_id",
                    "revenue", "cost", "qty", "gross_profit", "created_at"), columnNames);

            var orgId = table.getColumns().stream()
                    .filter(column -> "org_id".equals(column.getColumnName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("integer not null references public.dim_org", orgId.getDataType());
            assertEquals("组织ID，关联 dim_org.id，一般指向部门级组织。", orgId.getLabel());

            var grossProfit = table.getColumns().stream()
                    .filter(column -> "gross_profit".equals(column.getColumnName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("numeric(18, 2) generated always as ((revenue - cost)) stored", grossProfit.getDataType());
            assertEquals("毛利金额，由 revenue - cost 自动生成。", grossProfit.getDescription());
        }
    }

    @Nested
    @DisplayName("parseDDL - multiple tables")
    class MultipleTablesTests {

        @Test
        @DisplayName("Should parse DDL with multiple tables")
        void shouldParseMultipleTables() {
            String ddl = """
                CREATE TABLE table1 (
                    id INTEGER -- ID
                );

                CREATE TABLE table2 (
                    id INTEGER -- ID
                );
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertEquals(2, result.getTables().size());
        }
    }

    @Nested
    @DisplayName("parseDDL - duplicate table checks")
    class DuplicateTableTests {

        @Test
        @DisplayName("Should warn when table already exists in same datasource and schema")
        void shouldWarnWhenTableAlreadyExists() {
            MetaTable existing = new MetaTable();
            existing.setId(7);
            existing.setDatasourceId(2);
            existing.setSchemaName("public");
            existing.setTableName("sales");
            when(metaTableMapper.selectOne(any())).thenReturn(existing);

            String ddl = """
                CREATE TABLE public.sales (
                    id INTEGER
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 2, "admin");

            assertNull(result.getTables().get(0).getSchemaName());
            assertEquals(1, result.getWarnings().size());
            assertTrue(result.getWarnings().get(0).contains("表已存在"));
            assertTrue(result.getWarnings().get(0).contains("sales"));
        }

        @Test
        @DisplayName("Should reject duplicate table definitions in one confirm request")
        void shouldRejectDuplicateTablesInConfirmRequest() {
            DDLConfirmRequest.ParsedTable first = parsedTable("public", "sales");
            DDLConfirmRequest.ParsedTable second = parsedTable("public", "sales");

            DDLConfirmRequest request = new DDLConfirmRequest();
            request.setDatasourceId(2);
            request.setTables(java.util.List.of(first, second));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ddlParserService.confirmAndSave(request, "admin"));

            assertTrue(ex.getMessage().contains("重复表定义"));
        }

        @Test
        @DisplayName("Should reject existing table in confirm request")
        void shouldRejectExistingTableInConfirmRequest() {
            MetaTable existing = new MetaTable();
            existing.setId(9);
            existing.setPublished(true);
            when(metaTableMapper.selectOne(any())).thenReturn(existing);

            DDLConfirmRequest request = new DDLConfirmRequest();
            request.setDatasourceId(3);
            request.setTables(java.util.List.of(parsedTable("analytics", "sales")));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> ddlParserService.confirmAndSave(request, "admin"));

            verify(metaTableMapper).selectOne(any());
            verify(metaTableMapper, never()).insert(org.mockito.ArgumentMatchers.<MetaTable>any());
            verify(metaTableMapper, never()).updateById(org.mockito.ArgumentMatchers.<MetaTable>any());
            assertTrue(ex.getMessage().contains("表已存在"));
            assertTrue(ex.getMessage().contains("表与字段管理"));
        }

        private DDLConfirmRequest.ParsedTable parsedTable(String schemaName, String tableName) {
            DDLConfirmRequest.ParsedColumn column = new DDLConfirmRequest.ParsedColumn();
            column.setColumnName("id");
            column.setDataType("INTEGER");
            column.setCommentSource("manual");

            DDLConfirmRequest.ParsedTable table = new DDLConfirmRequest.ParsedTable();
            table.setSchemaName(schemaName);
            table.setTableName(tableName);
            table.setColumns(java.util.List.of(column));
            return table;
        }
    }

    @Nested
    @DisplayName("parseDDL - error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should throw exception for empty DDL")
        void shouldThrowForEmptyDdl() {
            assertThrows(Exception.class, () -> ddlParserService.parseDDL("", 1,"test"));
        }

        @Test
        @DisplayName("Should throw exception for null DDL")
        void shouldThrowForNullDdl() {
            assertThrows(Exception.class, () -> ddlParserService.parseDDL(null, 1,"test"));
        }

        @Test
        @DisplayName("Should throw exception for whitespace-only DDL")
        void shouldThrowForWhitespaceDdl() {
            assertThrows(Exception.class, () -> ddlParserService.parseDDL("   ", 1,"test"));
        }
    }

    @Nested
    @DisplayName("DDLParseResult DTO")
    class DDLParseResultDTOTests {

        @Test
        @DisplayName("Should contain uploadId")
        void shouldContainUploadId() {
            String ddl = "CREATE TABLE t (id INTEGER)";

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result.getUploadId());
            assertFalse(result.getUploadId().isEmpty());
        }

        @Test
        @DisplayName("Should parse column data types")
        void shouldParseDataTypes() {
            String ddl = """
                CREATE TABLE test_table (
                    id INTEGER PRIMARY KEY,
                    amount DECIMAL(10,2),
                    status VARCHAR(50)
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertEquals(1, result.getTables().size());
            assertEquals(3, result.getTables().get(0).getColumns().size());
        }

        @Test
        @DisplayName("Should parse all columns including inline PRIMARY KEY and line comments")
        void shouldParseInlinePrimaryKeyAndLineComments() {
            String ddl = """
                CREATE TABLE gross_profit_detail (
                    id              INT IDENTITY(1,1) PRIMARY KEY,
                    period_month    VARCHAR(6),     -- 账期月份，格式YYYYMM
                    period_quarter  VARCHAR(6),     -- 账期季度，格式YYYYQN
                    period_year     INT,            -- 账期年份
                    revenue         DECIMAL(18,2),  -- 销售收入（含税）
                    cost            DECIMAL(18,2),  -- 销售成本（含税）
                    org_id          INT,
                    product_category VARCHAR(50),
                    created_at      DATETIME DEFAULT GETDATE()
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1, "test");

            assertNotNull(result);
            assertEquals(1, result.getTables().size());
            var columns = result.getTables().get(0).getColumns();
            assertEquals(9, columns.size());

            var idCol = columns.stream().filter(c -> "id".equals(c.getColumnName())).findFirst().orElse(null);
            assertNotNull(idCol);
            assertEquals("INT IDENTITY(1,1) PRIMARY KEY", idCol.getDataType());

            var revenueCol = columns.stream().filter(c -> "revenue".equals(c.getColumnName())).findFirst().orElse(null);
            assertNotNull(revenueCol);
            assertEquals("DECIMAL(18,2)", revenueCol.getDataType());
            assertEquals("销售收入（含税）", revenueCol.getLabel());

            var orgIdCol = columns.stream().filter(c -> "org_id".equals(c.getColumnName())).findFirst().orElse(null);
            assertNotNull(orgIdCol);
            assertEquals("manual", orgIdCol.getCommentSource());
        }
    }

    @Nested
    @DisplayName("DDLParserService - bracket-aware column split")
    class BracketAwareSplitTests {

        @Test
        @DisplayName("Should preserve DECIMAL(10,2) type after split")
        void testDecimalTypePreservedAfterSplit() {
            String ddl = """
                CREATE TABLE test_table (
                    id INTEGER,
                    amount DECIMAL(10,2),
                    rate NUMERIC(18,4),
                    name VARCHAR(100)
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertEquals(1, result.getTables().size());
            var columns = result.getTables().get(0).getColumns();

            // Find amount column and verify DECIMAL(10,2) is preserved
            var amountCol = columns.stream()
                    .filter(c -> "amount".equals(c.getColumnName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(amountCol);
            assertEquals("DECIMAL(10,2)", amountCol.getDataType());

            // Find rate column and verify NUMERIC(18,4) is preserved
            var rateCol = columns.stream()
                    .filter(c -> "rate".equals(c.getColumnName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(rateCol);
            assertEquals("NUMERIC(18,4)", rateCol.getDataType());
        }

        @Test
        @DisplayName("Should parse DDL with nested parentheses")
        void testNestedParenthesisDDLParsed() {
            String ddl = """
                CREATE TABLE complex_table (
                    id INTEGER,
                    data_type VARCHAR(100),
                    nested_data TYPE(ARRAY(10, (20, 30))),
                    complex_col TABLE(20) -- 嵌套括号类型
                )
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertEquals(1, result.getTables().size());
            // Should not throw and should parse columns
            assertTrue(result.getTables().get(0).getColumns().size() >= 2);
        }
    }

    @Nested
    @DisplayName("DDLParserService - sp_addextendedproperty comments")
    class SpExtendedPropertyTests {

        @Test
        @DisplayName("Should extract sp_addextendedproperty comments for multiple columns")
        void testSpExtendedPropertyMultipleColumns() {
            String ddl = """
                CREATE TABLE sales (
                    id INTEGER,
                    amount DECIMAL(10,2),
                    region VARCHAR(50)
                );

                EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'销售ID',
                    @level1type=N'Table', @level1name=N'sales',
                    @level2type=N'Column', @level2name=N'id';

                EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'销售金额',
                    @level1type=N'Table', @level1name=N'sales',
                    @level2type=N'Column', @level2name=N'amount';

                EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'销售区域',
                    @level1type=N'Table', @level1name=N'sales',
                    @level2type=N'Column', @level2name=N'region';
                """;

            DDLParserService.DDLParseResult result = ddlParserService.parseDDL(ddl, 1,"test");

            assertNotNull(result);
            assertEquals(1, result.getTables().size());
            var columns = result.getTables().get(0).getColumns();

            var idCol = columns.stream().filter(c -> "id".equals(c.getColumnName())).findFirst().orElse(null);
            assertNotNull(idCol);
            assertEquals("销售ID", idCol.getLabel());

            var amountCol = columns.stream().filter(c -> "amount".equals(c.getColumnName())).findFirst().orElse(null);
            assertNotNull(amountCol);
            assertEquals("销售金额", amountCol.getLabel());

            var regionCol = columns.stream().filter(c -> "region".equals(c.getColumnName())).findFirst().orElse(null);
            assertNotNull(regionCol);
            assertEquals("销售区域", regionCol.getLabel());
        }
    }
}
