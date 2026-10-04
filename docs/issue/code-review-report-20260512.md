# Text2Report 代码审查报告

> **审查日期**: 2026-05-12  
> **审查范围**: 全项目后端 + 前端 (排除 application-local.yml)  
> **审查视角**: 资深开发人员 — 安全、架构、代码质量、生产就绪度  
> **项目版本**: M11 (Code Complete)

---

## 审查摘要

| 严重级别 | 数量 | 说明 |
|----------|------|------|
| **P0 — 紧急/阻断** | 3 | 必须在上线前修复，存在直接安全威胁或数据泄露风险 |
| **P1 — 高危** | 5 | 上线前应修复，存在权限绕过、审计缺失或数据完整性风险 |
| **P2 — 中危** | 9 | 建议在 M12 修复，影响代码质量、可维护性或防御深度 |
| **P3 — 低危** | 8 | 可在后续迭代修复，属代码整洁度和性能优化 |

---

## P0 — 紧急/阻断（上线前必须修复）

### P0-1. 数据源密码使用 Base64 伪装加密，明文回退

**文件**: `backend/src/main/java/com/text2report/service/DataSourceService.java:177-186`

**问题**: `encryptPassword()` 方法使用 Base64 编码伪装为"加密"。Base64 是可逆编码而非加密，任何有数据库读权限的人（备份泄露、SQL 注入等）均可瞬间还原所有数据源密码。更严重的是，异常路径直接返回明文密码：

```java
private String encryptPassword(String password) {
    try {
        return Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
        return password; // ← 明文回退！
    }
}
```

同时，`@Value("${datasource.password.secret-key:default-secret}")` 字段是死代码，给人以"可配置加密"的错觉。

**影响**: `meta_datasource.password` 列中存储的所有数据库凭证均可被瞬间破解。违反项目自身不变量 "Only the query gateway may access databases"——当凭证本身不安全时，此不变量形同虚设。

**修复建议**:
1. 使用 AES-256-GCM 加密，密钥通过环境变量 / Vault / K8s Secret 注入
2. 移除明文回退路径，改为抛出异常
3. 移除 `secretKey` 的默认值 `default-secret`
4. 对已存储的 Base64 密码做一次性迁移脚本

---

### P0-2. 客户端可控的 X-Request-ID 导致日志注入与审计碰撞

**文件**: `backend/src/main/java/com/text2report/common/context/TraceFilter.java:31-37`

**问题**: TraceFilter 直接信任客户端发送的 `X-Request-ID` 头，不做任何校验：

```java
String requestId = request.getHeader(REQUEST_ID_HEADER);
if (requestId == null || requestId.isBlank()) {
    requestId = UUID.randomUUID().toString();
}
```

攻击者可以：
1. **日志注入**: 设置含换行符/ANSI 转义码的 `X-Request-ID`，伪造日志条目掩盖攻击痕迹
2. **审计碰撞**: `requestId` 是 `audit_query_logs` 的主键，攻击者可发送可预测/重复的 ID，导致 upsert 碰撞覆盖合法审计记录
3. **无长度/字符限制**: 可接受任意长度的头值

**修复建议**:
1. 始终由服务端生成 UUID 作为 requestId（作为审计主键）
2. 如需客户端追踪，接受独立的 `X-Correlation-ID` 头存入单独字段
3. 如必须接受客户端 ID，校验格式 `^[a-f0-9\-]{36}$`，最大长度 64 字符

---

### P0-3. application.yml 中硬编码 Vector 数据库密码和 IP

**文件**: `backend/src/main/resources/application.yml:45-47`

**问题**: 审查时，Vector 数据源配置的默认值包含真实密码和数据库地址。本文已移除原值，下面展示修复后的配置形式：

```yaml
vector:
  jdbc-url: ${VECTOR_DB_URL:jdbc:postgresql://localhost:5433/postgres}
  username: ${VECTOR_DB_USER:postgres}
  password: ${VECTOR_DB_PASSWORD}
```

