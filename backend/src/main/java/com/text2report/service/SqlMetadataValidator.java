package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import com.text2report.domain.meta.mapper.MetaTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that SQL references only published metadata tables and active metadata columns.
 * This service runs after SqlGuardService and before QueryExecutorService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlMetadataValidator {

    private final MetaTableMapper metaTableMapper;
    private final MetaColumnMapper metaColumnMapper;

    /**
     * 校验 SQL 中引用的表和字段是否存在于已发布的 metadata 中。
     * 必须在 SqlGuardService.validate() 之后、QueryExecutorService.execute() 之前调用。
     *
     * @param sql 已通过 SqlGuard 校验的 SQL
     * @param datasourceId 当前数据源 ID
     * @throws BusinessException 如果存在未发布/不存在的表或字段
     */
    public void validateTableAndColumnExistence(String sql, Integer datasourceId) {
        if (!StringUtils.hasText(sql)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "SQL 不能为空");
        }
        if (datasourceId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Datasource ID cannot be null");
        }

        MetadataSnapshot snapshot = loadMetadataSnapshot(datasourceId);
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select select) {
                validateSelect(select, snapshot, new LinkedHashSet<>());
            }
        } catch (JSQLParserException e) {
            log.warn("SQL metadata validation parse failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SQL_PARSE_ERROR, "SQL 解析失败");
        }
    }

    private MetadataSnapshot loadMetadataSnapshot(Integer datasourceId) {
        List<MetaTable> tables = metaTableMapper.selectQueryableTables(datasourceId);
        Map<String, MetaTable> tablesByName = new LinkedHashMap<>();
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();

        for (MetaTable table : tables) {
            String tableName = normalize(table.getTableName());
            if (!StringUtils.hasText(tableName)) {
                continue;
            }
            tablesByName.put(tableName, table);
            columnsByTable.put(tableName, metaColumnMapper.selectByTableId(table.getId()).stream()
                    .map(MetaColumn::getColumnName)
                    .filter(StringUtils::hasText)
                    .map(this::normalize)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        return new MetadataSnapshot(tablesByName, columnsByTable);
    }

    private void validateSelect(Select select, MetadataSnapshot snapshot, Set<String> inheritedCteNames) {
        if (select == null) {
            return;
        }

        Set<String> cteNames = new LinkedHashSet<>(inheritedCteNames);
        registerCtes(select.getWithItemsList(), cteNames);
        validateWithItems(select.getWithItemsList(), snapshot, cteNames);

        if (select instanceof PlainSelect plainSelect) {
            validatePlainSelect(plainSelect, snapshot, cteNames);
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : safeList(setOperationList.getSelects())) {
                validateSelect(child, snapshot, cteNames);
            }
            SelectScope scope = new SelectScope(cteNames);
            validateOrderBy(setOperationList.getOrderByElements(), snapshot, scope);
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            validateSelect(parenthesedSelect.getSelect(), snapshot, cteNames);
        }
    }

    private void validateWithItems(List<WithItem> withItems, MetadataSnapshot snapshot, Set<String> cteNames) {
        for (WithItem withItem : safeList(withItems)) {
            validateSelect(withItem.getSelect(), snapshot, cteNames);
        }
    }

    private void registerCtes(List<WithItem> withItems, Set<String> cteNames) {
        for (WithItem withItem : safeList(withItems)) {
            if (withItem.getAlias() != null && StringUtils.hasText(withItem.getAlias().getName())) {
                cteNames.add(normalize(withItem.getAlias().getName()));
            }
        }
    }

    private void validatePlainSelect(PlainSelect plainSelect, MetadataSnapshot snapshot, Set<String> cteNames) {
        SelectScope scope = new SelectScope(cteNames);
        collectSelectAliases(plainSelect, scope);

        validateFromItem(plainSelect.getFromItem(), snapshot, scope);
        for (Join join : safeList(plainSelect.getJoins())) {
            validateFromItem(join.getRightItem(), snapshot, scope);
        }

        for (SelectItem<?> item : safeList(plainSelect.getSelectItems())) {
            validateExpression(item.getExpression(), snapshot, scope);
        }
        validateExpression(plainSelect.getWhere(), snapshot, scope);
        validateJoinExpressions(plainSelect.getJoins(), snapshot, scope);
        validateGroupBy(plainSelect, snapshot, scope);
        validateExpression(plainSelect.getHaving(), snapshot, scope);
        validateOrderBy(plainSelect.getOrderByElements(), snapshot, scope);
    }

    private void collectSelectAliases(PlainSelect plainSelect, SelectScope scope) {
        for (SelectItem<?> item : safeList(plainSelect.getSelectItems())) {
            if (item.getAlias() != null && StringUtils.hasText(item.getAlias().getName())) {
                scope.outputAliases.add(normalize(item.getAlias().getName()));
            }
        }
    }

    private void validateFromItem(FromItem fromItem, MetadataSnapshot snapshot, SelectScope scope) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            validateTable(table, snapshot, scope);
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            validateSelect(parenthesedSelect.getSelect(), snapshot, scope.cteNames);
            registerDerivedAlias(parenthesedSelect, scope);
            return;
        }
        if (fromItem instanceof LateralSubSelect lateralSubSelect) {
            validateSelect(lateralSubSelect.getSelect(), snapshot, scope.cteNames);
            registerDerivedAlias(lateralSubSelect, scope);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
            validateFromItem(parenthesedFromItem.getFromItem(), snapshot, scope);
            for (Join join : safeList(parenthesedFromItem.getJoins())) {
                validateFromItem(join.getRightItem(), snapshot, scope);
            }
            validateJoinExpressions(parenthesedFromItem.getJoins(), snapshot, scope);
            registerDerivedAlias(parenthesedFromItem, scope);
            return;
        }
        if (fromItem instanceof TableFunction tableFunction) {
            validateExpression(tableFunction.getFunction(), snapshot, scope);
            registerDerivedAlias(tableFunction, scope);
        }
    }

    private void validateTable(Table table, MetadataSnapshot snapshot, SelectScope scope) {
        String tableName = normalize(table.getName());
        if (!StringUtils.hasText(tableName)) {
            return;
        }

        String alias = table.getAlias() != null ? normalize(table.getAlias().getName()) : null;
        if (scope.cteNames.contains(tableName)) {
            scope.derivedQualifiers.add(tableName);
            if (StringUtils.hasText(alias)) {
                scope.derivedQualifiers.add(alias);
            }
            return;
        }

        if (!snapshot.tablesByName.containsKey(tableName)) {
            throw new BusinessException(
                    ErrorCode.METADATA_VALIDATION_FAILED,
                    "SQL 引用了未发布或不存在的数据表: " + table.getName()
                            + "。已发布的可用表包括: " + firstItems(snapshot.tablesByName.keySet(), 5)
            );
        }

        scope.physicalTableNames.add(tableName);
        scope.qualifierToTable.put(tableName, tableName);
        if (StringUtils.hasText(alias)) {
            scope.qualifierToTable.put(alias, tableName);
        }
    }

    private void validateJoinExpressions(List<Join> joins, MetadataSnapshot snapshot, SelectScope scope) {
        for (Join join : safeList(joins)) {
            for (Expression expression : safeCollection(join.getOnExpressions())) {
                validateExpression(expression, snapshot, scope);
            }
            for (net.sf.jsqlparser.schema.Column usingColumn : safeList(join.getUsingColumns())) {
                validateColumn(usingColumn, snapshot, scope);
            }
        }
    }

    private void validateGroupBy(PlainSelect plainSelect, MetadataSnapshot snapshot, SelectScope scope) {
        if (plainSelect.getGroupBy() == null || plainSelect.getGroupBy().getGroupByExpressions() == null) {
            return;
        }
        ExpressionList<?> expressions = plainSelect.getGroupBy().getGroupByExpressions();
        for (Expression expression : safeList(expressions.getExpressions())) {
            validateExpression(expression, snapshot, scope);
        }
    }

    private void validateOrderBy(List<OrderByElement> orderByElements, MetadataSnapshot snapshot, SelectScope scope) {
        for (OrderByElement element : safeList(orderByElements)) {
            validateExpression(element.getExpression(), snapshot, scope);
        }
    }

    private void validateExpression(Expression expression, MetadataSnapshot snapshot, SelectScope scope) {
        if (expression == null || expression instanceof AllColumns || expression instanceof AllTableColumns) {
            return;
        }
        if (expression instanceof net.sf.jsqlparser.schema.Column column) {
            validateColumn(column, snapshot, scope);
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            validateExpression(parenthesis.getExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof SignedExpression signedExpression) {
            validateExpression(signedExpression.getExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof Function function) {
            validateFunction(function, snapshot, scope);
            return;
        }
        if (expression instanceof ParenthesedSelect parenthesedSelect) {
            validateSelect(parenthesedSelect.getSelect(), snapshot, scope.cteNames);
            return;
        }
        if (expression instanceof ExistsExpression existsExpression) {
            validateExpression(existsExpression.getRightExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof InExpression inExpression) {
            validateExpression(inExpression.getLeftExpression(), snapshot, scope);
            validateExpression(inExpression.getRightExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof IsNullExpression isNullExpression) {
            validateExpression(isNullExpression.getLeftExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof LikeExpression likeExpression) {
            validateExpression(likeExpression.getLeftExpression(), snapshot, scope);
            validateExpression(likeExpression.getRightExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof Between between) {
            validateExpression(between.getLeftExpression(), snapshot, scope);
            validateExpression(between.getBetweenExpressionStart(), snapshot, scope);
            validateExpression(between.getBetweenExpressionEnd(), snapshot, scope);
            return;
        }
        if (expression instanceof CaseExpression caseExpression) {
            validateExpression(caseExpression.getSwitchExpression(), snapshot, scope);
            for (WhenClause whenClause : safeList(caseExpression.getWhenClauses())) {
                validateExpression(whenClause.getWhenExpression(), snapshot, scope);
                validateExpression(whenClause.getThenExpression(), snapshot, scope);
            }
            validateExpression(caseExpression.getElseExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            validateExpression(binaryExpression.getLeftExpression(), snapshot, scope);
            validateExpression(binaryExpression.getRightExpression(), snapshot, scope);
            return;
        }
        if (expression instanceof ExpressionList<?> expressionList) {
            for (Expression child : safeList(expressionList.getExpressions())) {
                validateExpression(child, snapshot, scope);
            }
        }
    }

    private void validateFunction(Function function, MetadataSnapshot snapshot, SelectScope scope) {
        if (function.isAllColumns()) {
            return;
        }
        if (function.getParameters() != null) {
            for (Expression parameter : safeList(function.getParameters().getExpressions())) {
                validateExpression(parameter, snapshot, scope);
            }
        }
        if (function.getAttribute() instanceof Expression attributeExpression) {
            validateExpression(attributeExpression, snapshot, scope);
        }
        for (OrderByElement orderByElement : safeList(function.getOrderByElements())) {
            validateExpression(orderByElement.getExpression(), snapshot, scope);
        }
    }

    private void validateColumn(net.sf.jsqlparser.schema.Column column, MetadataSnapshot snapshot, SelectScope scope) {
        String columnName = normalize(column.getColumnName());
        if (!StringUtils.hasText(columnName) || "*".equals(columnName)) {
            return;
        }

        Table table = column.getTable();
        String qualifier = table != null ? normalize(table.getName()) : null;
        if (StringUtils.hasText(qualifier)) {
            if (scope.outputAliases.contains(columnName) && !scope.qualifierToTable.containsKey(qualifier)) {
                return;
            }
            if (scope.derivedQualifiers.contains(qualifier)) {
                return;
            }
            String physicalTable = scope.qualifierToTable.get(qualifier);
            if (!StringUtils.hasText(physicalTable)) {
                return;
            }
            validateColumnOnTable(physicalTable, columnName, snapshot);
            return;
        }

        if (scope.outputAliases.contains(columnName)) {
            return;
        }
        if (scope.physicalTableNames.isEmpty()) {
            return;
        }
        if (scope.physicalTableNames.size() == 1) {
            validateColumnOnTable(scope.physicalTableNames.iterator().next(), columnName, snapshot);
            return;
        }
        boolean foundInAnyTable = scope.physicalTableNames.stream()
                .map(snapshot.columnsByTable::get)
                .filter(Objects::nonNull)
                .anyMatch(columns -> columns.contains(columnName));
        if (!foundInAnyTable) {
            String firstTable = scope.physicalTableNames.iterator().next();
            throw missingColumn(firstTable, columnName, snapshot);
        }
    }

    private void validateColumnOnTable(String tableName, String columnName, MetadataSnapshot snapshot) {
        Set<String> columns = snapshot.columnsByTable.getOrDefault(tableName, Set.of());
        if (!columns.contains(columnName)) {
            throw missingColumn(tableName, columnName, snapshot);
        }
    }

    private BusinessException missingColumn(String tableName, String columnName, MetadataSnapshot snapshot) {
        return new BusinessException(
                ErrorCode.METADATA_VALIDATION_FAILED,
                "SQL 引用了不存在的字段: " + tableName + "." + columnName
                        + "。该表可用字段包括: "
                        + firstItems(snapshot.columnsByTable.getOrDefault(tableName, Set.of()), 10)
        );
    }

    private void registerDerivedAlias(FromItem fromItem, SelectScope scope) {
        if (fromItem != null && fromItem.getAlias() != null && StringUtils.hasText(fromItem.getAlias().getName())) {
            scope.derivedQualifiers.add(normalize(fromItem.getAlias().getName()));
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String firstItems(Collection<String> values, int limit) {
        return values.stream()
                .filter(StringUtils::hasText)
                .limit(limit)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private <T> Collection<T> safeCollection(Collection<T> values) {
        return values == null ? List.of() : values;
    }

    private record MetadataSnapshot(Map<String, MetaTable> tablesByName, Map<String, Set<String>> columnsByTable) {
    }

    private static class SelectScope {
        private final Set<String> cteNames;
        private final Map<String, String> qualifierToTable = new HashMap<>();
        private final Set<String> physicalTableNames = new LinkedHashSet<>();
        private final Set<String> derivedQualifiers = new HashSet<>();
        private final Set<String> outputAliases = new HashSet<>();

        private SelectScope(Set<String> cteNames) {
            this.cteNames = cteNames;
        }
    }
}
