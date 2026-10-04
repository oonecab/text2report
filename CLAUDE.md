# CLAUDE.md

## Project

Text2Report is an enterprise-grade **natural-language-to-report** platform.
Its goal is not to be a general chatbot. Its goal is to convert controlled business questions into safe SQL, execute them through a governed read-only path, and render the results as charts, tables, summaries, and downloadable reports.

This repository must always preserve four invariants:

1. **LLM sees metadata only — never real business data.**
2. **All SQL execution must pass through `SqlGuardService` (JSqlParser AST validation). No exceptions.**
3. **Only `SELECT` / `WITH...SELECT` is permitted in production. All write operations are forbidden.**
4. **Every request must be auditable (`audit_query_logs`).**

---

## Current Scope — M11 Post-M10 Refinement (Code Complete)

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21 + Spring Boot 3.2.x |
| Architecture | Single Spring Boot monolith (modular packages, microservice-ready) |
| ORM | MyBatis-Plus 3.5.x |
| SQL Safety | JSqlParser 4.x (AST-based, mandatory) |
| Auth | Spring Security + JWT (jjwt 0.12.x) + BCrypt |
| Business DB | PostgreSQL — `text2report` (read-only, HikariCP read-only=true) |
| Metadata DB | PostgreSQL — `text2report-meta` (read-write) |
| Vector DB | PostgreSQL + pgvector — `meta_embedding` (HNSW cosine index, 1024-dim) |
| Cache | Redis 7 (Lettuce) |
| LLM | DeepSeek API (deepseek-chat) via `LLMService` interface (local Qwen reserved) |
| Embedding | BGE-M3 via Ollama (Spring AI, 1024-dim) |
| Frontend | React 18 + TypeScript + Ant Design 5.x + Vite + Zustand + ECharts + MSW |

### Alibaba Cloud Infrastructure

| Container | Image | Port | Purpose |
|-----------|-------|------|---------|
| `postgres` | postgres:15 | 5432 | Hosts PostgreSQL databases |
| `redis-prod` | redis:7 | 6379 | Cache / session / rate-limiting |
| `ollama` | ollama/ollama | 11434 | BGE-M3 embedding inference |

> Full server details, IPs, and passwords → `docs/env-infrastructure.md` (not in Git)

### Three data sources (not two)

- `text2report` → read-only business database (HikariCP `read-only=true`)
- `text2report-meta` → metadata, audit, user, and org database
- `vectorDataSource` → pgvector for embedding storage (HNSW cosine, 1024-dim)

---

## Configuration File Structure

```
backend/src/main/resources/
├── application.yml                    ← Main config (committed to Git, no secrets)
├── application-local.yml              ← Local dev overrides (NOT in Git, has real passwords)
├── application-prod.yml               ← Production overrides (NOT in Git, has real passwords)
└── application-local.yml.template     ← Template for new developers (committed to Git)
```

### Config Layer Priority

```
application.yml  (base, committed)
    ↓ overridden by
application-local.yml  (local dev, gitignored)
    or
application-prod.yml   (production, gitignored)
```

### Activation

```bash
# Local development (IDEA: Active profiles = local)
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Production
java -jar text2report-backend.jar --spring.profiles.active=prod \
  --spring.config.additional-location=/opt/text2report/config/
```

### application.yml — Key Sections