原配置虽然使用了环境变量占位符，但仍将数据库地址和密码作为默认值提交到了 Git。清理当前配置后，还需要处理历史中的副本并更换已提交的凭据。

**修复建议**:
1. 移除所有密码默认值，改为 `${VECTOR_DB_PASSWORD}`（无默认值，启动时强制要求配置）
2. 移除 IP 地址默认值，改为 `${VECTOR_DB_HOST:localhost}`
3. 审查 Git 历史，确认密码是否已被泄露；若已泄露则更换密码

---

## P1 — 高危（上线前应修复）

### P1-1. MANAGER 角色被排除在 SQL 可见性之外 — 权限模型违规

**文件**:
- `backend/src/main/java/com/text2report/controller/QueryController.java:95-109`
- `frontend/src/components/query/SqlPreview.tsx`

**问题**: 后端 `maskSqlIfNeeded()` 仅允许 ADMIN 保留 SQL：

```java
if (result == null || "ADMIN".equalsIgnoreCase(TraceContext.getRole())) {
    return result; // SQL 保留
}
// ... 否则 sql = null
```

前端 `SqlPreview.tsx` 同样只检查 `isAdmin()`。但 AGENTS.md 明确规定 MANAGER 应可查看 SQL。

**修复建议**:
- 后端：增加 MANAGER 角色判断
- 前端：使用 `isManagerOrAbove()` 替代 `isAdmin()`

---

### P1-2. SqlMetadataValidator 错误消息泄露 Schema 枚举数据

**文件**: `backend/src/main/java/com/text2report/service/SqlMetadataValidator.java:233-237, 411-416`

**问题**: 当 LLM 生成的 SQL 引用不存在的表/列时，错误消息返回可用表名（最多 5 个）和可用列名（最多 10 个）：

```
"SQL 引用了未发布或不存在的数据表: xxx。已发布的可用表包括: fact_gross_profit_detail, dim_org, ..."
```

此错误消息通过 API 返回给 USER 角色。USER 角色攻击者可系统性探测无效表名/列名来枚举完整 Schema——这本应是 ADMIN/MANAGER 才有的能力。

**修复建议**:
1. 内部传递（LLM 重试上下文）：保留详细提示
2. API 响应：对 USER 角色返回通用消息 "SQL 引用了无效的元数据"
3. 在 `OrchestratorService.sanitizeException()` 中按角色过滤

---

### P1-3. AuditController 对 MANAGER 角色缺少组织范围过滤

**文件**: `backend/src/main/java/com/text2report/controller/AuditController.java:30, 47-55`

**问题**: `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")` 允许 MANAGER 访问所有审计日志，但查询未按 MANAGER 的组织范围过滤。按 AGENTS.md 权限模型，MANAGER 应只能看到其管辖组织范围内的数据。

**修复建议**: 确认 `AuditQueryService` 对 MANAGER 角色应用了组织范围过滤。如未实现，需补充。

---

### P1-4. DataSourceService — 手动 ID 生成的竞态条件

**文件**: `backend/src/main/java/com/text2report/service/DataSourceService.java:93-103`

**问题**: 使用 `SELECT MAX(id) + 1` 手动生成 ID，存在经典 TOCTOU 竞态条件：

```java
List<Object> maxIds = datasourceMapper.selectObjs(
    new LambdaQueryWrapper<MetaDatasource>()
        .select(MetaDatasource::getId)
        .orderByDesc(MetaDatasource::getId)
        .last("LIMIT 1")
);
ds.setId((Integer) maxIds.get(0) + 1);
```

并发请求可能产生主键冲突。

**修复建议**: 在迁移脚本中修复 PostgreSQL 序列：`SELECT setval('meta_datasource_id_seq', (SELECT MAX(id) FROM meta_datasource))`，或使用 `INSERT ... RETURNING id`。

---

### P1-5. DataSourceController 缺少审计日志

**文件**: `backend/src/main/java/com/text2report/controller/DataSourceController.java:56-80`

