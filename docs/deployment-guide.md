# Text2Report 初始化与部署

本文按当前代码说明空环境准备、本地联调和部署步骤。已有数据库与本机配置时，使用已有资源；初始化 SQL 仅用于新建的开发数据库。真实业务环境必须经过独立验收。

## 环境准备

- JDK 21、Maven；用 `java -version` 和 `mvn -v` 确认实际使用的 JDK。
- Node.js 20.19+（20.x）或 22.12+，使用仓库的 npm lockfile 安装依赖。
- PostgreSQL 业务库、元数据库及向量库；向量库需要支持 HNSW 的 pgvector。
- Redis 7；用于会话、黑名单、限流与缓存。
- Ollama 与 `bge-m3`，以及一个可用的 OpenAI 兼容聊天模型服务。

三个 PostgreSQL 数据源在后端分别配置。以下示例使用本机 `5432` 上的业务库与元数据库，以及 `5433` 上的独立向量实例；端口和库名可以按实际环境调整。

## 空数据库初始化

### 业务库与元数据库

在项目根目录执行，连接账号需要具备建库及 DDL 权限：

```bash
createdb -h localhost -p 5432 -U postgres text2report
createdb -h localhost -p 5432 -U postgres text2report-meta
psql -h localhost -p 5432 -U postgres -d text2report -v ON_ERROR_STOP=1 -f sql/schema-business.sql
psql -h localhost -p 5432 -U postgres -d text2report-meta -v ON_ERROR_STOP=1 -f sql/schema-meta.sql
```

脚本含表所有者 `postgres` 的声明，使用其他管理角色时需要先调整所有者。它们是建表 DDL，不能作为已存在数据库的升级迁移脚本直接重跑；也不包含业务演示数据、组织种子、账号或激活模型。

为业务连接创建独立只读账号。在业务库的 `psql` 会话中执行，`\password` 会交互设置密码：

```sql
CREATE ROLE t2r_business_ro LOGIN;
\password t2r_business_ro
GRANT CONNECT ON DATABASE text2report TO t2r_business_ro;
GRANT USAGE ON SCHEMA public TO t2r_business_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO t2r_business_ro;
ALTER ROLE t2r_business_ro SET default_transaction_read_only = on;
```

后端业务连接使用此账号。后续新增业务表时，应按部署流程补充读取授权；元数据库与向量库使用独立的读写账号。

### 向量库

在 pgvector 所在实例上创建开发库：

```bash
createdb -h localhost -p 5433 -U postgres text2report-vector
psql -h localhost -p 5433 -U postgres -d text2report-vector
```

在该会话中执行下列 SQL。当前业务库与元数据库 DDL 不包含向量表；此结构与 `MetaEmbeddingMapper` 的 `embedding` 字段及 upsert 唯一键对应：

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE public.meta_embedding (
    id SERIAL PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL,
    source_id INTEGER NOT NULL,
    content_text TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    model_version VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (source_type, source_id)
);

CREATE INDEX idx_meta_embedding_hnsw
    ON public.meta_embedding USING hnsw (embedding vector_cosine_ops);
```

向量连接指向此库，例如 `jdbc:postgresql://localhost:5433/text2report-vector`。在 Ollama 服务可用后执行 `ollama pull bge-m3`，确保模型输出维度与表的 1024 维一致。

### 初始组织与管理员

登录账号读取元数据库中的 `sys_user`，没有自动创建的真实后端演示账号。首次管理员需要由数据库管理员初始化，密码字段必须为 BCrypt 哈希。

可以使用本机的 `htpasswd` 交互生成哈希；该工具由 Apache HTTP Server 工具包提供。命令不会把密码放进命令参数：

```bash
htpasswd -nBC 10 admin
```

复制输出中 `admin:` 后的完整 BCrypt 哈希，替换下面的 `<ADMIN_BCRYPT_HASH>`。账号和哈希仅保存在本机受保护的配置或数据库中。

组织 ID 必须与业务库一致。对于全新的开发库，可先在业务库建立一个测试根组织：

```sql
INSERT INTO public.dim_org (id, org_name, parent_id, org_level, org_code, is_active)
VALUES (1, '演示集团', NULL, 1, 'DEMO-HQ', TRUE);
```

再在元数据库中初始化对应组织、管理员及其默认授权：

```sql
BEGIN;

INSERT INTO public.dim_org (id, org_name, parent_id, org_level, org_code, is_active)
VALUES (1, '演示集团', NULL, 1, 'DEMO-HQ', TRUE);

INSERT INTO public.sys_user (username, password_hash, display_name, role, is_active, created_by)
VALUES ('admin', '<ADMIN_BCRYPT_HASH>', '系统管理员', 'ADMIN', TRUE, 'bootstrap');

INSERT INTO public.sys_user_org (user_id, org_id, scope, is_primary, granted_by)
SELECT id, 1, 'ALL', TRUE, 'bootstrap'
FROM public.sys_user WHERE username = 'admin';

COMMIT;
```