```yaml
spring:
  profiles:
    active: local

  datasource:
    business:                        # text2report (READ-ONLY)
      hikari:
        read-only: true              # ENFORCED at connection pool level
        maximum-pool-size: 5
        pool-name: BusinessPool
    meta:                            # text2report-meta (read-write)
      hikari:
        read-only: false
        maximum-pool-size: 5
        pool-name: MetaPool
    vector:                          # pgvector (embedding storage)
      hikari:
        read-only: false
        maximum-pool-size: 5
        pool-name: VectorPool

  ai:
    ollama:
      base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
      embedding:
        model: ${OLLAMA_EMBEDDING_MODEL:bge-m3}

mybatis-plus:
  mapper-locations: classpath:mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true

llm:
  provider: ${LLM_PROVIDER:openai}
  embedding-model: ${EMBEDDING_MODEL:bge-m3}
  embedding-dimensions: ${EMBEDDING_DIMS:1024}
  openai:
    api-key: ${DEEPSEEK_API_KEY:}
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}
    model: ${DEEPSEEK_MODEL:deepseek-chat}
    temperature: 0.1
    max-tokens: 2000
    timeout-seconds: 120
    max-concurrent-requests: 5
    thread-pool-size: 4
  local:
    base-url: ${LOCAL_LLM_URL:http://localhost:8000/v1}
    model: ${LOCAL_LLM_MODEL:qwen2.5-coder-32b}

sql-guard:
  block-select-star: true
  default-limit: 1000
  query-timeout-seconds: 30

jwt:
  secret: ${JWT_SECRET}
  expiration-seconds: ${JWT_EXPIRATION_SECONDS:86400}

cache:
  query-result-ttl: 1800
  metadata-ttl: 1800
  session-ttl: 86400
  rate-limit-window: 60
  rate-limit-max-requests: 20

app:
  prompt-dir: ${PROMPT_DIR:../prompts/}
  query:
    max-retries: ${QUERY_MAX_RETRIES:2}
    default-limit: ${QUERY_DEFAULT_LIMIT:1000}
  rate-limit:
    enabled: ${RATE_LIMIT_ENABLED:true}
    anonymous-per-minute: ${RATE_LIMIT_ANONYMOUS_PER_MINUTE:10}
    user-per-minute: ${RATE_LIMIT_USER_PER_MINUTE:30}
    admin-per-minute: ${RATE_LIMIT_ADMIN_PER_MINUTE:60}
    query-per-minute: ${RATE_LIMIT_QUERY_PER_MINUTE:10}
  rag:
    similarity-threshold-example: ${RAG_SIMILARITY_THRESHOLD_EXAMPLE:0.65}
    similarity-threshold-glossary: ${RAG_SIMILARITY_THRESHOLD_GLOSSARY:0.60}
    similarity-threshold-table: ${RAG_SIMILARITY_THRESHOLD_TABLE:0.55}
    top-k-example: ${RAG_TOP_K_EXAMPLE:5}
    top-k-glossary: ${RAG_TOP_K_GLOSSARY:5}
    top-k-table: ${RAG_TOP_K_TABLE:3}
    fallback-to-keyword: ${RAG_FALLBACK_TO_KEYWORD:true}
```

### LLM Configuration Detail

All LLM credentials are stored in `application-local.yml` (local) or `application-prod.yml` (production) — **never in `application.yml`**.

```yaml
# In application-local.yml (not committed to Git)
llm:
  provider: openai                   # uses OpenAI-compatible protocol
  openai:
    api-key: sk-YOUR_DEEPSEEK_KEY
    base-url: https://api.deepseek.com/v1
    model: deepseek-chat
```

The `LLMService` interface isolates all business logic from the LLM protocol. Switching providers:
- **OpenAI-compatible** (DeepSeek, Qwen, doubao Ark): only YAML config change needed
- **Non-compatible** (Anthropic, Gemini): implement a new `LLMService` class + config

### Redis Key Naming Convention

| Scenario | Key Pattern | TTL |
|----------|-------------|-----|
| Query result cache | `t2r:cache:query:{md5(question+orgId)}` | 1800s |
| User session (JTI) | `t2r:session:{userId}` | 86400s |
| JWT blacklist | `t2r:blacklist:{token}` | token expiration |
| Rate-limit counter | `t2r:ratelimit:{userId}` | 60s |

---

## Business Database Schema — `text2report`

> **Reference only. Tables already exist. Do not re-execute DDL.**
> Full DDL → `sql/schema-business.sql`

### Tables

| Table | Description |
|-------|-------------|
| `dim_org` | Organization: parent company → 3 subsidiaries → 12 departments |
| `dim_product` | 5 products across manufacturing and service categories |
| `dim_channel` | 4 sales channels: direct, dealer, e-commerce, key account |
| `fact_gross_profit_detail` | Core fact table: monthly revenue/cost by org × product × channel |

### Organization Structure

```
母公司 (id=1)
├── 子公司A (id=2)  →  销售部(11), 生产部(12), 市场部(13), 综合部(14)
├── 子公司B (id=3)  →  销售部(21), 生产部(22), 市场部(23), 综合部(24)
└── 子公司C (id=4)  →  销售部(31), 生产部(32), 市场部(33), 综合部(34)
```

### Key Fields