**问题**: 数据源的创建、更新、删除操作没有审计记录。ADMIN 可以添加带有可写连接串的数据源（绕过只读 HikariCP 约束），或删除关键数据源，却没有任何谁、何时、做了什么的记录。违反 AGENTS.md "Every request must be auditable and permission-aware"。

**修复建议**: 为数据源 CRUD 操作添加审计日志，记录操作者、操作类型、变更内容。

---

## P2 — 中危（建议 M12 修复）

### P2-1. DDLParserService — 无 DDL 内容大小限制

**文件**: `backend/src/main/java/com/text2report/service/DDLParserService.java:69-96`

**问题**: `parseDDL` 方法接受任意大小的 `rawDDL`。恶意 ADMIN 可上传数百 MB 的 DDL 字符串，导致内存压力和潜在的 ReDoS（如 `splitColumns` 方法中的复杂正则）。

**修复建议**: 在 Controller 或 Service 入口强制最大 DDL 大小限制（如 100KB）。

---

### P2-2. DDLParserService — commitDDL 绕过 Parse-Confirm 工作流

**文件**: `backend/src/main/java/com/text2report/service/DDLParserService.java:218-237`

**问题**: `commitDDL()` 方法跳过人工审核步骤，始终使用 `"system"` 作为上传用户。违反 AGENTS.md Rule 7 "DDL upload is the metadata entry point"和 parse → confirm 管道设计。

**修复建议**: 废弃或移除 `commitDDL`。所有 DDL 导入应通过 parse → preview → confirm 管道，附带真实用户身份。

---

### P2-3. DDLParserService — 循环内编译正则表达式

**文件**: `backend/src/main/java/com/text2report/service/DDLParserService.java:289-291, 421, 445`

**问题**: `extractLineComments`、`parseAndAddColumn` 等方法在循环体内调用 `Pattern.compile()`。对于含数百列的大 DDL 文件，造成不必要的对象分配和正则重编译。

**修复建议**: 将这些正则提取为 `private static final Pattern` 常量。

---

### P2-4. AuditService — upsert 参数列表达 23 个位置参数

**文件**: `backend/src/main/java/com/text2report/service/AuditService.java:39-44, 220-225`

**问题**: `auditQueryLogMapper.upsert()` 有 23 个位置参数，大量调用传入 `null` 填充不相关字段。单个参数偏移就会导致审计记录静默损坏（如把 feedback 值写入 sql 列）。

**修复建议**: 创建类型化的 `AuditLogEntry` Builder/DTO，改 Mapper 接受该 DTO，消除位置错位风险。

---

### P2-5. 前端 role 类型转换缺少运行时校验

**文件**:
- `frontend/src/services/authApi.ts:9-13`
- `frontend/src/store/useAuthStore.ts:41`

**问题**: 使用 TypeScript `as` 断言将 role 字符串强转为联合类型，无运行时校验。`authApi.ts` 的 fallback 逻辑中，如果服务端返回非预期角色，MANAGER 可能被降级为 USER 或错误提升为 ADMIN。

**修复建议**: 添加严格的运行时角色校验和 allowlist，遇到非预期角色时记录警告。

---

### P2-6. Vite loadEnv 加载所有环境变量

**文件**: `frontend/vite.config.ts:6`

**问题**: `loadEnv(mode, process.cwd(), '')` 使用空前缀，加载所有系统环境变量到构建上下文。任何对非 `VITE_` 变量的意外引用都可能在构建产物中泄露密钥。

**修复建议**: 改为 `loadEnv(mode, process.cwd(), 'VITE_')`（安全默认值）。

---

### P2-7. 前端 queryApi.ts — requestId 未经校验拼入 URL 路径

**文件**: `frontend/src/services/queryApi.ts:43, 51, 55`

**问题**: `requestId` 参数直接拼入 URL 路径，未经校验或编码：

```typescript
apiPost(`/v1/query/${requestId}/feedback`, { feedback })
```

