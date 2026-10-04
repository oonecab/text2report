package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.config.SqlGuardProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlGuardService {

    private static final Set<String> ORG_SCOPED_TABLES = Set.of("dim_org");

    private final SqlGuardProperties sqlGuardProperties;

    public String validateAndInject(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "SQL 不能为空");
        }
        if (sql.length() > 10000) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "SQL 长度超过 10000 字符限制");
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql.trim());
            validateStatement(statement);
            return injectLimit(statement);
        } catch (JSQLParserException e) {
            log.warn("SQL parsing failed, rejecting: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SQL_PARSE_ERROR, "SQL 解析失败");
        }
    }

    public String validate(String sql) {
        return validateAndInject(sql);
    }

    public String enforceOrgScope(String sql, List<Integer> allowedOrgIds) {
        if (allowedOrgIds == null || allowedOrgIds.isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_ORG_SCOPE_VIOLATION, "缺少可用组织范围");
        }
        Set<Long> allowed = allowedOrgIds.stream().map(Integer::longValue).collect(Collectors.toSet());

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select) || !requiresOrgScope(select, new HashSet<>())) {
                return sql;
            }

            OrgScopeContext ctx = new OrgScopeContext(allowed);
            collectOrgScope(select, ctx, new HashSet<>());
            if (ctx.hasRequiredOrgScope) {
                return sql;
            }

            injectOrgScope(select, allowedOrgIds, new HashSet<>());
            return statement.toString();
        } catch (JSQLParserException e) {
            log.warn("SQL org scope injection failed, rejecting: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SQL_PARSE_ERROR, "SQL 解析失败");
        }
    }

    public String validateOrgScope(String sql, List<Integer> allowedOrgIds) {
        if (allowedOrgIds == null || allowedOrgIds.isEmpty()) {
            throw new BusinessException(ErrorCode.SQL_ORG_SCOPE_VIOLATION, "缺少可用组织范围");
        }
        Set<Long> allowed = allowedOrgIds.stream().map(Integer::longValue).collect(Collectors.toSet());

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "只允许执行 SELECT 查询");
            }
            if (!requiresOrgScope(select, new HashSet<>())) {
                return sql;
            }

            OrgScopeContext ctx = new OrgScopeContext(allowed);
            collectOrgScope(select, ctx, new HashSet<>());
            if (ctx.hasUnauthorizedOrgId) {
                throw new BusinessException(ErrorCode.SQL_ORG_SCOPE_VIOLATION, "SQL 包含未授权组织范围");
            }
            if (!ctx.hasRequiredOrgScope) {
                throw new BusinessException(ErrorCode.SQL_ORG_SCOPE_VIOLATION, "SQL 缺少组织范围过滤");
            }
            return sql;
        } catch (JSQLParserException e) {
            log.warn("SQL org scope parsing failed, rejecting: {}", e.getMessage());
            throw new BusinessException(ErrorCode.SQL_PARSE_ERROR, "SQL 解析失败");
        }
    }

    private void validateStatement(Statement statement) {
        if (statement == null) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "空 SQL 语句");
        }
        if (statement instanceof Select select) {
            validateSelect(select);
            return;
        }
        if (statement instanceof Insert) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "INSERT not allowed");
        }
        if (statement instanceof Update) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "UPDATE not allowed");
        }
        if (statement instanceof Delete) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "DELETE not allowed");
        }
        if (statement instanceof Drop) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "DROP not allowed");
        }
        if (statement instanceof Truncate) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "TRUNCATE not allowed");
        }
        if (statement instanceof Merge) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "MERGE not allowed");
        }
        if (statement instanceof Execute) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "EXEC/EXECUTE not allowed");
        }
        if (statement instanceof Alter) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "ALTER not allowed");
        }
        throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "只允许执行 SELECT 查询");
    }

    private void validateSelect(Select select) {
        ValidationContext ctx = new ValidationContext();
        validateSelectRecursive(select, ctx);
        if (ctx.blocked) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, ctx.blockReason);
        }
    }

    private void validateSelectRecursive(Select select, ValidationContext ctx) {
        if (select == null || ctx.blocked) {
            return;
        }
        if (select instanceof PlainSelect plainSelect) {
            registerCtes(plainSelect.getWithItemsList(), ctx);
            validateWithItems(plainSelect.getWithItemsList(), ctx);
            validatePlainSelect(plainSelect, ctx);
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                validateSelectRecursive(child, ctx);
                if (ctx.blocked) {
                    return;
                }
            }
            if (setOperationList.getOrderByElements() != null) {
                for (OrderByElement element : setOperationList.getOrderByElements()) {
                    validateExpression(element.getExpression(), ctx);
                    if (ctx.blocked) {
                        return;
                    }
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            validateSelectRecursive(parenthesedSelect.getSelect(), ctx);
        }
    }

    private void validatePlainSelect(PlainSelect plainSelect, ValidationContext ctx) {
        if (plainSelect.getSelectItems() != null) {
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                Expression expression = item.getExpression();
                if (sqlGuardProperties.isBlockSelectStar() && expression instanceof AllColumns) {
                    ctx.setBlocked("不允许使用 SELECT *，请明确指定查询字段");
                    return;
                }
                if (sqlGuardProperties.isBlockSelectStar() && expression instanceof AllTableColumns) {
                    ctx.setBlocked("不允许使用 t.*，请明确指定查询字段");
                    return;
                }
                validateExpression(expression, ctx);
                if (ctx.blocked) {
                    return;
                }
            }
        }

        validateFromItem(plainSelect.getFromItem(), ctx);
        if (ctx.blocked) {
            return;
        }

        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                validateFromItem(join.getRightItem(), ctx);
                if (ctx.blocked) {
                    return;
                }
                validateExpression(join.getOnExpression(), ctx);
                if (ctx.blocked) {
                    return;
                }
            }
        }

        validateExpression(plainSelect.getWhere(), ctx);
        validateGroupBy(plainSelect, ctx);
        validateExpression(plainSelect.getHaving(), ctx);
        if (plainSelect.getOrderByElements() != null) {
            for (OrderByElement orderByElement : plainSelect.getOrderByElements()) {
                validateExpression(orderByElement.getExpression(), ctx);
                if (ctx.blocked) {
                    return;
                }
            }
        }
    }

    private void validateWithItems(List<WithItem> withItems, ValidationContext ctx) {
        if (withItems == null) {
            return;
        }
        for (WithItem withItem : withItems) {
            validateSelectRecursive(withItem.getSelect(), ctx);
            if (ctx.blocked) {
                return;
            }
        }
    }

    private void registerCtes(List<WithItem> withItems, ValidationContext ctx) {
        if (withItems == null) {
            return;
        }
        for (WithItem withItem : withItems) {
            String alias = resolveWithItemAlias(withItem);
            if (StringUtils.hasText(alias)) {
                ctx.cteNames.add(alias);
            }
        }
    }

    private void validateGroupBy(PlainSelect plainSelect, ValidationContext ctx) {
        if (plainSelect.getGroupBy() == null || plainSelect.getGroupBy().getGroupByExpressions() == null) {
            return;
        }
        ExpressionList<?> expressions = plainSelect.getGroupBy().getGroupByExpressions();
        if (expressions.getExpressions() == null) {
            return;
        }
        for (Object expression : expressions.getExpressions()) {
            if (expression instanceof Expression groupExpression) {
                validateExpression(groupExpression, ctx);
                if (ctx.blocked) {
                    return;
                }
            }
        }
    }

    private void validateFromItem(FromItem fromItem, ValidationContext ctx) {
        if (fromItem == null || ctx.blocked) {
            return;
        }
        if (fromItem instanceof Table table) {
            validateTable(table, ctx);
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            validateSelectRecursive(parenthesedSelect.getSelect(), ctx);
            return;
        }
        if (fromItem instanceof LateralSubSelect lateralSubSelect) {
            validateSelectRecursive(lateralSubSelect.getSelect(), ctx);
            return;
        }
        if (fromItem instanceof TableFunction tableFunction) {
            validateExpression(tableFunction.getFunction(), ctx);
        }
    }

    private void validateTable(Table table, ValidationContext ctx) {
        String tableName = normalizeIdentifier(table.getName());
        if (ctx.cteNames.contains(tableName)) {
            return;
        }

        String databaseName = table.getDatabase() != null ? normalizeIdentifier(table.getDatabase().getDatabaseName()) : null;
        if (isBlockedSchema(databaseName)) {
            ctx.setBlocked("禁止访问系统 schema: " + databaseName);
            return;
        }

        String schemaName = normalizeIdentifier(table.getSchemaName());
        if (isBlockedSchema(schemaName)) {
            ctx.setBlocked("禁止访问系统 schema: " + schemaName);
            return;
        }

        String qualifiedName = normalizeQualifiedTable(schemaName, tableName);
        if (isBlockedSystemObject(schemaName, tableName, qualifiedName)) {
            ctx.setBlocked("禁止访问系统对象: " + qualifiedName);
        }
    }

    private void validateExpression(Expression expression, ValidationContext ctx) {
        if (expression == null || ctx.blocked) {
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            validateExpression(parenthesis.getExpression(), ctx);
            return;
        }
        if (expression instanceof SignedExpression signedExpression) {
            validateExpression(signedExpression.getExpression(), ctx);
            return;
        }
        if (expression instanceof Function function) {
            validateFunction(function, ctx);
            if (ctx.blocked) {
                return;
            }
        }
        if (expression instanceof ParenthesedSelect parenthesedSelect) {
            validateSelectRecursive(parenthesedSelect.getSelect(), ctx);
            return;
        }
        if (expression instanceof ExistsExpression existsExpression) {
            validateExpression(existsExpression.getRightExpression(), ctx);
            return;
        }
        if (expression instanceof InExpression inExpression) {
            validateExpression(inExpression.getLeftExpression(), ctx);
            validateItemsList(inExpression.getRightExpression(), ctx);
            return;
        }
        if (expression instanceof IsNullExpression isNullExpression) {
            validateExpression(isNullExpression.getLeftExpression(), ctx);
            return;
        }
        if (expression instanceof LikeExpression likeExpression) {
            validateExpression(likeExpression.getLeftExpression(), ctx);
            validateExpression(likeExpression.getRightExpression(), ctx);
            return;
        }
        if (expression instanceof Between between) {
            validateExpression(between.getLeftExpression(), ctx);
            validateExpression(between.getBetweenExpressionStart(), ctx);
            validateExpression(between.getBetweenExpressionEnd(), ctx);
            return;
        }
        if (expression instanceof CaseExpression caseExpression) {
            validateExpression(caseExpression.getSwitchExpression(), ctx);
            for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                validateExpression(whenClause.getWhenExpression(), ctx);
                validateExpression(whenClause.getThenExpression(), ctx);
                if (ctx.blocked) {
                    return;
                }
            }
            validateExpression(caseExpression.getElseExpression(), ctx);
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            validateExpression(binaryExpression.getLeftExpression(), ctx);
            validateExpression(binaryExpression.getRightExpression(), ctx);
            return;
        }
        if (expression instanceof Column column) {
            validateQualifiedColumn(column, ctx);
        }
    }

    private void validateFunction(Function function, ValidationContext ctx) {
        String rawName = function.getName();
        String normalizedName = normalizeIdentifier(rawName);
        String simpleName = simpleName(normalizedName);
        String schemaName = schemaName(normalizedName);

        if (isBlockedSchema(schemaName) || PostgresRiskCatalog.BLOCKED_FUNCTIONS.contains(simpleName) || simpleName.startsWith("pg_")) {
            ctx.setBlocked("禁止调用高危函数: " + normalizedName);
            return;
        }

        if (function.getParameters() != null && function.getParameters().getExpressions() != null) {
            for (Expression parameter : function.getParameters().getExpressions()) {
                validateExpression(parameter, ctx);
                if (ctx.blocked) {
                    return;
                }
            }
        }
    }

    private void validateItemsList(Expression rightExpression, ValidationContext ctx) {
        if (rightExpression == null || ctx.blocked) {
            return;
        }
        if (rightExpression instanceof ParenthesedSelect parenthesedSelect) {
            validateSelectRecursive(parenthesedSelect.getSelect(), ctx);
            return;
        }
        if (rightExpression instanceof ExpressionList<?> expressionList) {
            for (Expression expression : expressionList.getExpressions()) {
                validateExpression(expression, ctx);
                if (ctx.blocked) {
                    return;
                }
            }
            return;
        }
        validateExpression(rightExpression, ctx);
    }

    private void validateQualifiedColumn(Column column, ValidationContext ctx) {
        Table table = column.getTable();
        if (table == null || !StringUtils.hasText(table.getName())) {
            return;
        }
        validateTable(table, ctx);
    }

    private boolean requiresOrgScope(Select select, Set<String> inheritedCteNames) {
        OrgScopedTableContext ctx = new OrgScopedTableContext(inheritedCteNames);
        collectReferencedTables(select, ctx);
        return ctx.requiresOrgScope;
    }

    private void collectReferencedTables(Select select, OrgScopedTableContext ctx) {
        if (select == null || ctx.requiresOrgScope) {
            return;
        }
        if (select instanceof PlainSelect plainSelect) {
            registerCtes(plainSelect.getWithItemsList(), ctx.cteNames);
            if (plainSelect.getWithItemsList() != null) {
                for (WithItem withItem : plainSelect.getWithItemsList()) {
                    collectReferencedTables(withItem.getSelect(), ctx);
                    if (ctx.requiresOrgScope) {
                        return;
                    }
                }
            }
            collectReferencedTablesFromItem(plainSelect.getFromItem(), ctx);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    collectReferencedTablesFromItem(join.getRightItem(), ctx);
                    if (ctx.requiresOrgScope) {
                        return;
                    }
                }
            }
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                collectReferencedTables(child, ctx);
                if (ctx.requiresOrgScope) {
                    return;
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            collectReferencedTables(parenthesedSelect.getSelect(), ctx);
        }
    }

    private void registerCtes(List<WithItem> withItems, Set<String> cteNames) {
        if (withItems == null) {
            return;
        }
        for (WithItem withItem : withItems) {
            String alias = resolveWithItemAlias(withItem);
            if (StringUtils.hasText(alias)) {
                cteNames.add(alias);
            }
        }
    }

    private String resolveWithItemAlias(WithItem withItem) {
        if (withItem == null) {
            return null;
        }
        if (withItem.getAlias() != null) {
            return normalizeIdentifier(withItem.getAlias().getName());
        }
        return normalizeIdentifier(withItem.toString().split("\\s+")[0]);
    }

    private void collectReferencedTablesFromItem(FromItem fromItem, OrgScopedTableContext ctx) {
        if (fromItem == null || ctx.requiresOrgScope) {
            return;
        }
        if (fromItem instanceof Table table) {
            String normalized = normalizeIdentifier(table.getName());
            if (ctx.cteNames.contains(normalized)) {
                return;
            }
            if (normalized.startsWith("fact_") || ORG_SCOPED_TABLES.contains(normalized)) {
                ctx.requiresOrgScope = true;
            }
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            collectReferencedTables(parenthesedSelect.getSelect(), ctx);
            return;
        }
        if (fromItem instanceof LateralSubSelect lateralSubSelect) {
            collectReferencedTables(lateralSubSelect.getSelect(), ctx);
        }
    }

    private void collectOrgScope(Select select, OrgScopeContext ctx, Set<String> cteNames) {
        if (select == null || ctx.hasUnauthorizedOrgId) {
            return;
        }
        if (select instanceof PlainSelect plainSelect) {
            registerCtes(plainSelect.getWithItemsList(), cteNames);
            if (plainSelect.getWithItemsList() != null) {
                for (WithItem withItem : plainSelect.getWithItemsList()) {
                    collectOrgScope(withItem.getSelect(), ctx, new HashSet<>(cteNames));
                    if (ctx.hasUnauthorizedOrgId) {
                        return;
                    }
                }
            }
            registerOrgScopeQualifiers(plainSelect, ctx, cteNames);
            if (isScopedByAllowedOrg(plainSelect.getWhere(), ctx)) {
                ctx.hasRequiredOrgScope = true;
            }
            collectOrgScopeFromItem(plainSelect.getFromItem(), ctx, cteNames);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    collectOrgScopeFromItem(join.getRightItem(), ctx, cteNames);
                    if (ctx.hasUnauthorizedOrgId) {
                        return;
                    }
                }
            }
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                collectOrgScope(child, ctx, new HashSet<>(cteNames));
                if (ctx.hasUnauthorizedOrgId) {
                    return;
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            collectOrgScope(parenthesedSelect.getSelect(), ctx, cteNames);
        }
    }

    private void collectOrgScopeFromItem(FromItem fromItem, OrgScopeContext ctx, Set<String> cteNames) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            collectOrgScope(parenthesedSelect.getSelect(), ctx, new HashSet<>(cteNames));
        } else if (fromItem instanceof LateralSubSelect lateralSubSelect) {
            collectOrgScope(lateralSubSelect.getSelect(), ctx, new HashSet<>(cteNames));
        }
    }

    private void registerOrgScopeQualifiers(PlainSelect plainSelect, OrgScopeContext ctx, Set<String> cteNames) {
        registerOrgScopeQualifier(plainSelect.getFromItem(), ctx, cteNames);
        if (plainSelect.getJoins() == null) {
            return;
        }
        for (Join join : plainSelect.getJoins()) {
            registerOrgScopeQualifier(join.getRightItem(), ctx, cteNames);
        }
    }

    private void registerOrgScopeQualifier(FromItem fromItem, OrgScopeContext ctx, Set<String> cteNames) {
        if (!(fromItem instanceof Table table)) {
            return;
        }
        String tableName = normalizeIdentifier(table.getName());
        if (!ORG_SCOPED_TABLES.contains(tableName) || cteNames.contains(tableName)) {
            return;
        }
        ctx.dimOrgQualifiers.add(normalizeIdentifier(resolveQualifier(table)));
    }

    private void injectOrgScope(Select select, List<Integer> allowedOrgIds, Set<String> cteNames) throws JSQLParserException {
        if (select == null) {
            return;
        }
        if (select instanceof PlainSelect plainSelect) {
            registerCtes(plainSelect.getWithItemsList(), cteNames);
            if (plainSelect.getWithItemsList() != null) {
                for (WithItem withItem : plainSelect.getWithItemsList()) {
                    injectOrgScope(withItem.getSelect(), allowedOrgIds, new HashSet<>(cteNames));
                }
            }

            String scopeColumn = resolveOrgScopeColumn(plainSelect, cteNames);
            if (StringUtils.hasText(scopeColumn)) {
                Expression scopeExpression = CCJSqlParserUtil.parseCondExpression(
                        scopeColumn + " IN (" + joinOrgIds(allowedOrgIds) + ")"
                );
                Expression where = plainSelect.getWhere();
                plainSelect.setWhere(where == null ? scopeExpression : new AndExpression(where, scopeExpression));
            }
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                injectOrgScope(child, allowedOrgIds, new HashSet<>(cteNames));
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            injectOrgScope(parenthesedSelect.getSelect(), allowedOrgIds, cteNames);
        }
    }

    private String resolveOrgScopeColumn(PlainSelect plainSelect, Set<String> cteNames) {
        String dimOrgScopeColumn = resolveOrgScopeColumn(plainSelect.getFromItem(), cteNames);
        if (isFactOrgScopeColumn(dimOrgScopeColumn)) {
            return dimOrgScopeColumn;
        }

        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                String scopeColumn = resolveOrgScopeColumn(join.getRightItem(), cteNames);
                if (isFactOrgScopeColumn(scopeColumn)) {
                    return scopeColumn;
                }
                if (!StringUtils.hasText(dimOrgScopeColumn)) {
                    dimOrgScopeColumn = scopeColumn;
                }
            }
        }
        return dimOrgScopeColumn;
    }

    private String resolveOrgScopeColumn(FromItem fromItem, Set<String> cteNames) {
        if (!(fromItem instanceof Table table)) {
            return null;
        }
        String tableName = normalizeIdentifier(table.getName());
        if (!StringUtils.hasText(tableName) || cteNames.contains(tableName)) {
            return null;
        }

        String qualifier = resolveQualifier(table);
        if (tableName.startsWith("fact_")) {
            return qualifier + ".org_id";
        }
        if (ORG_SCOPED_TABLES.contains(tableName)) {
            return qualifier + ".id";
        }
        return null;
    }

    private String resolveQualifier(Table table) {
        if (table.getAlias() != null && StringUtils.hasText(table.getAlias().getName())) {
            return table.getAlias().getName();
        }
        return table.getName();
    }

    private boolean isFactOrgScopeColumn(String scopeColumn) {
        return StringUtils.hasText(scopeColumn) && scopeColumn.endsWith(".org_id");
    }

    private String joinOrgIds(List<Integer> orgIds) {
        return orgIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private boolean isScopedByAllowedOrg(Expression expression, OrgScopeContext ctx) {
        if (expression == null || ctx.hasUnauthorizedOrgId) {
            return false;
        }
        if (expression instanceof Parenthesis parenthesis) {
            return isScopedByAllowedOrg(parenthesis.getExpression(), ctx);
        }
        if (expression instanceof AndExpression andExpression) {
            return isScopedByAllowedOrg(andExpression.getLeftExpression(), ctx)
                    || isScopedByAllowedOrg(andExpression.getRightExpression(), ctx);
        }
        if (expression instanceof OrExpression orExpression) {
            return isScopedByAllowedOrg(orExpression.getLeftExpression(), ctx)
                    && isScopedByAllowedOrg(orExpression.getRightExpression(), ctx);
        }
        if (expression instanceof EqualsTo equalsTo) {
            return isAllowedOrgEquality(equalsTo.getLeftExpression(), equalsTo.getRightExpression(), ctx)
                    || isAllowedOrgEquality(equalsTo.getRightExpression(), equalsTo.getLeftExpression(), ctx);
        }
        if (expression instanceof InExpression inExpression) {
            return isAllowedOrgInExpression(inExpression, ctx);
        }
        return false;
    }

    private boolean isAllowedOrgEquality(Expression possibleColumn, Expression possibleValue, OrgScopeContext ctx) {
        if (!isOrgScopeColumn(possibleColumn, ctx)) {
            return false;
        }
        Long orgId = getLongValue(possibleValue);
        if (orgId == null) {
            return false;
        }
        if (!ctx.allowedOrgIds.contains(orgId)) {
            ctx.hasUnauthorizedOrgId = true;
            return false;
        }
        return true;
    }

    private boolean isAllowedOrgInExpression(InExpression inExpression, OrgScopeContext ctx) {
        if (inExpression.isNot() || !isOrgScopeColumn(inExpression.getLeftExpression(), ctx)) {
            return false;
        }
        if (!(inExpression.getRightExpression() instanceof ExpressionList<?> expressionList)
                || expressionList.getExpressions() == null
                || expressionList.getExpressions().isEmpty()) {
            return false;
        }
        for (Expression expression : expressionList.getExpressions()) {
            Long orgId = getLongValue(expression);
            if (orgId == null) {
                return false;
            }
            if (!ctx.allowedOrgIds.contains(orgId)) {
                ctx.hasUnauthorizedOrgId = true;
                return false;
            }
        }
        return true;
    }

    private boolean isOrgScopeColumn(Expression expression, OrgScopeContext ctx) {
        if (!(expression instanceof Column column)) {
            return false;
        }
        if ("org_id".equalsIgnoreCase(column.getColumnName())) {
            return true;
        }
        if (!"id".equalsIgnoreCase(column.getColumnName()) || column.getTable() == null) {
            return false;
        }
        return ctx.dimOrgQualifiers.contains(normalizeIdentifier(column.getTable().getName()));
    }

    private Long getLongValue(Expression expression) {
        if (expression instanceof LongValue longValue) {
            return longValue.getValue();
        }
        if (expression instanceof SignedExpression signedExpression) {
            Long value = getLongValue(signedExpression.getExpression());
            if (value == null) {
                return null;
            }
            return signedExpression.getSign() == '-' ? -value : value;
        }
        return null;
    }

    private String injectLimit(Statement statement) {
        if (!(statement instanceof Select select)) {
            return statement.toString();
        }
        if (hasLimit(select)) {
            return select.toString();
        }
        int limit = sqlGuardProperties.getDefaultLimit();
        if (select instanceof PlainSelect plainSelect) {
            Limit limitExpr = new Limit();
            limitExpr.setRowCount(new LongValue(limit));
            plainSelect.setLimit(limitExpr);
        } else if (select instanceof SetOperationList setOperationList) {
            Limit limitExpr = new Limit();
            limitExpr.setRowCount(new LongValue(limit));
            setOperationList.setLimit(limitExpr);
        }
        return select.toString();
    }

    private boolean hasLimit(Select select) {
        if (select instanceof PlainSelect plainSelect) {
            return plainSelect.getLimit() != null || plainSelect.getTop() != null;
        }
        return select instanceof SetOperationList setOperationList && setOperationList.getLimit() != null;
    }

    public boolean hasSelectStar(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select select) {
                SelectStarContext ctx = new SelectStarContext();
                checkSelectStarRecursive(select, ctx);
                return ctx.hasSelectStar;
            }
            return false;
        } catch (JSQLParserException e) {
            return false;
        }
    }

    private void checkSelectStarRecursive(Select select, SelectStarContext ctx) {
        if (ctx.hasSelectStar || select == null) {
            return;
        }
        if (select instanceof PlainSelect plainSelect) {
            registerCtes(plainSelect.getWithItemsList(), new HashSet<>());
            if (plainSelect.getWithItemsList() != null) {
                for (WithItem withItem : plainSelect.getWithItemsList()) {
                    checkSelectStarRecursive(withItem.getSelect(), ctx);
                    if (ctx.hasSelectStar) {
                        return;
                    }
                }
            }
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    if (item.getExpression() instanceof AllColumns || item.getExpression() instanceof AllTableColumns) {
                        ctx.hasSelectStar = true;
                        return;
                    }
                }
            }
            validateSelectStarFromItem(plainSelect.getFromItem(), ctx);
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    validateSelectStarFromItem(join.getRightItem(), ctx);
                    if (ctx.hasSelectStar) {
                        return;
                    }
                }
            }
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select child : setOperationList.getSelects()) {
                checkSelectStarRecursive(child, ctx);
                if (ctx.hasSelectStar) {
                    return;
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            checkSelectStarRecursive(parenthesedSelect.getSelect(), ctx);
        }
    }

    private void validateSelectStarFromItem(FromItem fromItem, SelectStarContext ctx) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            checkSelectStarRecursive(parenthesedSelect.getSelect(), ctx);
        } else if (fromItem instanceof LateralSubSelect lateralSubSelect) {
            checkSelectStarRecursive(lateralSubSelect.getSelect(), ctx);
        }
    }

    static String normalizeIdentifier(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.replace("\"", "").trim().toLowerCase(Locale.ROOT);
    }

    static String simpleName(String raw) {
        String normalized = normalizeIdentifier(raw);
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        int index = normalized.lastIndexOf('.');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    static String schemaName(String raw) {
        String normalized = normalizeIdentifier(raw);
        if (!StringUtils.hasText(normalized) || !normalized.contains(".")) {
            return null;
        }
        return normalized.substring(0, normalized.lastIndexOf('.'));
    }

    static String normalizeQualifiedTable(String schema, String table) {
        if (!StringUtils.hasText(table)) {
            return table;
        }
        String normalizedSchema = normalizeIdentifier(schema);
        String normalizedTable = normalizeIdentifier(table);
        return StringUtils.hasText(normalizedSchema) ? normalizedSchema + "." + normalizedTable : normalizedTable;
    }

    private boolean isBlockedSchema(String schemaName) {
        if (!StringUtils.hasText(schemaName)) {
            return false;
        }
        String normalized = normalizeIdentifier(schemaName);
        if (PostgresRiskCatalog.BLOCKED_SCHEMAS.contains(normalized)) {
            return true;
        }
        for (String part : normalized.split("\\.")) {
            if (PostgresRiskCatalog.BLOCKED_SCHEMAS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockedSystemObject(String schemaName, String tableName, String qualifiedName) {
        if (PostgresRiskCatalog.BLOCKED_SYSTEM_OBJECTS.contains(qualifiedName)
                || PostgresRiskCatalog.BLOCKED_SYSTEM_OBJECTS.contains(tableName)) {
            return true;
        }
        return PostgresRiskCatalog.BLOCKED_TABLE_PREFIXES.stream().anyMatch(tableName::startsWith);
    }

    private static class ValidationContext {
        private final Set<String> cteNames = new HashSet<>();
        private boolean blocked;
        private String blockReason = "";

        void setBlocked(String reason) {
            this.blocked = true;
            this.blockReason = reason;
        }
    }

    private static class SelectStarContext {
        private boolean hasSelectStar;
    }

    private static class OrgScopeContext {
        private final Set<Long> allowedOrgIds;
        private final Set<String> dimOrgQualifiers = new HashSet<>();
        private boolean hasRequiredOrgScope;
        private boolean hasUnauthorizedOrgId;

        private OrgScopeContext(Set<Long> allowedOrgIds) {
            this.allowedOrgIds = allowedOrgIds;
        }
    }

    private static class OrgScopedTableContext {
        private final Set<String> cteNames;
        private boolean requiresOrgScope;

        private OrgScopedTableContext(Set<String> inheritedCteNames) {
            this.cteNames = new HashSet<>(inheritedCteNames);
        }
    }
}
