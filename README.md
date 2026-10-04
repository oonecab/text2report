# Text2Report

Text2Report 是面向企业业务查询的自然语言报表平台。用户选择组织范围、输入业务问题，系统结合受控元数据生成 SQL，完成权限与安全校验后执行只读查询，再呈现表格、图表和摘要。

当前主要面向 PostgreSQL 毛利分析，支持按月、季度、年度以及组织、产品、渠道等维度提问。例如：“查询某子公司本季度的毛利趋势”或“对比各产品今年的收入与毛利”。具体可查询的范围取决于已发布的元数据和用户授权。

## 当前能力与边界

- 查询工作台、历史记录、收藏，以及组织、用户、元数据、模型、向量检索、数据源和审计管理页面。
- DDL 解析与确认、字段语义维护、术语、JOIN 关系、SQL 示例和元数据发布；RAG 结合 pgvector 相似度检索与关键词回退。
- OpenAI 兼容聊天模型配置、连接测试、启停、激活，以及 SQL 生成、SQL 修复、结果摘要的能力路由；Embedding 使用 Ollama 的 BGE-M3。
- JWT Cookie 登录、CSRF 防护、BCrypt 密码校验、角色与组织授权、单设备会话、查询缓存、限流和请求审计。

代码已在 M11 基础上继续实现多模型配置和查询链路拆分。真实数据库、模型服务、权限隔离和完整查询链路仍需在目标环境验收；旧里程碑中的测试数量与通过率是历史记录。下载与报表导出仍待实现。

目前查询执行使用后端配置的固定 `businessDataSource`。数据源管理中的记录用于元数据管理，尚未实现按记录动态切换执行连接；初次联调应只启用与该业务库一致的数据源。业务域和组织字段仍有约束，相关问题见 [多数据源与组织校验分析](docs/issue/multi-datasource-execution-and-sqlguard-hardcoded-20260504_121936.md)。

## 查询与安全设计

```text
业务问题 + 组织范围
  → 意图与权限解析 → 元数据检索 → LLM 生成 SQL
  → SQL / 元数据 / 组织范围校验 → 只读查询网关
  → 表格、图表、摘要 → 缓存与审计
```

项目维护四条核心约束：

1. **LLM 只接收元数据与结果结构信息。** 提示词可包含表结构、字段说明、术语、关联关系、示例 SQL 和组织上下文；结果摘要使用 `ResultProfile`，不传递原始业务结果行。
2. **业务数据库只能通过查询网关访问。** LLM 与控制器不直接执行业务 SQL。
3. **生产查询必须只读。** `SqlGuardService` 使用 JSqlParser AST 校验，拒绝写操作、危险函数、系统表和 `SELECT *`；业务连接配置为只读，并应使用数据库级只读账号。默认补充 `LIMIT 1000`，执行器的 JDBC 超时由 `sql-guard.query-timeout-seconds` 控制，默认为 30 秒。
4. **请求必须遵守权限并可追踪。** 组织范围由账号授权与所选组织的子树取交集，查询状态与重试记录写入 `audit_query_logs`。

| 角色 | 主要访问范围 |
|------|--------------|
| `USER` | 查询、历史、收藏；查询响应隐藏 SQL |
| `MANAGER` | 查询及组织、用户、Schema 图、审计页面；可查看 SQL，受组织授权限制 |
| `ADMIN` | 全部管理功能，包括模型、元数据、数据源和向量管理 |

## 技术栈与目录

| 层级 | 当前技术 |
|------|----------|
| 前端 | React 19、TypeScript 6、Ant Design 5、Vite 8、React Router 7、Zustand 5、ECharts 6、MSW |
| 后端 | Java 21、Spring Boot 3.2.5、MyBatis-Plus 3.5.7、JSqlParser 4.9 |
| 数据 | PostgreSQL 业务库（只读）、元数据库（读写）、pgvector 向量库 |
| 缓存与会话 | Redis 7 |
| 模型 | `LLMService` 接口、OpenAI 兼容 Chat Completions、Ollama BGE-M3（1024 维） |