- `period_month` — `CHAR(6)`, format `YYYYMM` (e.g. `202401`)
- `gross_profit` — computed column: `revenue - cost` (STORED)
- `org_level` — `1`=parent, `2`=subsidiary, `3`=department
- Data range: 2024-01 to 2025-12. Includes 3 anomaly rows for edge-case testing.

---

## Metadata Database Schema — `text2report-meta`

> **Reference only. Tables already exist. Do not re-execute DDL.**
> Full DDL → `sql/schema-meta.sql`

| Table | Purpose |
|-------|---------|
| `org_tree` | Organization hierarchy for permission scoping |
| `dim_org` | Organization dimension (synced from business DB) |
| `sys_user` | System users (username, BCrypt password, role: ADMIN/MANAGER/USER) |
| `sys_user_org` | User-to-organization permission grants (scope: SELF/CHILDREN/ALL) |
| `meta_datasource` | Data source registry |
| `meta_table` | Table-level metadata: label, description |
| `meta_column` | Column-level: label, type, description, dimension/measure flags |
| `meta_glossary` | Business glossary: term → SQL snippet |
| `meta_join_edge` | JOIN relationship graph |
| `meta_sql_example` | Few-shot question → SQL pairs |
| `meta_ddl_upload` | DDL upload audit trail |
| `meta_embedding` | Vector store (pgvector, 1024-dim, HNSW cosine) |
| `audit_query_logs` | Full audit log for every request |

---

## Backend Module Structure

