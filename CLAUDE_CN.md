# CLAUDE_CN.md（中文版）

## 项目简介

Text2Report 是一个企业级**自然语言驱动的报表平台**。
项目目标不是做一个通用聊天机器人，而是将受控的业务问题转化为安全的 SQL，通过受治理的只读路径执行，并将结果渲染为图表、表格、摘要和可下载报表。

本项目始终遵守以下四条不可违反的原则：

1. **LLM 只能看到元数据，绝不接触真实业务数据。**
2. **所有 SQL 执行必须经过 `SqlGuardService`（JSqlParser AST 校验），不可绕过。**
3. **生产环境只允许 `SELECT` / `WITH...SELECT`，禁止任何写操作。**
4. **每次请求必须可审计（写入 `audit_query_logs`）。**

---

## 当前阶段 — M11 后 M10 打磨（代码完成）

### 技术栈

| 层次 | 技术 |
|------|------|
| 运行时 | Java 21 + Spring Boot 3.2.x |
| 架构模式 | 单体 Spring Boot（按模块分包，可平滑拆分微服务） |
| ORM | MyBatis-Plus 3.5.x |
| SQL 安全 | JSqlParser 4.x（AST 解析，强制要求） |
| 认证 | Spring Security + JWT（jjwt 0.12.x）+ BCrypt |
| 业务数据库 | PostgreSQL — `text2report`（只读，HikariCP read-only=true） |
| 元数据库 | PostgreSQL — `text2report-meta`（读写） |
| 向量数据库 | PostgreSQL + pgvector — `meta_embedding`（HNSW cosine 索引，1024维） |
| 缓存 | Redis 7（Lettuce 连接池） |
| LLM | DeepSeek API（deepseek-chat），通过 `LLMService` 接口隔离（预留本地 Qwen） |
| Embedding | BGE-M3 via Ollama（Spring AI 接入，1024维） |
| 前端 | React 18 + TypeScript + Ant Design 5.x + Vite + Zustand + ECharts + MSW |

### 阿里云基础设施

| 容器名 | 镜像 | 端口 | 用途 |
|--------|------|------|------|
| `postgres` | postgres:15 | 5432 | 承载 PostgreSQL 数据库 |
| `redis-prod` | redis:7 | 6379 | 缓存 / 会话 / 限流 |
| `ollama` | ollama/ollama | 11434 | BGE-M3 embedding 推理 |

> 服务器 IP、端口、密码等详细信息 → `docs/env-infrastructure.md`（不提交 Git）

### 三数据源（非双数据源）

- `text2report` → 只读业务数据库（HikariCP `read-only=true`）
- `text2report-meta` → 元数据库、审计库、用户库、组织库
- `vectorDataSource` → pgvector 向量存储（HNSW cosine, 1024维）

---

## 配置文件结构

```
backend/src/main/resources/
├── application.yml                    ← 主配置（提交 Git，不含任何密码）
├── application-local.yml              ← 本地开发配置（不提交 Git，含真实密码）
├── application-prod.yml               ← 生产配置（不提交 Git，含真实密码）
└── application-local.yml.template     ← 模板文件（提交 Git，供新开发者参考）
```

### 启动方式

```bash
# 本地开发（IDEA 中设置 Active profiles = local）
mvn spring-boot:run -Dspring-boot.run.profiles=local

# 生产部署
java -jar text2report-backend.jar --spring.profiles.active=prod \
  --spring.config.additional-location=/opt/text2report/config/
```

### LLM 配置详细说明

所有 LLM 凭据（API Key 等）**只存放在 profile 文件中**，绝不写入 `application.yml`。

当前使用 DeepSeek API（兼容 OpenAI 协议），通过 `LLMService` 接口隔离。切换供应商：
- **OpenAI 兼容**（DeepSeek、Qwen、豆包 Ark）：仅需修改 YAML 配置
- **非兼容协议**（Claude、Gemini）：需实现新的 `LLMService` 类 + 配置

### Redis Key 命名规范

