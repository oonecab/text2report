# 多数据源场景下 SQL 执行路径致命缺陷 + SqlGuard 硬编码假设风险

## 基本信息

| 项目 | 内容 |
|------|------|
| 文档创建时间 | 2026-05-04 12:19:36 (Asia/Shanghai) |
| 严重程度 | P0 — 阻断多租户/多数据源场景 |
| 影响范围 | `QueryExecutorService`, `BusinessDataSourceConfig`, `SqlGuardService`, `EmbeddingService`, `MetadataService` |
| 前置条件 | 项目计划支持不同公司/客户上传各自的 DDL 并执行查询 |

---

## 问题一：SQL 执行路径缺少动态数据源路由

### 1.1 问题摘要

**整个项目只有 ONE 业务数据库连接**。`meta_datasource` 表中存储的多数据源连接信息（`jdbcUrl`、`username`、`password`）从未被用于 SQL 查询执行。无论前端选择哪个数据源，所有 LLM 生成的 SQL 都在同一个 `text2report` 业务库上执行。

### 1.2 根因分析

#### 1.2.1 唯一的业务数据源 Bean

`BusinessDataSourceConfig.java` 创建了全局唯一的 `businessDataSource` Bean，永远指向 `application.yml` 中 `spring.datasource.business` 配置的那个 PostgreSQL 数据库：

```java
// BusinessDataSourceConfig.java:23-30
@Primary
@Bean(name = "businessDataSource")
@ConfigurationProperties(prefix = "spring.datasource.business")
public DataSource businessDataSource() {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setConnectionInitSql("SET TIME ZONE '" + TimeZoneConfig.BUSINESS_TIME_ZONE + "'");
    return dataSource;
}
```

该 Bean 在应用启动时一次性创建，**没有动态切换、代理或路由机制**。

#### 1.2.2 QueryExecutorService 硬绑定唯一数据源

`QueryExecutorService` 构造时直接注入 `businessDataSource`，所有查询方法都从该数据源获取连接：

```java
// QueryExecutorService.java:37-47
private final DataSource businessDataSource;

public QueryExecutorService(@Qualifier("businessDataSource") DataSource businessDataSource,
                            MetadataService metadataService,
                            SqlGuardProperties sqlGuardProperties) {
    this.businessDataSource = businessDataSource;
}

// QueryExecutorService.java:49-56
public ExecutionResult execute(String validatedSql) {
    // ...
    try (Connection connection = businessDataSource.getConnection();  // ← 永远同一个库
         PreparedStatement statement = connection.prepareStatement(validatedSql)) {
        statement.setQueryTimeout(sqlGuardProperties.getQueryTimeoutSeconds());
        // ...
    }
}
```

`execute()` 方法签名是 `execute(String validatedSql)`——**没有任何 `datasourceId` 参数**，不具备根据数据源选择连接的能力。

#### 1.2.3 OrchestratorService 链路未传递 datasourceId

```java
// OrchestratorService.query() → buildQueryContext() →
//   MetadataService.retrieveForPrompt(question, datasourceId)  ← 元数据检索有datasourceId
//   LLMService.generateSQL(...)                                 ← LLM 生成 SQL
//   SqlGuardService.validate(sql)                                ← 校验
//   QueryExecutorService.execute(validatedSql)                   ← 执行，没有datasourceId！
```

元数据检索路径知道用哪个 `datasourceId`，但 **SQL 执行路径完全丢失了这个信息**。LLM 基于正确数据源的元数据生成了正确的 SQL（例如引用了公司A的表 `sales_detail`），但执行时这些表在 `text2report` 库中不存在，直接报错 `relation "sales_detail" does not exist` (SQLSTATE 42P01)。

#### 1.2.4 meta_datasource 的连接信息是"死数据"

`MetaDatasource` 实体存储了完整的连接信息：

```java
// MetaDatasource.java
private String dbType;    // sqlserver, postgresql, mysql  — 存了但从未影响SQL方言
private String jdbcUrl;   // JDBC连接串                      — 存了但从未用于建立连接
private String username;  // 数据库用户名                    — 存了但从未用于认证
private String password;  // 加密密码                        — 存了但从未用于认证
```

这些字段的**唯一用途**是：
- `DataSourceService` 中的 CRUD 管理页面展示
- `MetadataController.confirmDDL()` 中通过 `id` 校验数据源是否存在 (`MetadataController.java:126-130`)

