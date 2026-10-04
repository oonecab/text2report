# SQL 安全校验

## 问题一：JSqlParser 解析失败时的处理策略

### 问题
JSqlParser 解析失败时怎么处理？是放行还是拒绝？

### 答案

**答案是：拒绝，不是放行。**

### 解析

JSqlParser 的 `validateStatement` 方法会先尝试将 SQL 解析成 AST（抽象语法树）：

```java
try {
    Statement statement = CCJSqlParserUtil.parse(trimmedSql);
    validateStatement(statement);
} catch (JSQLParserException e) {
    // 解析失败 → 直接拒绝
    throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "Invalid SQL syntax");
}
```

如果解析失败，说明这条 SQL 根本不是合法的 SQL 语句。此时会直接拒绝，不会放行到后续的 AST 检查环节。

### 两层检查机制

1. **第一层：解析层** — 解析失败 = 拒绝
2. **第二层：AST 层** — 语义违规（如 SELECT *、DELETE 等）= 拒绝
3. **第三层：LIMIT 注入 + JDBC 超时**

### 结论

解析失败 = 不可信 = 拒绝。这是安全设计的基本原则。

---

## 问题二：SELECT * 的检测深度（嵌套子查询场景）

### 问题
`SELECT * FROM (SELECT * FROM users)` 这种 SQL，SELECT * 的检测是在最外层还是递归所有层？

### 答案

**答案是：递归检测所有层。**

### 解析

JSqlParser 的 Visitor 模式是递归自动向下遍历整棵 AST 树的，不会停在某一层。

```
        [外层 SELECT]
           │
    ┌──────┴──────┐
    │             │
 [FROM 子句]    [SELECT *]  ← 外层发现 *
    │
    ▼
[子查询 SELECT]
    │
    ├──────┴──────┐
    │             │
 [FROM]       [SELECT *]  ← 递归进来，内层也发现 *
    │
    ▼
  [users]
```

### 代码实现

`validatePlainSelect` 方法在检查完当前层的 SELECT 列表后，会递归检查 FROM 子句中的子查询：

```java
private void validateFromItem(FromItem fromItem, ValidationContext ctx) {
    if (fromItem instanceof ParenthesedSelect) {
        ParenthesedSelect ps = (ParenthesedSelect) fromItem;
        if (ps.getSelect() != null) {
            validateSelectRecursive(ps.getSelect(), ctx);  // 递归检查
        }
    }
}
```

### 边界情况覆盖

| SQL 形态 | 是否拦截 | 说明 |
|----------|----------|------|
| `SELECT * FROM users` | ✅ 拦截 | 最外层 * |
| `SELECT a FROM (SELECT * FROM t)` | ✅ 拦截 | 递归检测到子查询 * |
| `SELECT a FROM (SELECT b FROM (SELECT * FROM t))` | ✅ 拦截 | 多层嵌套，递归到底 |

---

## 问题三：WITH ... CTE 绕过场景

### 问题
`WITH cte AS (DELETE FROM ...) SELECT * FROM cte` 这种写法，Guard 能拦住吗？

### 答案

**能拦住，但当前实现不够严谨。**

### 解析

这个 SQL 看起来像是在"读"数据（SELECT FROM cte），但实际上 CTE 里面藏了一个 DELETE。

JSqlParser 解析后的 AST 结构：

```
[WithStatement]
    ├── [CTE: cte]
    │       └── [DeleteStatement]  ← 这里是要拦截的 DELETE
    │
    └── [Select]                   ← 外层 SELECT
```

### 当前代码的问题

`validateStatement` 方法只处理了 `Select`、`Insert`、`Update`、`Delete` 等类型，**没有专门处理 `WithStatement`**：

```java
private void validateStatement(Statement statement) {
    if (statement instanceof Select) {
        validateSelect((Select) statement);
    } else if (statement instanceof Insert) {
        throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "INSERT not allowed");
    } else if (statement instanceof Delete) {
        throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "DELETE not allowed");
    }
    // ...
    else {
        throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "Only SELECT statements are allowed");
    }
}
```

由于 `WithStatement` 不属于 `Select` 的子类，会走到 else 分支，虽然最终会拒绝，但拒绝原因不对。

### 正确的实现

需要专门处理 `WithStatement`，递归检查每个 CTE 内部的语句类型：

```java
if (statement instanceof WithStatement) {
    validateWithStatement((WithStatement) statement);
}

private void validateWithStatement(WithStatement withStatement) {
    for (WithItem item : withStatement.getCommonTableExpressions()) {
        Statement innerStmt = item.getStatement();
        if (innerStmt instanceof Delete) {
            throw new BusinessException(ErrorCode.SQL_GUARD_BLOCKED, "DELETE in CTE not allowed");
        }
        if (innerStmt instanceof Select) {
            validateSelectRecursive((Select) innerStmt, new ValidationContext());
        }
        // 递归处理嵌套 CTE
        if (innerStmt instanceof WithStatement) {
            validateWithStatement((WithStatement) innerStmt);
        }
    }
}
```