| 使用场景 | Key 格式 | TTL |
|----------|---------|-----|
| 查询结果缓存 | `t2r:cache:query:{md5(question+orgId)}` | 1800s |
| 用户会话（JTI） | `t2r:session:{userId}` | 86400s |
| JWT 黑名单 | `t2r:blacklist:{token}` | token 过期时间 |
| 限流计数器 | `t2r:ratelimit:{userId}` | 60s |

---

## 业务数据库 Schema — `text2report`

> **仅供结构参考，数据库中已执行建表语句，请勿重复运行。**
> 完整 DDL → `sql/schema-business.sql`

| 表名 | 说明 |
|------|------|
| `dim_org` | 组织维表：母公司 → 3 子公司 → 12 部门 |
| `dim_product` | 产品维表：5 种产品 |
| `dim_channel` | 渠道维表：4 种渠道 |
| `fact_gross_profit_detail` | 毛利事实表：月 × 部门 × 产品 × 渠道 |

数据范围：2024-01 至 2025-12，含 3 条异常数据用于边界测试。

---

## 元数据库 Schema — `text2report-meta`

> **仅供结构参考，请勿重复运行 DDL。**
> 完整 DDL → `sql/schema-meta.sql`

| 表名 | 用途 |
|------|------|
| `org_tree` | 组织机构层级（权限范围控制） |
| `dim_org` | 组织维表（从业务库同步） |
| `sys_user` | 系统用户（BCrypt 密码，角色 ADMIN/MANAGER/USER） |
| `sys_user_org` | 用户-组织权限关联（scope: SELF/CHILDREN/ALL） |
| `meta_datasource` | 数据源注册表 |
| `meta_table` | 表级元数据 |
| `meta_column` | 字段级元数据（口径说明、维度/度量标记） |
| `meta_glossary` | 业务术语词典（术语→SQL片段） |
| `meta_join_edge` | 表间 JOIN 关系图 |
| `meta_sql_example` | Few-shot 问答示例对 |
| `meta_ddl_upload` | DDL 上传审计记录 |
| `meta_embedding` | 向量存储（pgvector, 1024维, HNSW cosine） |
| `audit_query_logs` | 每次查询的完整审计日志 |

---

## 后端模块结构

```
backend/src/main/java/com/text2report/
├── config/                              ← 13 个配置类
│   ├── BusinessDataSourceConfig.java    ← 只读业务数据源（JdbcTemplate）
│   ├── MetaDataSourceConfig.java        ← 元数据源（MyBatis-Plus）
│   ├── VectorDataSourceConfig.java      ← pgvector 数据源
│   ├── SecurityConfig.java              ← Spring Security + JWT + CORS + RBAC
│   ├── AsyncConfig.java                 ← @EnableAsync + 3 线程池
│   ├── LLMConfig.java / RagConfig.java  ← LLM 与 RAG 配置
│   └── RateLimitFilter.java             ← Redis Sorted Set 滑动窗口限流
├── controller/                          ← 9 个控制器
│   ├── QueryController.java             ← 查询 / 历史 / 收藏 / 反馈
│   ├── MetadataController.java          ← DDL 解析/确认 + 表/列/术语/JOIN/示例 CRUD
│   ├── OrgController.java               ← 组织树 / 子树 / 路径 / 用户关联 / 同步
│   ├── AuthController.java              ← 登录 / 登出 / 当前用户 / 刷新 token
│   ├── UserController.java              ← 用户 CRUD / 启停 / 密码 / 组织授权
│   ├── EmbeddingController.java         ← 向量同步 / 搜索 / 统计
│   ├── AuditController.java             ← 审计日志分页 / 详情 / 统计
│   ├── DataSourceController.java        ← 数据源 CRUD
│   └── HealthController.java            ← 健康检查
├── service/                             ← 17 个服务
│   ├── OrchestratorService.java         ← 核心查询编排（意图→RAG→LLM→Guard→执行→审计）
│   ├── SqlGuardService.java             ← JSqlParser AST 校验（validate + validateOrgScope + LIMIT 注入）
│   ├── QueryExecutorService.java        ← PreparedStatement 执行 + 中文列名补全
│   ├── MetadataService.java             ← 混合检索（向量优先 + 关键词兜底）
│   ├── EmbeddingService.java            ← BGE-M3 向量化与同步
│   ├── OrgService.java                  ← 组织树 CRUD + 递归子树 + 业务库同步
│   ├── UserService.java                 ← 用户 CRUD + RBAC + 组织权限解析
│   ├── AuditService.java                ← @Async 异步 upsert audit_query_logs
│   ├── PromptBuilder.java               ← 加载 /prompts 模板 + 占位符填充
│   └── llm/                             ← LLMService 接口 + OpenAILLMService + LocalLLMService + BgeEmbeddingProvider
├── domain/                              ← 实体 + Mapper（business / meta / vector 三个包）
├── security/                            ← JwtUtil + JwtAuthFilter + JwtConstants
└── common/                              ← ApiResult / BusinessException / TraceContext / TraceFilter 等
```

