package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import com.text2report.domain.meta.mapper.MetaTableMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlMetadataValidatorTest {

    @Mock
    private MetaTableMapper metaTableMapper;
    @Mock
    private MetaColumnMapper metaColumnMapper;

    private SqlMetadataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SqlMetadataValidator(metaTableMapper, metaColumnMapper);

        List<MetaTable> publishedTables = List.of(
                table(1, "fact_gross_profit_detail", true),
                table(2, "dim_org", true),
                table(3, "dim_product", true),
                table(4, "dim_channel", true)
        );
        when(metaTableMapper.selectQueryableTables(1)).thenReturn(publishedTables);

        Map<Integer, List<String>> columns = Map.of(
                1, List.of("id", "period_date", "period_month", "org_id", "product_id", "channel_id",
                        "revenue", "cost", "qty", "gross_profit", "created_at"),
                2, List.of("id", "org_name", "parent_id", "org_level", "org_code", "is_active"),
                3, List.of("product_id", "product_code", "product_name", "category", "is_active"),
                4, List.of("channel_id", "channel_code", "channel_name", "is_active")
        );
        columns.forEach((tableId, names) -> lenient().when(metaColumnMapper.selectByTableId(tableId))
                .thenReturn(names.stream().map(name -> column(tableId, name)).toList()));
    }

    @Test
    @DisplayName("场景1: 引用已发布表和合法字段，校验通过")
    void validSingleTableSqlPasses() {
        assertDoesNotThrow(() -> validator.validateTableAndColumnExistence(
                "SELECT revenue, cost FROM fact_gross_profit_detail WHERE org_id = 1 LIMIT 1000",
                1
        ));
    }

    @Test
    @DisplayName("场景2: 引用未发布表，抛出 BusinessException")
    void unpublishedTableFails() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validateTableAndColumnExistence(
                        "SELECT revenue FROM fact_gross_profit WHERE org_id = 1 LIMIT 1000",
                        1
                ));

        assertTrue(exception.getMessage().contains("未发布或不存在的数据表"));
        assertTrue(exception.getMessage().contains("fact_gross_profit"));
        assertTrue(exception.getMessage().contains("fact_gross_profit_detail"));
    }

    @Test
    @DisplayName("场景3: 引用不存在字段，抛出 BusinessException")
    void missingColumnFails() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validateTableAndColumnExistence(
                        "SELECT fake_metric FROM fact_gross_profit_detail WHERE org_id = 1 LIMIT 1000",
                        1
                ));

        assertTrue(exception.getMessage().contains("不存在的字段"));
        assertTrue(exception.getMessage().contains("fact_gross_profit_detail.fake_metric"));
        assertTrue(exception.getMessage().contains("revenue"));
    }

    @Test
    @DisplayName("场景4: 带 JOIN 的 SQL，所有表字段合法，校验通过")
    void validJoinSqlPasses() {
        assertDoesNotThrow(() -> validator.validateTableAndColumnExistence(
                """
                SELECT d.org_name, SUM(f.revenue) AS revenue_sum
                FROM fact_gross_profit_detail f
                JOIN dim_org d ON f.org_id = d.id
                WHERE f.period_month = '2026-04'
                GROUP BY d.org_name
                ORDER BY revenue_sum DESC
                LIMIT 1000
                """,
                1
        ));
    }

    @Test
    @DisplayName("场景5: 带 JOIN 的 SQL，其中一张表未发布，校验失败")
    void joinWithUnpublishedTableFails() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validateTableAndColumnExistence(
                        """
                        SELECT d.org_name, f.revenue
                        FROM fact_gross_profit f
                        JOIN dim_org d ON f.org_id = d.id
                        LIMIT 1000
                        """,
                        1
                ));

        assertTrue(exception.getMessage().contains("未发布或不存在的数据表"));
        assertTrue(exception.getMessage().contains("fact_gross_profit"));
    }

    @Test
    @DisplayName("场景6: 带别名的 SQL，正确解析别名，校验通过")
    void aliasSqlPasses() {
        assertDoesNotThrow(() -> validator.validateTableAndColumnExistence(
                "SELECT t.revenue FROM fact_gross_profit_detail t WHERE t.cost > 0 LIMIT 1000",
                1
        ));
    }

    @Test
    @DisplayName("场景7: 带 CTE 的 SQL，CTE 临时表名不被当作物理表校验")
    void cteNameIsNotValidatedAsPhysicalTable() {
        assertDoesNotThrow(() -> validator.validateTableAndColumnExistence(
                """
                WITH monthly_gp AS (
                  SELECT org_id, SUM(gross_profit) AS gp
                  FROM fact_gross_profit_detail
                  GROUP BY org_id
                )
                SELECT m.gp, d.org_name
                FROM monthly_gp m
                JOIN dim_org d ON m.org_id = d.id
                ORDER BY m.gp DESC
                LIMIT 1000
                """,
                1
        ));
    }

    @Test
    @DisplayName("场景8: GROUP BY / ORDER BY 字段正确提取并校验")
    void groupByAndOrderByColumnsAreValidated() {
        assertDoesNotThrow(() -> validator.validateTableAndColumnExistence(
                """
                SELECT period_month, SUM(revenue) AS revenue_sum
                FROM fact_gross_profit_detail
                WHERE org_id = 1
                GROUP BY period_month
                ORDER BY period_month
                LIMIT 1000
                """,
                1
        ));
    }

    @Test
    @DisplayName("场景9: 聚合函数参数中的字段被提取并校验")
    void aggregateFunctionParametersAreValidated() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validateTableAndColumnExistence(
                        "SELECT SUM(fake_amount), COUNT(*) FROM fact_gross_profit_detail WHERE org_id = 1 LIMIT 1000",
                        1
                ));

        assertTrue(exception.getMessage().contains("fact_gross_profit_detail.fake_amount"));
    }

    @Test
    @DisplayName("场景10: SELECT 计算表达式别名不被当作字段校验")
    void computedSelectAliasIsNotValidatedAsColumn() {
        assertDoesNotThrow(() -> validator.validateTableAndColumnExistence(
                """
                SELECT revenue - cost AS gp
                FROM fact_gross_profit_detail
                WHERE org_id = 1
                ORDER BY gp DESC
                LIMIT 1000
                """,
                1
        ));
    }

    private MetaTable table(Integer id, String tableName, boolean published) {
        MetaTable table = new MetaTable();
        table.setId(id);
        table.setDatasourceId(1);
        table.setTableName(tableName);
        table.setActive(true);
        table.setPublished(published);
        return table;
    }

    private MetaColumn column(Integer tableId, String columnName) {
        MetaColumn column = new MetaColumn();
        column.setTableId(tableId);
        column.setColumnName(columnName);
        column.setActive(true);
        return column;
    }
}
