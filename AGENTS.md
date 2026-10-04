# AGENTS.md

## Project

Text2Report is an enterprise-grade **natural-language-to-report** platform.
Its goal is not to be a general chatbot. Its goal is to convert controlled business questions into safe SQL, execute them through a governed read-only path, and render the results as charts, tables, summaries, and downloadable reports.

This repository should always preserve four invariants:

1. **LLM sees metadata, not business data.**
2. **Only the query gateway may access databases.**
3. **Only read-only SQL is allowed in production.**
4. **Every request must be auditable and permission-aware.**

---

## Current scope (M11 — Code Complete)

- **Business domain**: Gross profit queries (monthly/quarterly/yearly) on `fact_gross_profit_detail` × `dim_org` × `dim_product` × `dim_channel`
- **Organization structure**: 1 parent → 3 subsidiaries → 12 departments (16 org nodes)
- **Data sources**: 3 PostgreSQL datasources — business (read-only), meta (read-write), vector (pgvector)
- **Frontend**: React 18 + TypeScript + Ant Design 5 + Vite + Zustand + ECharts + MSW
- **Backend**: Java 21 + Spring Boot 3.2 + MyBatis-Plus 3.5
- **LLM**: DeepSeek API (deepseek-chat) via `LLMService` interface (OpenAI-compatible protocol; Qwen reserved)
- **Embedding**: BGE-M3 via Ollama (Spring AI, 1024-dim) → pgvector HNSW cosine
- **Cache**: Redis 7 (query results, session JTI, rate limiting, token blacklist)
- **Auth**: Spring Security + JWT + BCrypt + RBAC (ADMIN / MANAGER / USER) + single-device session

---

## Product goals

- Self-service business querying via natural language
- Automatic monthly/quarterly/yearly gross profit reporting
- Lower BI/report development cost
- Secure deployment in enterprise intranet
- Gradual evolution from external-model POC to local-model production

---

## Non-goals

Do not turn this project into:

- a generic LLM assistant
- a free-form SQL console without policy control
- a direct database chat client
- a front-end-only demo without auditability
- a system where the LLM can access live business data

---

## System architecture (8 layers)

1. **Frontend layer** (React + Ant Design)
   - 3 query pages: QueryPage, HistoryPage, FavoritesPage
   - 11 admin pages: OrgTree, Users, Embedding, DDL Upload, Tables, Glossary, Joins, Schema Graph, Examples, Audit Log, Data Sources
   - 3 Zustand stores: useAuthStore (persist), useOrgStore, useQueryStore
   - 9 API service modules with Axios JWT interceptor
   - MSW mock handlers for all API domains

2. **Orchestration layer** (Spring Boot)
   - `OrchestratorService`: intent detection → RAG → LLM → Guard → execute → summarize → cache → audit
   - `QueryContext` lifecycle DTO with retry tracking
   - 2-retry auto-repair for recoverable errors

3. **Semantic / LLM layer**
   - `LLMService` interface: generateSQL, summarizeResults, repairSQL, detectIntent, recommendChartType, generateEmbedding
   - `OpenAILLMService`: DeepSeek via OkHttp (@Async, response_format: json_object)
   - `PromptBuilder`: loads versioned templates from `/prompts/`
   - `ResultProfile` DTO: LLM sees only rowCount/columnNames/hasData — never raw business rows

4. **Metadata / RAG layer**
   - `MetadataService`: hybrid retrieval — vector similarity (pgvector cosine) + keyword fallback (SQL LIKE)
   - `EmbeddingService`: BGE-M3 vectorization via Ollama → pgvector upsert (HNSW cosine index, 1024-dim)
   - Three source types: sql_example (topK=5, thr=0.65), glossary (topK=5, thr=0.60), table_description (topK=3, thr=0.55)
   - Async vectorization on DDL confirm / glossary save / example save (@Async)

5. **SQL guard / query gateway**
   - `SqlGuardService`: JSqlParser AST validation
     - `validate()`: block non-SELECT, system schemas, dangerous functions, SELECT *
     - `validateOrgScope()`: AST-level org_id IN (allowedIds) enforcement
     - `injectLimit()`: auto-add LIMIT 1000 if missing
   - SQL length limit (10000 chars), auto-injected JDBC timeout (30s)