---

## 核心设计规则

### 规则一：只向 LLM 传元数据

Prompt 中**可以包含**：表名、字段名、字段注释、业务术语定义、JOIN 关系、示例 SQL、PostgreSQL 方言提示、组织上下文（仅 ID 和名称）。

Prompt 中**绝对不能包含**：真实查询结果行、生产业务数据、PII 个人信息、财务数字、数据库连接凭据。

**实现方式**：`OrchestratorService` 传给 `LLMService.summarizeResults()` 的是 `ResultProfile`（rowCount, columnNames, hasData），而非原始数据行。

### 规则二：SqlGuardService 不可绕过

所有 SQL 执行前必须经过 `SqlGuardService`。两个检查点：`validate()`（语句类型/系统schema/危险函数/SELECT */LIMIT注入）+ `validateOrgScope()`（org_id 范围校验）。

### 规则三：只读保障双重机制

连接池层：`hikari.read-only: true` + AST 层：非 SELECT 拒绝。

### 规则四~八同上（接口驱动、结构化输出、模板文件化、DDL 入口、审计全覆盖）

---

## SQL 安全策略

### SqlGuardService 拦截项
- `INSERT/UPDATE/DELETE/MERGE/ALTER/DROP/TRUNCATE` — AST 级拒绝
- `GRANT/REVOKE/EXECUTE/CALL` — 拒绝
- 系统 catalog（`pg_catalog`、`information_schema`）— 拒绝
- 危险函数（`xp_cmdshell`、`sp_executesql` 等）— 拒绝
- `SELECT *` — 可配置拒绝
- SQL 长度超过 10000 字符 — 拒绝

### SqlGuardService 自动注入
- 无 LIMIT → 注入 `LIMIT 1000`
- 组织范围校验 → 检查 `org_id IN (allowedIds)`
- JDBC queryTimeout → 30s

---

## 权限模型

| 角色 | 权限范围 |
|------|---------|
| **ADMIN** | 全部：元数据 CRUD、用户管理、embedding、审计、数据源、组织同步 |
| **MANAGER** | 管理页面（组织树/用户/审计/Schema图）、查询域、SQL 可见 |
| **USER** | 仅查询域，SQL 字段脱敏 |

组织范围控制：`sys_user_org` 表 + `OrgService.resolveSubtreeIds()` 递归展开 → 交集 → `SqlGuardService.validateOrgScope()` AST 级强制执行。
单设备登录：Redis 会话 JTI 校验。

---

## RAG 向量检索（M10）

**写入**：元数据变更 → `EmbeddingService.embedAndStoreAsync(@Async)` → BGE-M3（Ollama）→ pgvector upsert（HNSW cosine）

**查询**：用户问题 → `MetadataService.retrieveForPrompt()` → 向量相似度检索（3 类来源）+ 关键词兜底 → `MetadataContext`

| 来源 | TopK | 相似度阈值 |
|------|------|-----------|
| sql_example | 5 | 0.65 |
| glossary | 5 | 0.60 |
| table_description | 3 | 0.55 |

---

## 重试策略