```
backend/src/main/java/com/text2report/
├── config/
│   ├── BusinessDataSourceConfig.java    ← read-only datasource bean (JdbcTemplate)
│   ├── MetaDataSourceConfig.java        ← metadata datasource bean (MyBatis-Plus)
│   ├── VectorDataSourceConfig.java      ← pgvector datasource bean (MyBatis-Plus)
│   ├── BusinessJdbcTemplateConfig.java  ← NamedParameterJdbcTemplate for business DB
│   ├── SecurityConfig.java              ← Spring Security + JWT + CORS + RBAC
│   ├── AsyncConfig.java                 ← @EnableAsync + 3 thread pools
│   ├── RedisConfig.java                 ← Lettuce connection + RedisTemplate
│   ├── LLMConfig.java                   ← LLM provider + embedding config
│   ├── RagConfig.java                   ← RAG thresholds + topK
│   ├── RateLimitProperties.java         ← Rate-limit per-role config
│   ├── SqlGuardProperties.java          ← SQL Guard config
│   ├── DemoUsersProperties.java         ← Demo user credentials
│   ├── RequestLoggingFilter.java        ← HTTP request logging
│   └── TimeZoneConfig.java              ← Asia/Shanghai timezone
├── controller/
│   ├── QueryController.java             ← POST /api/v1/query + history/favorites/feedback
│   ├── MetadataController.java          ← DDL parse/confirm, tables, columns, glossary, joins, examples
│   ├── OrgController.java               ← Org tree, subtree, path, user-org grants, sync
│   ├── AuthController.java              ← Login, logout, me, refresh
│   ├── UserController.java              ← User CRUD, activate/deactivate, password, org grants
│   ├── EmbeddingController.java         ← Vector sync, search, stats
│   ├── AuditController.java             ← Audit log pagination, detail, stats
│   ├── DataSourceController.java        ← Data source CRUD
│   └── HealthController.java            ← Health check (DB/Redis/LLM)
├── service/
│   ├── OrchestratorService.java         ← Core query pipeline (intent → RAG → LLM → Guard → execute → audit)
│   ├── SqlGuardService.java             ← JSqlParser AST validation + org scope + LIMIT injection
│   ├── QueryExecutorService.java        ← PreparedStatement execution + column metadata enrichment
│   ├── QueryCacheService.java           ← Redis query result cache
│   ├── MetadataService.java             ← Hybrid retrieval: vector-first + keyword-fallback
│   ├── EmbeddingService.java            ← BGE-M3 vectorization (embedAndStore / searchSimilar / syncAll)
│   ├── OrgService.java                  ← Org tree CRUD, recursive subtree, biz sync
│   ├── UserService.java                 ← User CRUD, RBAC, org permission resolution
│   ├── AuditService.java                ← @Async upsert to audit_query_logs
│   ├── HistoryService.java              ← Query history with multi-dimension filters
│   ├── FeedbackService.java             ← User feedback (good/bad)
│   ├── FavoriteService.java             ← Favorite management
│   ├── DDLParserService.java            ← PostgreSQL COMMENT ON + inline comment parsing
│   ├── DataSourceService.java           ← Data source CRUD
│   ├── PromptBuilder.java               ← Load /prompts templates + fill placeholders
│   └── llm/
│       ├── LLMService.java              ← Interface: generateSQL, summarizeResults, repairSQL, detectIntent, recommendChartType, generateEmbedding
│       ├── OpenAILLMService.java        ← DeepSeek/OpenAI implementation (OkHttp, response_format: json_object)
│       ├── LocalLLMService.java         ← Reserved for local Qwen
│       ├── LLMAsyncExecutor.java        ← OkHttp HTTP communication (@Async)
│       └── BgeEmbeddingProvider.java    ← Spring AI Ollama BGE-M3 embedding
├── model/
│   ├── entity/                          ← MyBatis-Plus entities (meta + business + vector)
│   ├── dto/                             ← Request/Response DTOs, QueryContext, MetadataContext, ResultProfile
│   └── vo/                              ← View objects
├── domain/
│   ├── business/
│   │   ├── entity/                      ← DimOrg, DimProduct, DimChannel, FactGrossProfitDetail
│   │   └── mapper/                      ← MyBatis-Plus mappers for text2report
│   ├── meta/
│   │   ├── entity/                      ← MetaTable, MetaColumn, MetaGlossary, MetaJoinEdge, MetaSqlExample, OrgTree, SysUser, SysUserOrg, AuditQueryLog, etc.
│   │   └── mapper/                      ← MyBatis-Plus mappers for text2report-meta
│   └── vector/
│       ├── entity/MetaEmbedding.java    ← pgvector embedding entity
│       ├── mapper/MetaEmbeddingMapper.java  ← pgvector search/upsert
│       └── typehandler/PgVectorTypeHandler.java ← float[] ↔ vector conversion
├── security/
│   ├── JwtUtil.java                     ← JWT sign/verify/parse (userId, username, role, orgId, jti)
│   ├── JwtAuthFilter.java               ← OncePerRequestFilter: token→blacklist→validate→JTI→SecurityContext
│   └── JwtConstants.java
├── common/
│   ├── exception/
│   │   ├── BusinessException.java
│   │   ├── ErrorCode.java               ← 4xxx business errors, 5xxx internal errors
│   │   └── GlobalExceptionHandler.java  ← @ControllerAdvice
│   ├── result/
│   │   ├── ApiResult.java               ← Unified response wrapper {request_id, code, message, data}
│   │   └── PageResult.java              ← Pagination wrapper {records, total, page, pageSize}
│   ├── context/
│   │   ├── TraceContext.java             ← ThreadLocal: requestId, userId, role, orgId
│   │   └── TraceFilter.java             ← UUID v4 requestId establishment
│   └── util/JsonUtil.java
├── config/RateLimitFilter.java           ← Redis Sorted Set sliding window rate limiter
└── Text2ReportApplication.java
```

---

## Core Design Rules

### Rule 1: Metadata-only prompting

Prompts **may** contain: table names, column names, column comments, business glossary, join relationships, example SQL, PostgreSQL dialect hints, org context (IDs and names only).

Prompts **must never** contain: raw query result rows, production business data, PII, financial figures from live result sets, database credentials.

**Implementation**: `OrchestratorService` passes `ResultProfile` (rowCount, columnNames, hasData) to `LLMService.summarizeResults()` — never raw rows.

### Rule 2: SqlGuardService is mandatory

All generated SQL must pass through `SqlGuardService` before execution. The guard must use JSqlParser AST parsing — regex-only blocking is explicitly forbidden.

**Two guard checkpoints**: `validate()` (statement type, schemas, functions, `SELECT *`, LIMIT injection) + `validateOrgScope()` (org_id IN allowedIds).

### Rule 3: Read-only enforcement at two levels

- **Connection pool**: Business datasource with `hikari.read-only: true`
- **AST level**: SqlGuardService rejects any non-SELECT statement

### Rule 4: LLM service must be interface-driven

Always code against the `LLMService` Java interface. Never call LLM SDKs directly from orchestration code. All LLM credentials live in profile-specific config files only.

### Rule 5: Structured LLM output only