6. **Execution layer**
   - `QueryExecutorService`: PreparedStatement on read-only HikariCP → `enrichColumnMeta()` (Chinese column labels)
   - `QueryCacheService`: Redis cache by md5(question+orgId), TTL 1800s, degradation-safe
   - `RateLimitFilter`: Redis Sorted Set sliding window, per-role tiers (anon=10, user=30, admin=60/min)

7. **Rendering layer**
   - `LLMService.recommendChartType()` → bar/line/pie/table/kpi
   - `ResultChart` component: ECharts via echarts-for-react, auto-detects label vs metric columns
   - `ResultTable` component: Ant Design Table with sorting, number formatting, compact pagination
   - Export deferred to post-Demo

8. **Audit / observability layer**
   - `AuditService`: @Async upsert to `audit_query_logs` — covers running/success/blocked/error/cache-hit
   - `TraceFilter`: UUID v4 requestId → TraceContext ThreadLocal
   - `RequestLoggingFilter`: method/URI/status/duration logging
   - Prompt sanitization: API key masking + 5000-char truncation in audit logs

---

## Core design rules

### Rule 1: Metadata-only prompting

Prompts may contain: table/column names, comments, glossary, join relationships, example SQL, PostgreSQL dialect hints, org context (IDs and names only).

Prompts must never contain: raw query result rows, production business data, PII, financial figures, database credentials.

Implementation: `OrchestratorService` passes `ResultProfile` DTO to `summarizeResults()` — never raw rows.

### Rule 2: SqlGuardService is mandatory

Two checkpoints: `validate()` (statement type, schemas, functions, `SELECT *`, LIMIT) + `validateOrgScope()` (org_id in WHERE clause). Regex-only blocking is forbidden.

### Rule 3: Read-only enforcement at two levels

HikariCP `read-only=true` on business datasource + AST-level non-SELECT rejection.

### Rule 4: Prefer metadata improvement over fine-tuning

Fix SQL quality in this order: metadata → glossary → join paths → examples → prompt structure → model upgrade → fine-tuning.

### Rule 5: Structured LLM outputs

`response_format: { type: "json_object" }` on all LLM calls.

### Rule 6: LLM service must be interface-driven

Code against `LLMService` interface. Provider switching: OpenAI-compatible = config only; non-compatible = new implementation class.

### Rule 7: DDL upload is the metadata entry point

Tables/columns populated via DDL parse → confirm pipeline. `comment_source` (ddl/manual) and `updated_by` tracked on all entries.

---

## Permission model (M10)

| Role | Access |
|------|--------|
| **ADMIN** | Full: all metadata CRUD, users, embedding, audit, data sources, org sync, SQL visible |
| **MANAGER** | Admin pages (org tree, users, audit, schema graph), query domain, SQL visible |
| **USER** | Query domain only, SQL masked in responses |

**Organization scope**: `sys_user_org` grants (scope: SELF/CHILDREN/ALL) → `UserService.resolveAccessibleOrgIds()` → intersection with org subtree → `SqlGuardService.validateOrgScope()` enforcement.

**Authentication**: JWT (userId, username, role, orgId, jti) + Redis session JTI comparison + Redis blacklist on logout.

---

## SQL safety policy

### Allowed
- `SELECT`, `WITH ... SELECT`, safe aggregations, bounded sorting and grouping