**从未被** `QueryExecutorService`、`OrchestratorService`、`BusinessDataSourceConfig` 读取或使用。

### 1.3 实际影响

| 场景 | 表现 | 用户感知 |
|------|------|----------|
| 公司A上传DDL（表: `t_order`, `t_customer`），在其自有库执行 | `QueryExecutorService` 仍在 `text2report` 库查找 `t_order` → `42P01 relation does not exist` | 解析成功、预览正常，但查询报错 |
| 公司B使用 MySQL 方言的表结构 | LLM 被告知 PostgreSQL 方言 → 生成的 SQL 语法错误 → `42601 syntax error` | 同样的流程走完，执行失败 |
| 公司C使用 SQL Server，表名含 `dbo.` schema 前缀 | `DDLParserService` 的 `CREATE_TABLE_PATTERN` 正则 `(\\w+)\\.` 只捕获点号前的部分 → 表名解析可能异常 | DDL 解析阶段就可能出错 |

### 1.4 建议修改方向

1. **实现动态数据源路由** — 使用 Spring 的 `AbstractRoutingDataSource`，在请求上下文中根据 `datasourceId` 切换目标数据源：

   ```
   Request → TraceContext.setDatasourceId() → RoutingDataSource.determineCurrentLookupKey()
            → 获取对应的 HikariCP 连接池 → 执行查询
   ```

2. **QueryExecutorService.execute() 接受 datasourceId** — 方法签名改为 `execute(String validatedSql, Integer datasourceId)`，内部通过 DataSource 路由获取正确连接。

3. **Prompt 方言动态化** — `PromptBuilder` 应根据 `meta_datasource.dbType` 设置 `{dialect}` 占位符，而非永远填入 PostgreSQL。

4. **安全性考量** — 数据源连接密码需考虑解密方案（当前配置明文存储，但实际接入客户数据库时需加密存储 + 运行时解密）。数据源连接池需按需创建/销毁，避免连接泄漏。

---

## 问题二：SqlGuard 组织范围校验的硬编码假设

### 2.1 问题摘要

`SqlGuardService.validateOrgScope()` 中的"需要组织范围过滤"判定逻辑硬编码了表名前缀 `fact_` 和固定白名单 `dim_org`。当不同公司/客户使用不同的表命名约定时，校验可能漏检（安全风险）或误检（可用性风险）。

### 2.2 根因分析

#### 2.2.1 硬编码的判定逻辑

```java
// SqlGuardService.java:66
private static final Set<String> ORG_SCOPED_TABLES = Set.of("dim_org");

// SqlGuardService.java:524-529
private void collectReferencedTablesFromItem(FromItem fromItem, OrgScopedTableContext ctx) {
    // ...
    if (fromItem instanceof Table table) {
        String normalized = normalizeIdentifier(table.getName());
        // 硬编码规则：表名以 fact_ 开头，或在 ORG_SCOPED_TABLES 白名单中
        if (normalized.startsWith("fact_") || ORG_SCOPED_TABLES.contains(normalized)) {
            ctx.requiresOrgScope = true;
        }
        return;
    }
    // ...
}
```

这段代码的含义是："只有表名以 `fact_` 开头，或者表名是 `dim_org`，才需要校验 SQL 中是否包含组织范围过滤"。

#### 2.2.2 不同命名约定下的行为

| 表命名方式 | requiresOrgScope 判定 | 后果 |
|-----------|----------------------|------|
| `fact_gross_profit_detail` (当前命名) | `true` | 正确：该表确实含 `org_id`，应校验 |
| `dim_org` (当前命名) | `true` | 正确：组织维度表须校验 |
| `t_sales` (客户A的命名) | `false` | **安全漏检** — 如果 `t_sales` 含 `org_id`，SQL 不带组织过滤也能通过 |
| `sales_fact` (客户B的命名, 后缀) | `false` | **安全漏检** — 表名不以 `fact_` 开头 |
| `fact_sales` (客户C命名) | `true` | **可能误检** — 如果 `fact_sales` 实际不含 `org_id`（全局统计表），会错误阻止查询 |
| `revenue_table` | `false` | **安全漏检** |
| `order_detail` | `false` | **安全漏检** |

#### 2.2.3 组织ID列名也是硬编码的

```java
// SqlGuardService.java:653-655
private boolean isOrgIdColumn(Expression expression) {
    return expression instanceof Column column && "org_id".equalsIgnoreCase(column.getColumnName());
}
```