Use `response_format: { type: "json_object" }` in all LLM API calls. Free-text responses are rejected.

### Rule 6: Prompt templates are versioned files

All prompt templates live in `/prompts/` as versioned `.txt` files. Never hardcode prompts inline in Java source code.

### Rule 7: DDL upload is the metadata entry point

Table and column metadata must be populated through the DDL upload and parse pipeline. All entries must track `comment_source` and `updated_by`.

### Rule 8: Every request must be audited

`audit_query_logs` must be written for every query — including failed ones, cache hits, and blocked ones — via async execution (Spring `@Async`). Must not block the response path.

---

## PostgreSQL Dialect Rules

| PostgreSQL rule | Current convention |
|-----------------|--------------------|
| Row limit | `LIMIT N` |
| Current time | `NOW()` |
| Quarter extraction | `EXTRACT(QUARTER FROM col)` |
| Year extraction | `EXTRACT(YEAR FROM col)` |
| Month formatting | `TO_CHAR(col, 'YYYYMM')` |
| Null handling | `COALESCE(a, b)` |
| Text conversion | `col::TEXT` |
| String concatenation | `\|\|` or `CONCAT()` |

### Safe gross profit rate (PostgreSQL)

```sql
ROUND(
  (SUM(revenue) - SUM(cost)) * 100.0 / NULLIF(SUM(revenue), 0),
  2
) AS gross_profit_rate
```

### Org scope injection (PostgreSQL recursive CTE)

```sql
WHERE org_id IN (
  WITH RECURSIVE subtree AS (
    SELECT id FROM dim_org WHERE id = :orgId
    UNION ALL
    SELECT d.id FROM dim_org d
    INNER JOIN subtree s ON d.parent_id = s.id
  )
  SELECT id FROM subtree
)
```

---

## SQL Safety Policy

### Allowed
- `SELECT`, `WITH ... SELECT`, safe aggregations, bounded sorting and grouping