### Blocked (JSqlParser AST)
- `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `ALTER`, `DROP`, `TRUNCATE`
- `GRANT`, `REVOKE`, `EXECUTE`, `CALL`
- System catalog access (`pg_catalog`, `information_schema`, `sys`, `syscolumns`)
- Dangerous functions (`xp_cmdshell`, `sp_executesql`, `xp_regread`, etc.)
- `SELECT *` (configurable)
- SQL > 10000 chars

### Auto-injected
- `LIMIT 1000` if no LIMIT clause
- Organization scope validation on `fact_*` and `dim_org` tables
- JDBC queryTimeout = 30s

---

## Retry policy

| Failure | Retry? |
|---------|--------|
| Unknown column/table | Yes |
| JOIN ambiguity / syntax error | Yes |
| PostgreSQL dialect error | Yes |
| Guard blocked (retryable) | Yes (repair → re-validate) |
| Guard blocked (DROP/INSERT/etc.) | No |
| Permission denied | No |
| Unsupported intent | No |

Default: **2** retries max. Each retry logged to `audit_query_logs`.

---

## Repository layout (current)

```
text2report/
├── CLAUDE.md / CLAUDE_CN.md / AGENTS.md / README.md
├── frontend/src/
│   ├── app/          ← App.tsx, routes.tsx, AuthGuard.tsx
│   ├── layouts/      ← QueryLayout.tsx, AdminLayout.tsx
│   ├── pages/query/  ← QueryPage, HistoryPage, FavoritesPage
│   ├── pages/admin/  ← 11 pages (OrgTree, Users, Embedding, DDL, Tables, Glossary, Joins, SchemaGraph, Examples, Audit, DataSources)
│   ├── components/   ← query/ + admin/ + common/ (15+ components)
│   ├── store/        ← useAuthStore, useOrgStore, useQueryStore (Zustand)
│   ├── services/     ← request.ts + 9 API modules (Axios + JWT interceptor)
│   ├── types/        ← TypeScript type definitions
│   ├── mocks/        ← MSW handlers + data (7 handler modules)
│   └── utils/        ← formatters, tablePagination, constants
├── backend/src/main/java/com/text2report/
│   ├── config/       ← 13 config classes (3 datasources, security, async, LLM, RAG, rate-limit)
│   ├── controller/   ← 9 controllers (Query, Metadata, Org, Auth, User, Embedding, Audit, DataSource, Health)
│   ├── service/      ← 17 services (Orchestrator, SqlGuard, Executor, Metadata, Embedding, Org, User, Audit, LLM, etc.)
│   ├── domain/       ← Entities + Mappers (business/ meta/ vector/)
│   ├── model/dto/    ← 26 DTOs (QueryContext, MetadataContext, ResultProfile, etc.)
│   ├── security/     ← JwtUtil, JwtAuthFilter, JwtConstants
│   └── common/       ← ApiResult, BusinessException, TraceContext, TraceFilter
├── prompts/          ← sql_generation_v1.txt, sql_repair_v1.txt, result_summary_v1.txt
├── sql/              ← schema-business.sql, schema-meta.sql
└── docs/             ← STATUS.md, api-contract.md, architecture/, milestones/, evaluation/, testing/
```

---

## Quality metrics

| Metric | Value |
|--------|-------|
| Backend tests | 221 (mvn test, 100%) |
| Frontend tsc | 0 errors |
| Frontend build | Success |
| P0 issues | 4 |
| P1 issues | 10 |

---

## Anti-patterns

- Sending PostgreSQL result rows to the LLM for any reason
- Calling PostgreSQL directly from LLMService or controller
- Hardcoding metric formulas in frontend
- Storing prompts inline in Java source files
- Using regex-only SQL safety (JSqlParser AST is mandatory)
- Bypassing `LLMService` interface to call SDKs directly
- Storing LLM API keys in `application.yml` (profile files only)
- Inserting metadata directly to DB without DDL parse pipeline
- Treating successful SQL execution as proof of semantic correctness
- Writing to `text2report` business database from any service

---

## Definition of done

A feature is complete when it has: documented business requirement, permission impact reviewed, audit events added to `audit_query_logs`, unit tests (service layer minimum), prompt/metadata version documented, PostgreSQL dialect compatibility verified, API contract aligned with frontend Mock.

---

## Milestone history

| Milestone | Date | Status |
|-----------|------|--------|
| M0–M3 | — | ✅ Frontend skeleton, admin pages, query pages, MSW mock |
| M4 | 2026-04-10 | ✅ Backend skeleton + common module |
| M5 | 2026-04-11 | ✅ Org + metadata services |
| M6 | 2026-04-11 | ✅ LLM + SQL Guard + Executor E2E |
| M7 | 2026-04-11 | ✅ Cache + history/feedback |
| M8 | 2026-04-12 | ✅ Gateway + auth + audit |
| M9 | 2026-04-12 | 🟡 Integration (code done, pending real-env verification) |
| M10 | 2026-04-16 | 🟡 Permission refactor + RAG (code done, pending verification) |
| M11 | 2026-04-27 | 🟡 Post-M10 refinement + full-stack review (code done, P0 fixes pending) |

### Current priorities

1. P0 fixes (4 items from code review)
2. P1 fixes (10 items)
3. Real-environment E2E verification
4. M12 planning: metadata-driven org scope upgrade