已有业务组织时，使用真实组织的 ID 与基本信息初始化默认授权，随后通过组织同步功能更新元数据库。其他账号与授权可在管理员登录后通过管理页面维护。

## 本地配置与启动

从项目根目录创建本机配置，已有文件时保留：

```bash
cd backend
cp -n src/main/resources/application-local.yml.template src/main/resources/application-local.yml
```

编辑模板中的数据库、Redis、JWT 和 Ollama 配置。业务账号使用前面创建的只读角色，向量 URL 使用实际初始化的库。模板中的 `<YOUR_...>` 都需要替换；填写明文密码只允许在被 Git 忽略的本机文件中进行。

JWT 密钥需独立随机生成，可在本机执行 `openssl rand -base64 48`，将结果保存在本机配置的 `jwt.secret`。后端要求至少 32 字符、至少 8 种不同字符，并拒绝连续或重复模式；若随机值被该规则拒绝，重新生成。

当前启动校验仍要求三个 `demo.users.*.password-hash`。在本机 YAML 中补充以下项，并分别填入交互生成的 BCrypt 哈希；也可设置对应的 `DEMO_ADMIN_PASSWORD_HASH`、`DEMO_USER_PASSWORD_HASH`、`DEMO_VIEWER_PASSWORD_HASH` 环境变量：

```yaml
demo:
  users:
    admin:
      password-hash: '<ADMIN_BCRYPT_HASH>'
    demo:
      password-hash: '<DEMO_BCRYPT_HASH>'
    user:
      password-hash: '<USER_BCRYPT_HASH>'
```

这些启动配置不会向 `sys_user` 插入账号，实际登录使用前面初始化的数据库账号。聊天模型也需要在管理页面建立并激活，旧的 `LLM_API_KEY` / `LLM_BASE_URL` 环境变量不会代替当前模型路由。

从 `backend/` 目录启动，以便默认 `../prompts/` 能找到提示词模板：

```bash
AUTH_COOKIE_SECURE=false mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`AUTH_COOKIE_SECURE=false` 用于本机 HTTP，HTTPS 部署保持 `true`。后端默认使用 `8080` 端口。

另开终端，从项目根目录启动前端：

```bash
cd frontend
npm ci
VITE_USE_MSW=false npm run dev
```

访问 `http://localhost:3000`，Vite 将 `/api` 代理到 `http://localhost:8080`。后端地址变化时设置 `VITE_API_BASE_URL`。真实认证使用 HttpOnly Cookie 与 CSRF Token，保持同一个浏览器入口进行登录和查询。

## 首次查询与验收

管理员登录后，先同步组织并核对账号授权，再登记与后端业务连接一致的启用数据源。当前执行器使用固定 `businessDataSource`，新增管理记录不会自动切换实际查询连接。

通过 DDL 上传与确认流程建立表和列元数据，维护业务说明、术语、关联关系与 SQL 示例，再发布相关表。到 `/admin/models` 新增兼容聊天模型，执行连接测试、启用并激活；到向量管理中同步元数据，并检查同步结果。

基本健康接口可用于检查 HTTP 服务响应：

```bash
curl http://localhost:8080/api/v1/health
```

管理员的 `/api/v1/health/details` 检查业务库、元数据库、Redis 和模型配置状态；它不验证完整模型调用或向量链路。验收还需要实际完成授权范围内的查询，检查表格与图表、业务口径、越权拒绝和 `audit_query_logs`，并按 [端到端验收清单](testing/e2e-test-checklist.md) 逐项核对。

## 构建与部署

在项目根目录运行测试与构建：

```bash
(cd backend && mvn test && mvn package -DskipTests)
(cd frontend && npm test && npm run build)
```

后端产物为 `backend/target/text2report-backend-1.0.0.jar`，前端产物为 `frontend/dist/`。为目标环境准备被 Git 忽略的 `application-prod.yml` 或外置配置，配置独立凭据、数据库账号和允许的 CORS 来源，并保留安全 Cookie 设置。

从保留 `prompts/` 相对位置的后端部署目录运行；改变目录结构时设置 `PROMPT_DIR` 为实际模板路径：

```bash
java -jar target/text2report-backend-1.0.0.jar --spring.profiles.active=prod
```

静态服务需要支持 SPA 路由回退、将 `/api/` 转发到后端，并在 HTTPS 入口传递正确的代理头。Vite 的开发代理不会随 `dist/` 自动部署；`VITE_API_BASE_URL` 不能代替生产反向代理。

当前模型密钥采用可逆 Base64 编码，生产部署前需要完善密钥存储保护，详见 [LLM 配置](llm-configuration.md)。发布到 GitHub 前还需按 [发布说明](security/github-publication.md) 复查当前源码与历史。