| 失败原因 | 是否重试 |
|----------|----------|
| 未知字段名 / 表名 | 是 |
| JOIN 歧义 / 语法错误 | 是 |
| Guard 拦截（可修复类错误） | 是（修复后重新过 Guard） |
| Guard 拦截（DROP/INSERT/系统表等） | 否 |
| 权限拒绝 / 不支持的意图 | 否 |

默认最多 **2 次**重试。

---

## 反模式（禁止做的事）

- 将 `text2report` 查询结果行传给 LLM（使用 `ResultProfile` DTO 替代）
- 在 `LLMService` 或 Controller 中直接访问任何数据库
- 在前端硬编码指标计算公式或业务逻辑
- 在 Java 源码中内联存储 Prompt 模板（必须在 `/prompts/` 下）
- 仅用正则表达式做 SQL 安全校验（JSqlParser AST 是强制要求）
- 绕过 `LLMService` 接口直接调用 LLM SDK
- 将 LLM API Key 写入 `application.yml`（只能在 profile 文件中）
- 向 `text2report` 业务数据库执行任何写操作

---

## 项目目录结构

```
text2report/
├── CLAUDE.md / CLAUDE_CN.md / AGENTS.md / README.md
├── frontend/                            ← React 18 + TS + Ant Design 5
├── backend/                             ← Spring Boot 3.2 + Java 21
├── prompts/                             ← sql_generation_v1.txt / sql_repair_v1.txt / result_summary_v1.txt
├── sql/                                 ← schema-business.sql / schema-meta.sql
└── docs/                                ← STATUS.md / api-contract.md / architecture/ / milestones/ / evaluation/ / testing/
```

## 质量指标

| 指标 | 数值 |
|------|------|
| 后端测试用例 | 221（mvn test，100% 通过） |
| 前端 TypeScript 编译 | 0 error（tsc --noEmit） |
| 前端生产构建 | 成功（npm run build） |
| 已知 P0 | 4 个 |
| 已知 P1 | 10 个 |

## 里程碑历史

| 里程碑 | 名称 | 日期 | 状态 |
|--------|------|------|------|
| M0–M3 | 前端骨架 / 管理页面 / 查询页面 / MSW Mock | — | ✅ 已完成 |
| M4 | 后端骨架 + 公共模块 | 2026-04-10 | ✅ 已完成 |
| M5 | 组织 + 元数据服务 | 2026-04-11 | ✅ 已完成 |
| M6 | LLM + SQL Guard + Executor 端到端编排 | 2026-04-11 | ✅ 已完成 |
| M7 | 缓存 + 历史/反馈 API | 2026-04-11 | ✅ 已完成 |
| M8 | 网关 + 认证 + 审计增强 | 2026-04-12 | ✅ 已完成 |
| M9 | 前后端联调 + 端到端验收 | 2026-04-12 | 🟡 代码完成，待真实环境验收 |
| M10 | 权限重构 + RAG 向量检索 | 2026-04-16 | 🟡 代码完成，待真实环境验收 |
| M11 | 后 M10 打磨 + 全栈代码评审 | 2026-04-27 | 🟡 代码完成，P0 修复待执行 |

### 当前优先级

1. **P0 修复** — 代码评审报告中 4 项
2. **P1 修复** — 10 项
3. **真实环境端到端验收** — 权限链路、RAG 检索、异步向量化、组织同步
4. **M12 规划** — 元数据驱动的 org scope 控制升级

---

## 变更记录

### 2026-04-30：全量更新至 M11 状态

更新内容：三数据源（含 pgvector）、DeepSeek LLM、BGE-M3 embedding、RAG 管线、完整 RBAC（ADMIN/MANAGER/USER）、9 个控制器、17 个服务、221 个测试、11 个前端管理页面、Redis 会话 JTI 单设备登录、SqlGuard 组织范围校验、完整里程碑历史。

### 2026-04-10：M4-Hotfix 安全与质量修复

P0 修复 5 项（LLM 隔离 / SqlGuard AST 重写 / JWT 黑名单 / TraceContext / @EnableAsync），P1 修复 7 项。33 个测试用例。