恶意 `requestId` 含 `/`、`?`、`../` 等字符可能操纵 URL 路径。

**修复建议**: 校验 `requestId` 格式（UUID 模式）后再拼入，或使用 `encodeURIComponent()`。

---

### P2-8. useAuthStore — 孤立的 localStorage 引用

**文件**: `frontend/src/store/useAuthStore.ts:57`

**问题**: `clearAuth()` 调用 `localStorage.removeItem('auth-storage')`，但 Store 未使用 Zustand `persist` 中间件，`auth-storage` 从未被写入。这是遗留代码或未完成的 persist 集成。当前依赖 HTTP-only Cookie + `/v1/auth/me` 重建状态（实际更安全），但与 AGENTS.md 文档 "useAuthStore (persist)" 矛盾。

**修复建议**: 要么添加 persist 中间件（不持久化敏感字段），要么移除孤立的 `localStorage.removeItem` 调用并更新文档。

---

### P2-9. 前端缺少 404 兜底路由

**文件**: `frontend/src/app/routes.tsx`

**问题**: 路由定义中没有 `path: '*'` 兜底路由。用户访问未定义路径时看到空白页面，无任何反馈。

**修复建议**: 添加 `path: '*'` 路由，渲染 404 页面或重定向到 `/query`。

---

## P3 — 低危（后续迭代修复）

### P3-1. DDLParserService — 死代码 DDLCommitResult2

**文件**: `backend/src/main/java/com/text2report/service/DDLParserService.java:528-533`

**问题**: `DDLCommitResult2` 内部类从未被引用，是重构遗留物。

**修复建议**: 删除。

---

### P3-2. DDLParserService — errorMessage 字段被挪用存储序列化数据

**文件**: `backend/src/main/java/com/text2report/service/DDLParserService.java:203-206`

**问题**: `upload.setErrorMessage(objectMapper.writeValueAsString(request.getTables()))` 将解析后的表数据存入 `errorMessage` 列，语义错误且 catch 块静默忽略失败。

**修复建议**: 使用专用的 `parsed_content` JSON 列替代。

---

### P3-3. DemoUsersProperties 缺少 Profile 守卫

**文件**: `backend/src/main/java/com/text2report/config/DemoUsersProperties.java`

**问题**: Demo 用户配置未加 `@Profile("demo")` 或 `@ConditionalOnProperty` 守卫，可能在生产环境意外激活。

**修复建议**: 添加条件注解，确保仅在开发/演示环境启用。

---

### P3-4. JwtTokenResolver — 无 Token 长度/格式校验

**文件**: `backend/src/main/java/com/text2report/security/JwtTokenResolver.java:36-43`

**问题**: 解析出的 JWT 字符串未做长度和基本格式校验（三段式结构），畸形或超长 token 可能导致下游 JWT 解析器过度内存分配。

**修复建议**: 添加轻量检查：`token.length() <= 4096 && token.split("\\.").length == 3`。

---

### P3-5. 前端 — ResultChart.tsx 缺少 memoization

**文件**: `frontend/src/components/query/ResultChart.tsx`

**问题**: `isMetricColumn()` 对每列扫描所有行（O(rows × columns)），chart option 对象每次渲染重建，均未用 `useMemo()` 包装。

**修复建议**: 对 `normalizedColumns`、`metricColumns`、`labelColumn`、`getOption` 结果使用 `useMemo`。

---

### P3-6. 前端 — Admin 页面未使用懒加载

**文件**: `frontend/src/app/routes.tsx:7-22`

**问题**: 11 个 Admin 页面 + 3 个 Query 页面全部静态导入，增大首屏 JS 体积。

**修复建议**: 使用 `React.lazy()` + `Suspense` 实现代码分割。

---

### P3-7. API 成功码约定不一致

**文件**: `frontend/src/services/request.ts:85`

**问题**: 响应拦截器检查三种成功码：`res.code === 'SUCCESS' || res.code === 0 || res.code === 200`。后端应统一为一种格式。