如果客户表中组织字段命名为 `company_id`、`tenant_id`、`dept_code`，校验逻辑完全无法识别。

### 2.3 问题一与此问题的联动风险

将两个问题组合起来看，形成一个危险的场景：

```
1. 客户上传DDL → t_order (含 tenant_id 字段, 是多租户隔离键)
2. MetadataService 正确检索了 t_order 的元数据
3. LLM 生成了 SELECT * FROM t_order (不带 tenant_id 过滤)
4. SqlGuardService.validateOrgScope():
   - "t_order" 不以 "fact_" 开头 → requiresOrgScope = false
   - 直接返回通过，不检查任何组织过滤
5. QueryExecutorService:
   - 在 text2report 库执行 → relation does not exist（侥幸失败）

   如果问题一被修复（动态数据源路由）但问题二未修复：
   - 在客户自己的库上直接执行 SELECT * FROM t_order
   - 没有 tenant_id 过滤 → 跨租户数据泄漏
```

这是一个**权限绕过 + 数据泄漏**的高危链路。

### 2.4 建议修改方向

1. **从"命名规则驱动"改为"元数据驱动"** — 在 `meta_table` 或 `meta_column` 中增加字段标记是否需要组织范围过滤：

   ```sql
   ALTER TABLE meta_tables ADD COLUMN requires_org_scope BOOLEAN DEFAULT false;
   ALTER TABLE meta_columns ADD COLUMN org_key_column BOOLEAN DEFAULT false;
   ```

   - DDL 上传时，如检测到 `org_id` 列，自动标记 `requires_org_scope = true`
   - `SqlGuardService` 读取该标记，而非硬编码表名前缀

2. **组织列名也改为可配置** — 不硬编码 `org_id`，改为从 `meta_column` 中查找标识了 `org_key_column = true` 的列。

3. **向后兼容过渡期** — 保留 `fact_` 前缀和 `dim_org` 作为默认启发式规则，但增加元数据标记优先级：元数据标记 > 命名启发式。

---

## 受影响文件清单

| 文件 | 问题一相关 | 问题二相关 |
|------|----------|----------|
| `backend/src/main/java/com/text2report/config/BusinessDataSourceConfig.java` | 唯一业务数据源 Bean，无路由机制 | — |
| `backend/src/main/java/com/text2report/service/QueryExecutorService.java` | `execute()` 无 datasourceId 参数 | — |
| `backend/src/main/java/com/text2report/service/OrchestratorService.java` | `query()` 链路未传递 datasourceId 到执行层 | — |
| `backend/src/main/java/com/text2report/service/SqlGuardService.java` | — | `ORG_SCOPED_TABLES` 硬编码, `fact_` 前缀硬编码, `org_id` 列名硬编码 |
| `backend/src/main/java/com/text2report/service/MetadataService.java` | — | `retrieveExamples()` / `retrieveGlossary()` 未按 datasource 过滤 |
| `backend/src/main/java/com/text2report/service/DDLParserService.java` | — | `confirmAndSave()` 中 `datasourceId` 为 null 时 fallback 硬编码为 1 |
| `backend/src/main/resources/mapper/vector/MetaEmbeddingMapper.xml` | — | `searchSimilar` 未按 datasource 过滤向量结果 |
| `frontend/src/pages/admin/DDLUploadPage.tsx` | 有数据源选择器 UI，但执行路径未使用 | — |

---

## 现有相关文档

- `docs/issue/sqlguard-org-scope-risk-review-20260425_222403.md` — 已记录 SqlGuard 命名规则驱动的风险
- `docs/architecture/` — 架构文档中可能涉及多数据源的设计意图

---

## 结论

当前项目在 **多数据源/多表结构场景** 下存在两条断链：

1. **元数据路径（已通但有缝）**：DDL 能解析入库、metadata 能按 datasource 检索，但 RAG 的 glossary/example 跨数据源泄漏、方言硬编码；
2. **执行路径（未通）**：`QueryExecutorService` 到业务数据库之间缺少动态路由，`meta_datasource` 的连接信息完全未被使用。

两个问题叠加后，即使逐个修复，也会在修复问题一后暴露问题二的安全风险（跨租户数据泄漏）。建议 **两个问题同步修复**，并优先从元数据结构层面建立组织范围标记机制。