### Blocked (JSqlParser AST)
- `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `ALTER`, `DROP`, `TRUNCATE`
- `GRANT`, `REVOKE`, `EXECUTE`, `CALL`
- System catalog access (`pg_catalog`, `information_schema`)
- Dangerous functions (`xp_cmdshell`, `sp_executesql`, etc.)
- `SELECT *` (configurable, default: blocked in production)

### Auto-injected by SqlGuardService
- `LIMIT 1000` if no LIMIT clause present (AST-level injection)
- Organization scope validation on `fact_*` and `dim_org` tables
- JDBC statement timeout (default: 30s)
- SQL length limit (10000 chars)

---

## Unified API Response Format

```json
{
  "request_id": "uuid-v4",
  "code": 200,
  "message": "success",
  "data": {
    "sql": "SELECT ...",
    "table": {
      "columns": [{ "key": "org_name", "title": "组织名称", "dataType": "string" }],
      "rows": [{ "org_name": "子公司A", "gross_profit_rate": 22.5 }]
    },
    "result_profile": { "recommended_chart": "bar", "row_count": 12 },
    "summary": "2024年Q1子公司A毛利率为22.5%。",
    "warnings": [],
    "audit": { "confidence": 0.87, "retry_count": 0, "execution_ms": 342 }
  }
}
```

### Error codes

| Code | Meaning |
|------|---------|
| 4001 | Permission denied |
| 4002 | SQL Guard blocked |
| 4003 | LLM call timeout or error |
| 4004 | Unsupported query intent |
| 4005 | Metadata retrieval failure |
| 5001 | Internal server error |

---

## Retry Policy

| Failure reason | Retry? | Note |
|----------------|--------|------|
| Unknown column / table | Yes | `isRetryable()` keyword match |
| JOIN ambiguity | Yes | |
| PostgreSQL dialect error | Yes | |
| Guard blocked (retryable error) | Yes | SQL repair → re-validate |
| Guard blocked (non-retryable) | No | DROP, INSERT, system schemas |
| Permission denied | No | |
| Unsupported intent | No | |

Default: **2 retries max**. Each retry appends error to prompt and logs to `audit_query_logs`.

---

## Permission Model

### Roles

| Role | Access |
|------|--------|
| **ADMIN** | Full access: metadata CRUD, user management, embedding, audit, data sources, org sync |
| **MANAGER** | Admin pages (org tree, users, audit, schema graph), query domain, SQL visible |
| **USER** | Query domain only, SQL hidden from responses |

### Organization scope

- `sys_user_org` table grants users access to specific orgs with scope: `SELF`, `CHILDREN`, or `ALL`
- `UserService.resolveAccessibleOrgIds()` → intersection of user grants and org subtree
- `SqlGuardService.validateOrgScope()` → AST-level enforcement of org_id in WHERE clause
- Single-device login enforced via Redis session JTI comparison

---

## RAG Pipeline (M10)

**Write path**: Metadata change → `EmbeddingService.embedAndStoreAsync(@Async)` → BGE-M3 inference (Ollama) → pgvector upsert (HNSW cosine)

**Query path**: User question → `MetadataService.retrieveForPrompt()` → vector similarity search (3 source types) + keyword fallback → `MetadataContext`

| Source type | TopK | Similarity threshold |
|-------------|------|---------------------|
| sql_example | 5 | 0.65 |
| glossary | 5 | 0.60 |
| table_description | 3 | 0.55 |

---

## Prompt Rules

All templates in `/prompts/` as versioned files. SQL generation prompt must include:
- PostgreSQL dialect declaration
- Org context (`{org_context}`)
- Org IDs for WHERE filtering (`{org_ids}`)
- Relevant metadata JSON (`{metadata_json}`)
- User question (`{user_question}`)
- Output JSON schema
- Safety constraints

```
prompts/
├── sql_generation_v1.txt
├── sql_repair_v1.txt
└── result_summary_v1.txt
```

### Five LLM call scenarios

| Scenario | Method | Output |
|----------|--------|--------|
| Intent detection | `detectIntent(question)` | query/trend/ranking/aggregation/comparison/extreme |
| SQL generation | `generateSQL(...)` | {sql, explanation, tables_used[], confidence} |
| SQL repair | `repairSQL(...)` | {sql, explanation, tables_used[], confidence} |
| Chart recommendation | `recommendChartType(...)` | bar/line/pie/table/kpi |
| Result summary | `summarizeResults(ResultProfile)` | {summary, key_metrics[]} |

---

## Repository Layout

```
text2report/
├── CLAUDE.md                            ← this file (read by Claude Code on startup)
├── CLAUDE_CN.md                         ← Chinese version
├── AGENTS.md                            ← Agent instructions
├── README.md                            ← Project overview
├── .gitignore                           ← excludes *-local.yml, *-prod.yml, env-*.md
├── frontend/                            ← React 18 + TS + Ant Design 5 (M0–M11 complete)
│   └── src/
│       ├── app/                         ← App.tsx, routes.tsx, AuthGuard.tsx
│       ├── layouts/                     ← QueryLayout.tsx, AdminLayout.tsx
│       ├── pages/query/                 ← QueryPage, HistoryPage, FavoritesPage
│       ├── pages/admin/                 ← 11 admin pages (OrgTree, Users, Embedding, DDL, Tables, Glossary, Joins, Examples, Audit, DataSources, SchemaGraph)
│       ├── components/query/            ← QueryInput, ResultTable, ResultChart, SqlPreview, QuerySummary, etc.
│       ├── components/admin/            ← ColumnEditor, OrgTreeEditor, DDLEditor, GlossaryForm, DataSourceForm
│       ├── components/common/           ← OrgSelector, SqlCodeBlock, BrandMark, InsightBridgeIcon
│       ├── store/                       ← useAuthStore, useOrgStore, useQueryStore (Zustand + persist)
│       ├── services/                    ← request.ts + 9 API modules
│       ├── types/                       ← TypeScript type definitions
│       ├── hooks/                       ← useOrg, useAuth, useWebSocket
│       ├── mocks/                       ← MSW handlers + mock data (auth/query/org/metadata/audit/user/embedding)
│       └── utils/                       ← formatters, tablePagination, constants
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/text2report/        ← (see Backend Module Structure above)
│       └── resources/
│           ├── application.yml                    ← committed (no secrets)
│           ├── application-local.yml              ← NOT committed (has secrets)
│           ├── application-prod.yml               ← NOT committed (has secrets)
│           └── application-local.yml.template     ← committed (template for devs)
├── prompts/
│   ├── sql_generation_v1.txt
│   ├── sql_repair_v1.txt
│   └── result_summary_v1.txt
├── sql/
│   ├── schema-business.sql              ← DDL reference (committed)
│   └── schema-meta.sql                  ← DDL reference (committed)
└── docs/
    ├── STATUS.md                        ← Current project status dashboard
    ├── api-contract.md                  ← API contract
    ├── deployment-guide.md              ← Deployment instructions
    ├── architecture/                    ← Architecture documentation (HTML + MD)
    ├── milestones/                      ← M0–M11 milestone reports
    ├── decisions/                       ← ADR records
    ├── evaluation/                      ← Code review reports
    ├── plan/                            ← Future planning docs
    ├── issue/                           ← Issue analysis
    ├── testing/                         ← E2E test checklist
    ├── env-infrastructure.md            ← NOT committed (server IPs/passwords)
    └── env-database.md                  ← NOT committed (DB credentials)
