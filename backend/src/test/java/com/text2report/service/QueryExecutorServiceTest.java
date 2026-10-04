package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.config.SqlGuardProperties;
import com.text2report.model.dto.QueryResultDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryExecutorServiceTest {

    @Mock
    private DataSource businessDataSource;
    @Mock
    private MetadataService metadataService;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private ResultSetMetaData resultSetMetaData;

    private QueryExecutorService queryExecutorService;
    private SqlGuardProperties sqlGuardProperties;

    @BeforeEach
    void setUp() {
        sqlGuardProperties = new SqlGuardProperties();
        sqlGuardProperties.setQueryTimeoutSeconds(30);
        sqlGuardProperties.setDefaultLimit(1000);
        queryExecutorService = new QueryExecutorService(businessDataSource, metadataService, sqlGuardProperties);
    }

    @Test
    @DisplayName("Should execute normal SELECT query successfully")
    void shouldExecuteNormalSelectQuerySuccessfully() throws Exception {
        mockStatementLifecycle("SELECT org_name, gross_profit FROM fact_gross_profit_detail");
        mockColumns(
                List.of("org_name", "gross_profit"),
                List.of(Types.VARCHAR, Types.NUMERIC),
                List.of("VARCHAR", "NUMERIC")
        );
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("子公司A");
        when(resultSet.getObject(2)).thenReturn(new BigDecimal("123.45"));

        QueryExecutorService.ExecutionResult result =
                queryExecutorService.execute("SELECT org_name, gross_profit FROM fact_gross_profit_detail");

        assertEquals(1, result.getRowCount());
        assertEquals(1, result.getRows().size());
        assertEquals("子公司A", result.getRows().get(0).get("org_name"));
        assertEquals(2, result.getColumns().size());
        verify(preparedStatement).setQueryTimeout(30);
    }

    @Test
    @DisplayName("Should return empty rows for empty result set")
    void shouldReturnEmptyRowsForEmptyResultSet() throws Exception {
        mockStatementLifecycle("SELECT org_name FROM dim_org");
        mockColumns(List.of("org_name"), List.of(Types.VARCHAR), List.of("VARCHAR"));
        when(resultSet.next()).thenReturn(false);

        QueryExecutorService.ExecutionResult result = queryExecutorService.execute("SELECT org_name FROM dim_org");

        assertEquals(0, result.getRowCount());
        assertTrue(result.getRows().isEmpty());
    }

    @Test
    @DisplayName("Should throw BusinessException on SQL timeout")
    void shouldThrowBusinessExceptionOnSqlTimeout() throws Exception {
        mockStatementExecution("SELECT org_name FROM dim_org");
        when(preparedStatement.executeQuery()).thenThrow(new SQLTimeoutException("timeout"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> queryExecutorService.execute("SELECT org_name FROM dim_org"));

        assertEquals("查询执行超时", exception.getMessage());
    }

    @Test
    @DisplayName("Should throw BusinessException on connection exception")
    void shouldThrowBusinessExceptionOnConnectionException() throws Exception {
        when(businessDataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> queryExecutorService.execute("SELECT org_name FROM dim_org"));

        assertEquals("查询执行失败", exception.getMessage());
    }

    @Test
    @DisplayName("Should map JDBC data types to frontend friendly types")
    void shouldMapJdbcDataTypesToFrontendFriendlyTypes() {
        when(metadataService.getColumnLabels(eq(1), anyCollection())).thenReturn(Map.of());

        List<QueryResultDTO.ColumnMeta> columns = queryExecutorService.enrichColumnMeta(List.of(
                QueryExecutorService.ExecutionColumnMeta.builder().key("org_name").jdbcType(Types.VARCHAR).jdbcTypeName("VARCHAR").build(),
                QueryExecutorService.ExecutionColumnMeta.builder().key("gross_profit").jdbcType(Types.NUMERIC).jdbcTypeName("NUMERIC").build(),
                QueryExecutorService.ExecutionColumnMeta.builder().key("period_date").jdbcType(Types.DATE).jdbcTypeName("DATE").build()
        ), 1);

        assertEquals(List.of("string", "number", "date"),
                columns.stream().map(QueryResultDTO.ColumnMeta::getDataType).toList());
    }

    @Test
    @DisplayName("Should replace titles with metadata labels when available")
    void shouldReplaceTitlesWithMetadataLabelsWhenAvailable() {
        when(metadataService.getColumnLabels(eq(1), anyCollection()))
                .thenReturn(Map.of("org_name", "组织名称"));

        List<QueryResultDTO.ColumnMeta> columns = queryExecutorService.enrichColumnMeta(List.of(
                QueryExecutorService.ExecutionColumnMeta.builder().key("org_name").jdbcType(Types.VARCHAR).jdbcTypeName("VARCHAR").build()
        ), 1);

        assertEquals("组织名称", columns.get(0).getTitle());
    }

    @Test
    @DisplayName("Should close ResultSet Statement and Connection")
    void shouldCloseResources() throws Exception {
        mockStatementLifecycle("SELECT org_name FROM dim_org");
        mockColumns(List.of("org_name"), List.of(Types.VARCHAR), List.of("VARCHAR"));
        when(resultSet.next()).thenReturn(false);

        queryExecutorService.execute("SELECT org_name FROM dim_org");

        verify(resultSet).close();
        verify(preparedStatement).close();
        verify(connection).close();
    }

    @Test
    @DisplayName("Should respect row limit when result set is large")
    void shouldRespectRowLimitWhenResultSetIsLarge() throws Exception {
        sqlGuardProperties.setDefaultLimit(2);
        mockStatementLifecycle("SELECT org_name FROM dim_org");
        mockColumns(List.of("org_name"), List.of(Types.VARCHAR), List.of("VARCHAR"));
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getObject(1)).thenReturn("A", "B", "C");

        QueryExecutorService.ExecutionResult result = queryExecutorService.execute("SELECT org_name FROM dim_org");

        assertEquals(2, result.getRowCount());
        assertEquals(List.of("A", "B"),
                result.getRows().stream().map(row -> row.get("org_name")).toList());
    }

    @Test
    @DisplayName("Should classify syntax failure as retryable execution failure")
    void shouldClassifySyntaxFailureAsRetryableExecutionFailure() throws Exception {
        mockStatementExecution("SELECT broken");
        SQLException syntaxException = new SQLException("syntax error at or near \"broken\"", "42601");
        when(preparedStatement.executeQuery()).thenThrow(syntaxException);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> queryExecutorService.execute("SELECT broken"));

        assertEquals("syntax error", exception.getMessage());
    }

    private void mockStatementLifecycle(String sql) throws Exception {
        mockStatementExecution(sql);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
    }

    private void mockStatementExecution(String sql) throws Exception {
        when(businessDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
    }

    private void mockColumns(List<String> labels, List<Integer> jdbcTypes, List<String> typeNames) throws Exception {
        when(resultSetMetaData.getColumnCount()).thenReturn(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            int index = i + 1;
            when(resultSetMetaData.getColumnLabel(index)).thenReturn(labels.get(i));
            when(resultSetMetaData.getColumnType(index)).thenReturn(jdbcTypes.get(i));
            when(resultSetMetaData.getColumnTypeName(index)).thenReturn(typeNames.get(i));
        }
    }
}