### 攻击场景覆盖

| SQL | 能否拦住 |
|-----|----------|
| `WITH cte AS (DELETE FROM users) SELECT * FROM cte` | ✅ |
| `WITH cte AS (UPDATE users SET ...) SELECT * FROM cte` | ✅ |
| `WITH cte AS (SELECT * FROM t) SELECT * FROM cte` | ✅ |

---

## 问题四：LIMIT 自动注入在 UNION 场景下的行为

### 问题
LIMIT 自动注入在有 UNION 的情况下加在哪？语义正确吗？

### 答案

**LIMIT 加在整个 `SetOperationList`（UNION 结果集）上，语义正确（对于 NL-to-SQL 场景）。**

### 解析

代码实现：

```java
if (select instanceof SetOperationList) {
    SetOperationList setOps = (SetOperationList) select;
    Limit limitExpr = new Limit();
    limitExpr.setRowCount(new LongValue(limit));
    setOps.setLimit(limitExpr);  // LIMIT 加在整个 UNION 上
}
```

生成的 SQL：

```sql
(SELECT * FROM t1) UNION (SELECT * FROM t2) LIMIT 1000
```

### 语义分析

| SQL | 实际行为 | 符合预期吗？ |
|-----|----------|-------------|
| `SELECT * FROM t1 UNION SELECT * FROM t2 LIMIT 10` | 整个 UNION 结果限制 10 行 | ✅ 正确 |
| `SELECT * FROM t1 LIMIT 3 UNION SELECT * FROM t2 LIMIT 5` | 先各子查询 LIMIT，再整体限流 | ⚠️ 可能有歧义 |

### 为什么可接受

对于 NL-to-SQL 场景：
1. **限流目的是防止 LLM 生成没有 LIMIT 的巨大查询把数据库拖死**
2. **整体限流保证最终返回不超过 N 行**，这是正确的策略
3. LLM 生成的 UNION 查询通常是多个维度的聚合结果，限制总行数是合理的

### 总结

LIMIT 打在 `SetOperationList` 顶层，对安全限流目的来说语义正确。

---

## 问题五：危险函数拦截列表

### 问题
拦截了哪些"危险函数"？OPENROWSET、BULK INSERT、xp_cmdshell 都在列表里吗？这个列表是硬编码的还是可配置的？

### 答案

**当前拦截的主要是 SQL Server 的存储过程（xp_*, sp_ 开头），OPENROWSET 和 BULK INSERT 不在里面。列表是硬编码的。**

### 实际代码

```java
private static final Set<String> BLOCKED_FUNCTIONS = Set.of(
        "xp_cmdshell", "sp_executesql", "sp_makewebtask", "xp_regread", "xp_regwrite",
        "xp_dirtree", "xp_fileexist", "xp_getnetname", "xp_terminate_process",
        "xp_regaddmultistring", "xp_regdeletekey", "xp_regdeletevalue",
        "xp_regremovemultistring", "sp_start_job", "sp_stop_job"
);
```

### 覆盖情况

| 函数/语句 | 在列表里吗？ | 防护方式 |
|----------|-------------|----------|
| `xp_cmdshell` | ✅ 有 | 函数名黑名单 |
| `OPENROWSET` | ❌ 没有 | 会被 INSERT 拦截拦掉 |
| `BULK INSERT` | ❌ 没有 | 会被 INSERT 拦截拦掉 |
| `sp_executesql` | ✅ 有（注意：代码里拼写有误） |

### 为什么 OPENROWSET 和 BULK INSERT 没有？

1. 这两个是 **SQL Server 特有语法**，不是 PostgreSQL 函数
2. 当前项目使用 **PostgreSQL**，这两个语句本来就不适用
3. JSqlParser 解析时不会把它们当作函数节点处理
4. 它们本质上是数据导入语句，会被 `INSERT not allowed` 拦掉

### 配置方式

**当前是硬编码的：**

```java
private static final Set<String> BLOCKED_FUNCTIONS = Set.of(...)
```

没有从配置文件或数据库表读取。

### 改进建议

```yaml
# application.yml
sql-guard:
  blocked-functions:
    - xp_cmdshell
    - sp_executesql
    # 可动态配置
```

或存储到 `meta_table` 数据库表中，支持运行时更新和审计。

---

## 总结

| 问题 | 结论 |
|------|------|
| 解析失败处理 | 拒绝，不是放行 |
| SELECT * 检测深度 | 递归所有层 |
| CTE 绕过 | 能拦住，但需要完善 WithStatement 处理 |
| UNION + LIMIT | 加在顶层，对限流目的语义正确 |
| 危险函数列表 | 硬编码，缺少 OPENROWSET/BULK INSERT（但被 INSERT 拦截覆盖） |

---

*文档生成时间：2026-04-21*