```text
text2report/
├── frontend/          # 查询与管理界面、API 服务、状态管理、MSW
├── backend/           # 编排、权限、元数据、模型路由、SQL 校验、查询网关
├── prompts/           # 版本化的 SQL 生成、修复与摘要模板
├── sql/               # 业务库与元数据库 DDL
├── scripts/           # 仓库隐私检查等辅助工具
└── docs/              # 启动部署、API、架构、里程碑、评审与验收文档
```

依赖版本以 [frontend/package-lock.json](frontend/package-lock.json) 与 [backend/pom.xml](backend/pom.xml) 为准。

## 快速开始

前端需要 **Node.js 20.19+（20.x）或 22.12+**；后端使用 **JDK 21 和 Maven**。完整联调还需要 PostgreSQL、pgvector、Redis、Ollama 及可用的 OpenAI 兼容聊天模型服务。

### 预览前端界面

在项目根目录执行：

```bash
cd frontend
npm ci
VITE_USE_MSW=true npm run dev
```

访问 `http://localhost:3000`。MSW 在浏览器中提供模拟接口，适合查看页面和交互。模拟账号为 `admin / admin123`、`manager / manager123`、`user / user123`，仅用于此预览模式；真实后端账号需要在元数据库中初始化。

### 启动真实后端

首次使用空数据库时，先按 [初始化与部署指南](docs/deployment-guide.md) 建库、初始化向量表和管理员，再配置后端。仓库中的 DDL 不包含演示数据、账号或已激活模型。

在项目根目录执行；已有本机配置时保留原文件：

```bash
cd backend
# 仅首次配置时执行
cp -n src/main/resources/application-local.yml.template src/main/resources/application-local.yml
```

编辑 `application-local.yml`，填入业务库、元数据库、向量库、Redis 和 JWT 配置，并补齐 `demo.users.*.password-hash`。下表列出可通过环境变量配置的主要项目；本地 profile 中的显式配置会覆盖 `application.yml` 对应的默认绑定。

| 配置 | 用途 |
|------|------|
| `BUSINESS_DB_HOST / BUSINESS_DB_PORT / BUSINESS_DB_NAME / BUSINESS_DB_USER / BUSINESS_DB_PASSWORD` | 业务库连接，使用只读账号 |
| `META_DB_HOST / META_DB_PORT / META_DB_NAME / META_DB_USER / META_DB_PASSWORD` | 元数据库连接 |
| `VECTOR_DB_URL / VECTOR_DB_USER / VECTOR_DB_PASSWORD` | pgvector 库 JDBC URL、账号与密码 |
| `REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DB` | Redis 连接与逻辑库 |
| `JWT_SECRET` | 独立生成的强随机密钥，至少 32 字符，并通过启动时的强度检查 |
| `DEMO_ADMIN_PASSWORD_HASH / DEMO_USER_PASSWORD_HASH / DEMO_VIEWER_PASSWORD_HASH` | 当前启动校验仍要求的三个 BCrypt 哈希；实际登录读取 `sys_user` |
| `OLLAMA_BASE_URL / OLLAMA_EMBEDDING_MODEL` | 默认 `http://localhost:11434` 与 `bge-m3` |
| `AUTH_COOKIE_SECURE` | 本地 HTTP 联调设为 `false`；HTTPS 部署保持 `true` |
| `PROMPT_DIR` | 默认 `../prompts/`，启动工作目录改变时需调整 |

从 `backend/` 目录启动：

```bash
AUTH_COOKIE_SECURE=false mvn spring-boot:run -Dspring-boot.run.profiles=local
```

服务默认监听 `8080`。执行 `curl http://localhost:8080/api/v1/health` 可确认 HTTP 服务响应；该接口不代表数据库、向量、模型或完整查询链路已经通过验收。

### 联调与首次查询

在另一终端从项目根目录执行：

```bash
cd frontend
npm ci
VITE_USE_MSW=false npm run dev
```

Vite 将 `/api` 代理至 `http://localhost:8080`；可用 `VITE_API_BASE_URL` 调整代理目标。登录会话通过 HttpOnly Cookie 传递，前端处理 CSRF Token。使用同一个 `localhost:3000` 入口完成登录与查询。

