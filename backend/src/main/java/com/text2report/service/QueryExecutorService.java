package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.config.SqlGuardProperties;
import com.text2report.model.dto.QueryResultDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes validated read-only SQL against the business datasource.
 */
@Slf4j
@Service
public class QueryExecutorService {

    private final DataSource businessDataSource;
    private final MetadataService metadataService;
    private final SqlGuardProperties sqlGuardProperties;

    public QueryExecutorService(@Qualifier("businessDataSource") DataSource businessDataSource,
                                MetadataService metadataService,
                                SqlGuardProperties sqlGuardProperties) {
        this.businessDataSource = businessDataSource;
        this.metadataService = metadataService;
        this.sqlGuardProperties = sqlGuardProperties;
    }

    public ExecutionResult execute(String validatedSql) {
        if (!StringUtils.hasText(validatedSql)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SQL 不能为空");
        }

        long start = System.currentTimeMillis();
        try (Connection connection = businessDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(validatedSql)) {

            statement.setQueryTimeout(sqlGuardProperties.getQueryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery()) {
                ExecutionResult result = extractResult(resultSet);
                result.setExecutionMs(System.currentTimeMillis() - start);
                return result;
            }
        } catch (SQLTimeoutException e) {
            log.error("Query execution timed out", e);
            throw new BusinessException(ErrorCode.DATASOURCE_ERROR, "查询执行超时");
        } catch (SQLException e) {
            log.error("Query execution failed", e);
            throw new BusinessException(ErrorCode.DATASOURCE_ERROR, classifySqlError(e));
        }
    }

    public List<QueryResultDTO.ColumnMeta> enrichColumnMeta(List<ExecutionColumnMeta> columns, Integer datasourceId) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }

        Map<String, String> labelMap = metadataService.getColumnLabels(
                datasourceId,
                columns.stream().map(ExecutionColumnMeta::getKey).toList()
        );

        List<QueryResultDTO.ColumnMeta> result = new ArrayList<>(columns.size());
        for (ExecutionColumnMeta column : columns) {
            String label = labelMap.get(column.getKey().toLowerCase(Locale.ROOT));
            result.add(QueryResultDTO.ColumnMeta.builder()
                    .key(column.getKey())
                    .title(StringUtils.hasText(label) ? label : toFriendlyTitle(column.getKey()))
                    .dataType(mapJdbcType(column.getJdbcType(), column.getJdbcTypeName()))
                    .build());
        }
        return result;
    }

    private ExecutionResult extractResult(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();

        List<ExecutionColumnMeta> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(ExecutionColumnMeta.builder()
                    .key(metaData.getColumnLabel(i))
                    .jdbcType(metaData.getColumnType(i))
                    .jdbcTypeName(metaData.getColumnTypeName(i))
                    .build());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < sqlGuardProperties.getDefaultLimit()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }

        return ExecutionResult.builder()
                .columns(columns)
                .rows(rows)
                .rowCount(rows.size())
                .build();
    }

    private String classifySqlError(SQLException exception) {
        String state = exception.getSQLState();
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);

        if (message.contains("timeout") || message.contains("timed out") || "57014".equals(state)) {
            return "查询执行超时";
        }
        if ("42703".equals(state) || message.contains("column")) {
            return "column does not exist";
        }
        if ("42P01".equals(state) || message.contains("relation") || message.contains("table")) {
            return "table does not exist";
        }
        if ("42601".equals(state) || message.contains("syntax error")) {
            return "syntax error";
        }
        if ("42702".equals(state) || message.contains("ambiguous")) {
            return "ambiguous column reference";
        }
        return "查询执行失败";
    }

    private String mapJdbcType(int jdbcType, String jdbcTypeName) {
        return switch (jdbcType) {
            case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.NCHAR, Types.LONGNVARCHAR ->
                    "string";
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT,
                    Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> "number";
            case Types.DATE -> "date";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "datetime";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            default -> inferTypeFromName(jdbcTypeName);
        };
    }

    private String inferTypeFromName(String jdbcTypeName) {
        if (!StringUtils.hasText(jdbcTypeName)) {
            return "string";
        }
        String normalized = jdbcTypeName.toLowerCase(Locale.ROOT);
        if (normalized.contains("date") && !normalized.contains("time")) {
            return "date";
        }
        if (normalized.contains("time")) {
            return "datetime";
        }
        if (normalized.contains("int") || normalized.contains("numeric")
                || normalized.contains("decimal") || normalized.contains("float")
                || normalized.contains("double")) {
            return "number";
        }
        if (normalized.contains("bool")) {
            return "boolean";
        }
        return "string";
    }

    private String toFriendlyTitle(String columnName) {
        if (!StringUtils.hasText(columnName)) {
            return columnName;
        }
        String[] parts = columnName.split("_");
        List<String> words = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            words.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return words.isEmpty() ? columnName : String.join(" ", words);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionColumnMeta {
        private String key;
        private int jdbcType;
        private String jdbcTypeName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionResult {
        @Builder.Default
        private List<ExecutionColumnMeta> columns = new ArrayList<>();
        @Builder.Default
        private List<Map<String, Object>> rows = new ArrayList<>();
        private int rowCount;
        private long executionMs;
    }
}