**修复建议**: 后端统一返回 `code: 'SUCCESS'`，前端简化判断。

---

### P3-8. RateLimitFilter — X-Forwarded-For IP 信任问题

**文件**: `backend/src/main/java/com/text2report/config/RateLimitFilter.java:128-133`

**问题**: `resolveClientIp()` 直接信任 `X-Forwarded-For` 头第一个值。在非反向代理部署下，客户端可伪造此头绕过速率限制。注释提到 "Redis unavailable - fail open" 也有隐患——Redis 不可用时速率限制完全失效。

**修复建议**: 仅在确认有受信反向代理时才解析 `X-Forwarded-For`，或配置 `app.trusted-proxies`。Redis 不可用时应考虑 fail-closed（拒绝请求）而非 fail-open。

---

## 架构级观察（非具体 Bug，但值得关注）

### A1. OrchestratorService 构造函数过度重载

**文件**: `backend/src/main/java/com/text2report/service/OrchestratorService.java:58-134`

存在 4 个构造函数用于单元测试兼容。建议使用 `@InjectMocks` + Mockito 的标准方式替代手写构造函数兼容，或使用 Spring 的 `@TestConfiguration`。

### A2. OpenAILLMService.detectIntent() 纯关键词匹配

**文件**: `backend/src/main/java/com/text2report/service/llm/OpenAILLMService.java:161-180`

代码注释中已有 TODO："Could we use an LLM directly for semantic understanding instead?"。当前关键词匹配过于僵化，无法覆盖同义词和变体表达。

### A3. LLMAsyncExecutor 每次请求创建新 OkHttpClient

**文件**: `backend/src/main/java/com/text2report/service/llm/LLMAsyncExecutor.java:59-63`

每次调用都 `new OkHttpClient.Builder()` 创建新实例，浪费连接池资源。OkHttpClient 应为单例或 Spring Bean。

### A4. BusinessDataSourceConfig 未显式设置 readOnly

**文件**: `backend/src/main/java/com/text2report/config/BusinessDataSourceConfig.java:22-25`

虽然 `application.yml` 中 HikariCP 配置了 `read-only: true`，但 `BusinessDataSourceConfig` 的 Java Bean 配置未显式设置 `dataSource.setReadOnly(true)`。如果有人修改了 YAML 中的 HikariCP 配置或使用不同配置源，只读保护可能失效。建议在 Java 代码中也显式设置。

---

## 正面观察

项目在多个方面展现了良好的安全实践：

1. **HTTP-only Cookie + CSRF 双重防护** — 不在 localStorage 存储 Token，XSS 风险更低
2. **JSqlParser AST 级 SQL 校验** — 非正则级别，覆盖面更广
3. **服务端 SQL 屏蔽** — 非 ADMIN 角色在 API 响应中看不到 SQL
4. **ResultProfile DTO 隔离** — LLM 只看元数据概要，不接触业务数据行
5. **Redis JTI 会话校验** — 单设备登录 + 黑名单机制
6. **BCrypt 密码哈希** — 不存储可逆密码
7. **CSP / 安全响应头** — SecurityConfig 中配置了完整的 CSP、X-Frame-Options、Permissions-Policy 等
8. **前端无 dangerouslySetInnerHTML** — XSS 攻击面更小

---

## 修复优先级路线图

```
M11 结束前（阻断上线）:
├── P0-1: 数据源密码加密改 AES-256-GCM + 移除明文回退
├── P0-2: 服务端生成 requestId + 客户端 ID 严格校验
└── P0-3: 移除 application.yml 中的硬编码密码和 IP 默认值

M12 迭代:
├── P1-1: 修复 MANAGER SQL 可见性
├── P1-2: 按 role 过滤错误消息详细程度
├── P1-3: 审计日志加 MANAGER 组织范围过滤
├── P1-4: 修复数据源 ID 竞态条件
├── P1-5: 数据源 CRUD 加审计日志
├── P2-1~P2-9: 中危项逐步修复
└── A3: OkHttpClient 改为单例
```