管理员登录后，依次完成：

1. 同步组织，检查账号的组织授权。
2. 在数据源管理中登记与 `businessDataSource` 一致的数据源，并启用。
3. 上传业务 DDL、确认解析结果、完善字段语义与关联关系，再发布相关表。未发布表不会进入查询可用元数据。
4. 在 `/admin/models` 配置、测试并激活聊天模型；在向量管理中同步元数据，检查 BGE-M3 与 pgvector 是否可用。
5. 发起一个授权范围内的业务问题，核对结果、权限限制和审计记录。

## 模型配置

聊天模型的 Provider、密钥与能力路由保存在元数据库中，由 `/admin/models` 管理。新数据库没有默认激活模型；仅填写本地 YAML 不会建立聊天模型路由。

当前调用路径使用 OpenAI 兼容协议。以 DeepSeek 为配置示例：

| 字段 | 示例 |
|------|------|
| 模型类型 / 协议 | 在线模型 / OpenAI 兼容 |
| Base URL / 端点路径 | `https://api.deepseek.com/v1` / `/chat/completions` |
| 模型编码 | `deepseek-chat`，按实际服务填写 |
| 鉴权 / JSON 模式 | `Bearer` / `response_format` |

激活模型会为 SQL 生成、SQL 修复和结果摘要建立路由。`response_format` 与混合模式会发送 `{"type":"json_object"}`；Prompt 约束模式用于不支持该请求字段的兼容服务，但后端仍要求 JSON 响应。

原生 Gemini、Anthropic、签名鉴权等配置选项仍需对应协议实现。当前非 `NONE` 的鉴权使用 Bearer 兼容处理。**模型密钥目前以 Base64 编码保存，Base64 可逆，不构成加密；列表接口的脱敏也不提供存储加密保障。** 生产部署前应完成真正的密钥加密或密钥托管。详细说明见 [LLM 配置](docs/llm-configuration.md)。

## 开发验证与发布

在项目根目录执行：

```bash
# 前端单元测试与 TypeScript / Vite 构建
(cd frontend && npm test && npm run build)

# 后端测试，使用 JDK 21
(cd backend && mvn test)

# 当前源码隐私检查；提交前另查暂存区
python3 scripts/check_repository_privacy.py
python3 scripts/check_repository_privacy.py --staged
```

测试与构建通过后，仍需按 [端到端验收清单](docs/testing/e2e-test-checklist.md) 验证目标环境。特别要核对业务口径、组织隔离、实际模型响应、缓存和审计，不能仅凭 SQL 执行成功判断报表正确。

本机 profile、环境笔记和私钥文件由 `.gitignore` 排除，`VITE_*` 变量只可放置可公开的配置。**早期 Git 历史包含过凭据，发布前需要轮换相关凭据，并从复查后的源码快照初始化新仓库，或先在独立克隆中清理完整历史。** 操作步骤见 [GitHub 发布说明](docs/security/github-publication.md)。

## 文档导航

| 文档 | 说明 |
|------|------|
| [初始化与部署指南](docs/deployment-guide.md) | 空数据库初始化、账号、本地配置与部署步骤 |
| [前端说明](frontend/README.md) | 前端命令、环境变量和代码入口 |
| [LLM 配置](docs/llm-configuration.md) | 模型激活、协议与密钥存储边界 |
| [API 契约](docs/api-contract.md) | 接口约定；新增实现需结合控制器与前端服务核对 |
| [架构文档](docs/architecture/) | 架构设计与历史快照 |
| [里程碑记录](docs/milestones/) / [状态记录](docs/STATUS.md) | 阶段成果与历史验证记录 |
| [代码评审](docs/issue/code-review-report-20260512.md) | 已记录的问题与风险分析 |
| [端到端验收](docs/testing/e2e-test-checklist.md) | 真实环境验收参考 |
| [GitHub 发布说明](docs/security/github-publication.md) | 凭据、隐私与提交历史处理 |
| [AGENTS.md](AGENTS.md) / [CLAUDE_CN.md](CLAUDE_CN.md) / [CLAUDE.md](CLAUDE.md) | 项目约束与协作规范 |