```

---

## Quality Metrics

| Metric | Value |
|--------|-------|
| Backend tests | 221 (mvn test, 100% pass) |
| Frontend TypeScript | 0 errors (tsc --noEmit) |
| Frontend build | Success (npm run build) |
| Known P0 issues | 4 (see latest code review) |
| Known P1 issues | 10 |

---

## Anti-patterns — Strictly Forbidden

- Passing `text2report` query result rows into any LLM prompt (use `ResultProfile` DTO)
- Calling any database directly from `LLMService` or controller
- Hardcoding metric formulas or business logic in frontend
- Storing prompt templates inline in Java source (must be in `/prompts/`)
- Using regex-only SQL safety (JSqlParser AST is mandatory)
- Bypassing `LLMService` interface to call LLM SDK directly
- Storing LLM API keys in `application.yml` (must be in profile files only)
- Writing to `text2report` business database from any service

---

## Definition of Done

- [ ] Business requirement documented
- [ ] Permission impact reviewed
- [ ] Audit events added to `audit_query_logs`
- [ ] Unit tests written (service layer minimum)
- [ ] Prompt / metadata changes version-documented
- [ ] PostgreSQL dialect compatibility verified
- [ ] API contract aligned with frontend Mock

---

## Milestone History

| Milestone | Name | Date | Status |
|-----------|------|------|--------|
| M0 | Project init & frontend skeleton | — | ✅ Complete |
| M1 | Admin frontend pages | — | ✅ Complete |
| M2 | Query frontend pages | — | ✅ Complete |
| M3 | Mock server (MSW) | — | ✅ Complete |
| M4 | Backend skeleton + common module | 2026-04-10 | ✅ Complete |
| M5 | Org + metadata services | 2026-04-11 | ✅ Complete |
| M6 | LLM + SQL Guard + Executor | 2026-04-11 | ✅ Complete |
| M7 | Cache + history/feedback | 2026-04-11 | ✅ Complete |
| M8 | Gateway + auth + audit | 2026-04-12 | ✅ Complete |
| M9 | Frontend-backend integration | 2026-04-12 | 🟡 Code complete, pending real-env verification |
| M10 | Permission refactor + RAG | 2026-04-16 | 🟡 Code complete, pending real-env verification |
| M11 | Post-M10 refinement + full-stack review | 2026-04-27 | 🟡 Code complete, P0 fixes pending |

### Current priorities

1. **P0 fixes** — 4 items from code review report
2. **P1 fixes** — 10 items
3. **Real-environment E2E verification** — Permission chain, RAG retrieval, async vectorization, org sync
4. **M12 planning** — Metadata-driven org scope upgrade

---

## Change Log

### 2026-04-30: CLAUDE.md full refresh

Updated to reflect M11 state: three datasources (incl. pgvector), DeepSeek LLM, BGE-M3 embedding, RAG pipeline, full RBAC (ADMIN/MANAGER/USER), 9 controllers, 17 services, 221 tests, 11 frontend admin pages, Redis session JTI single-device login, SqlGuard org scope validation, full milestone history.

### 2026-04-10: M4-Hotfix — Security & quality fixes (P0/P1)

P0 fixes: LLMService Rule 1 isolation (ResultProfile DTO), SqlGuardService AST rewrite (JSqlParser Visitor), JWT blacklist, TraceContext requestId preservation, @EnableAsync activation. P1 fixes: exception leak, HealthController @Qualifier, LLM bean conflict, SecurityConfig CORS, JWT config unification, credential cleanup, ErrorCode format. (33 tests)
