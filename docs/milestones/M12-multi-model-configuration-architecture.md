# M12 — 多模型配置中心与 Provider Driver 架构设计

> 状态：需求分析 / 架构设计完成，待实现
> 设计日期：2026-05-04
> 默认激活模型：DeepSeek

## 1. 背景

当前项目的 LLM 接入以 DeepSeek 为主，后端通过 `LLMService` 接口隔离业务调用，默认实现为 `OpenAILLMService`，并由 `llm.provider=openai` + `llm.openai.*` 配置驱动。这个设计对于单一 OpenAI 兼容模型足够，但若要在管理端页面中配置并切换多个模型，并同时支持**在线模型**（OpenAI / Gemini / Qwen / DeepSeek / Seed 等）与**本地模型**（如本地 Ollama / 本地 OpenAI 兼容网关），现有架构还不完整：

- 当前配置来源于 `application*.yml`，不是可运营的后台配置，也没有落到元数据数据库
- 当前 `llm.provider` 只有 `openai` / `local` 两档，不支持管理多个在线 provider
- 当前请求协议、认证方式、响应解析均偏向 OpenAI 兼容协议
- 当前没有“模型配置变更审计”“连接测试”“默认模型回退”“按能力路由”等配套能力

因此，这个需求**可以实现**，但不能只增加一个前端页面；必须同时补齐后端配置域、运行时路由层、密钥安全、审计与缓存一致性设计。

## 2. 可行性结论

### 2.1 可以实现的原因

现有项目已经具备三个关键基础：

1. `OrchestratorService` 只依赖 `LLMService` 接口，而不是具体 SDK
2. `PromptBuilder`、`SqlGuardService`、`QueryExecutorService` 与具体模型供应商解耦
3. DeepSeek / Qwen / 部分 Seed/Ark 网关本身支持 OpenAI 兼容协议，可先低成本接入

### 2.2 当前不足

如果直接在现有实现上“堆配置项”，后续会出现这些问题：

- 每接一个非 OpenAI 兼容模型，都要改 `OpenAILLMService` / `LLMAsyncExecutor`
- API Key 只能写在配置文件里，无法通过后台安全管理
- 本地模型与在线模型没有统一的配置入口，用户无法页面化切换配置模式
- 激活模型切换后，查询缓存仍可能命中旧模型结果
- 审计日志无法准确回答“这次 SQL 是哪个模型生成的”
- 前端看似有“多模型配置页”，实际后端仍只有单 provider 静态配置

### 2.3 总体判断

建议将该需求作为 **M12 独立里程碑** 推进，采用“管理端配置中心 + Provider Driver 协议层 + 运行时路由”的完整方案实现。  
不建议只做前端表单或只在 `application.yml` 中堆多组配置。

## 3. 需求定义

### 3.1 目标

在现有项目基础上新增一个“多模型配置”管理页面，使系统能够：

- 仅由**系统管理员（`ADMIN`）**在前端页面维护模型配置
- 支持分别配置**在线模型**与**本地模型**
- 将模型配置持久化保存到**元数据数据库**
- 默认激活 DeepSeek
- 项目在后续 LLM 相关操作中按当前激活配置调用对应模型
- 支持 OpenAI / Gemini / Qwen / DeepSeek / Seed 等在线模型的逐步接入
- 支持本地 Ollama / 本地 OpenAI 兼容网关等本地模型配置入口
- 对模型配置新增、修改、启停、激活、测试连接进行审计

### 3.2 V1 范围

M12 第一阶段建议只覆盖这些能力：

- 多模型配置 CRUD
- 在线模型 / 本地模型双入口配置
- 单一全局激活模型
- DeepSeek 默认配置
- `generateSQL` / `repairSQL` / `summarizeResults` 三类远程调用按激活模型执行
- 连接测试、参数校验、启停控制、审计留痕

### 3.3 暂不纳入 V1

- 每个用户单独选模型
- 每个组织单独选模型
- 多模型投票
- 自动成本路由
- 自动故障切换
- Embedding 模型管理
- Prompt 在线编辑

## 4. 核心建议

### 4.1 页面名称不要叫“模型配置”就结束

更准确的对象应拆成三层：

- **Provider 实例**：一条可调用的远程模型接入配置
- **Routing 策略**：当前哪些能力用哪个 provider
- **Secret 凭据**：API key / AKSK 等敏感字段

否则后期容易把“模型名称”“供应商配置”“路由规则”“密钥信息”全部混在一张表和一个页面里，维护成本很高。

### 4.2 V1 先做“全局激活模型”，不要直接做“按用户选模型”

当前系统的安全与审计边界以“统一治理”为主。若一开始就支持每用户、每会话、每组织自选模型，会立刻引入：

- 缓存键维度膨胀
- 审计复杂度上升
- 排错困难
- 权限与配额策略复杂化

所以 V1 建议先做：`全局默认 DeepSeek + 全局单活模型`。

### 4.3 在线模型、本地模型与 Embedding 模型必须分开

当前嵌入能力来自 `BGE-M3 via Ollama`，其职责与在线聊天模型不同。  
M12 建议在“模型配置中心”中支持**在线模型**和**本地模型**两种 chat provider，但**不建议**把 embedding 也并入同一个配置体系，否则会把：

- SQL 生成模型
- 摘要模型
- Embedding 模型
- 本地 / 在线调用链

全部混为一谈，导致实现和运营都变复杂。

## 5. 现状基线

基于当前代码，LLM 相关核心事实如下：

- `LLMService` 已经是稳定业务接口
- `OpenAILLMService` 同时承担业务逻辑与协议逻辑
- `LLMAsyncExecutor` 固定走 `/chat/completions`
- 认证方式固定为 `Authorization: Bearer {apiKey}`
- 默认配置位于 `LLMConfig.openai`
- `LocalLLMService` 预留存在，但尚未真正完成，也没有后台配置化入口

这意味着：

- **OpenAI 兼容模型** 可以低成本接入
- **非兼容模型** 需要协议层重构后再接入

## 6. 目标架构

### 6.1 总体分层

```text
Admin UI
  ├─ 模型配置列表页
  ├─ 选择“在线模型 / 本地模型”的创建入口
  ├─ 在线模型配置表单 / 弹框
  ├─ 本地模型配置表单 / 弹框
  ├─ 激活切换
  └─ 连接测试 / 审计查看

ModelConfigController
  ├─ ProviderConfigService
  ├─ RoutingPolicyService
  ├─ ProviderSecretService
  └─ ModelConfigAuditService

OrchestratorService
  └─ LLMService
       └─ UnifiedLLMService
            ├─ PromptBuilder
            ├─ ProviderRoutingResolver
            └─ DriverRegistry
                 ├─ OpenAICompatibleDriver
                 ├─ GeminiDriver
                 ├─ AnthropicDriver
                 ├─ VolcanoNativeDriver
                 └─ ...

基础设施
  ├─ Meta DB（模型配置、路由策略、审计）
  ├─ Secret 加密组件
  ├─ OkHttpClient 连接池
  └─ 现有 Audit / Cache / SqlGuard / QueryExecutor
```

### 6.2 运行时调用流程

```text
用户发起查询
  → OrchestratorService
  → LLMService.generateSQL()
  → ProviderRoutingResolver 解析当前能力对应的激活 provider
  → DriverRegistry 获取匹配 Driver
  → Driver 构造请求、认证、发送、解析响应
  → 返回标准化内容给 UnifiedLLMService
  → SqlGuardService 校验
  → QueryExecutorService 执行
  → Audit 记录 provider/model/requestId/latency
```

### 6.3 能力路由范围

M12 建议只对以下远程能力路由：

| 能力 | 当前状态 | M12 方案 |
|------|----------|----------|
| `generateSQL` | 远程 LLM | 路由到激活 provider |
| `repairSQL` | 远程 LLM | 路由到激活 provider |
| `summarizeResults` | 远程 LLM | 路由到激活 provider |
| `detectIntent` | 当前为本地规则 | V1 保持本地，不纳入路由 |
| `recommendChartType` | 当前为本地规则 | V1 保持本地，不纳入路由 |
| `generateEmbedding` | 本地 BGE-M3 | 不纳入本次多模型页 |

这能避免把“不需要远程模型的逻辑”也强行绑进多模型改造。

## 7. 配置域设计

### 7.1 配置对象拆分

建议引入三个核心对象。  
其中“模型配置主表”是本次需求的**必选项**，必须创建在元数据数据库中。

#### A. ProviderConfig

表示一条可调用的模型实例配置。

建议新增元数据表：`meta_llm_provider`

建议字段：

| 字段 | 说明 |
|------|------|
| `id` | 主键 |
| `providerKey` | 系统唯一标识，如 `deepseek-default` |
| `displayName` | 页面展示名，如“DeepSeek 默认” |
| `providerMode` | `ONLINE` / `LOCAL` |
| `vendorType` | `OPENAI_COMPATIBLE` / `GEMINI` / `ANTHROPIC` / `VOLCANO_NATIVE` |
| `modelCode` | 如 `deepseek-chat` / `gpt-4.1-mini` |
| `baseUrl` | API 根地址 |
| `endpointPath` | 如 `/chat/completions`，保留可变性 |
| `authType` | `BEARER` / `API_KEY` / `AKSK` / `NONE` |
| `deploymentType` | `SAAS` / `OLLAMA` / `LOCAL_GATEWAY` |
| `host` | 本地模型主机地址，如 `http://127.0.0.1:11434` |
| `port` | 本地模型端口 |
| `timeoutSeconds` | 超时 |
| `temperature` | 温度 |
| `maxTokens` | 最大 token |
| `jsonMode` | `RESPONSE_FORMAT` / `PROMPT_ONLY` / `NONE` |
| `enabled` | 是否启用 |
| `isDefault` | 是否默认 provider |
| `priority` | 预留给后续 fallback |
| `extraConfigJson` | 供应商特有参数 |
| `createdBy/updatedBy` | 审计字段 |
| `createdAt/updatedAt` | 审计字段 |
| `version` | 乐观锁版本号 |

说明：

- 在线模型与本地模型都存放在 `meta_llm_provider` 中
- 二者通过 `providerMode` 与 `deploymentType` 区分
- 这样前端列表、激活、审计、路由都可以复用同一套主流程

#### B. ProviderSecret

敏感字段不要和普通配置混存。

建议新增元数据表：`meta_llm_provider_secret`

建议字段：

| 字段 | 说明 |
|------|------|
| `providerId` | 关联 provider |
| `secretCiphertext` | 加密后的 token / key |
| `secretMask` | 前端展示用脱敏值 |
| `secretType` | `API_KEY` / `ACCESS_KEY` / `SECRET_KEY` |
| `encryptionKeyVersion` | 密钥版本 |
| `updatedAt` | 最近更新时间 |

说明：

- 对纯本地模型（如本机 Ollama）允许无 secret
- 对本地网关但带鉴权的模型，仍走 secret 表
- 如果项目希望首期简化为“一张表先落地”，也可以先将密文字段放在主表中，但从安全性和后续扩展看，**更推荐主表 + 密钥表拆分**

#### C. RoutingPolicy

定义哪个能力使用哪个 provider。

建议新增元数据表：`meta_llm_routing_policy`

建议字段：

| 字段 | 说明 |
|------|------|
| `capabilityCode` | `SQL_GENERATION` / `SQL_REPAIR` / `RESULT_SUMMARY` |
| `providerId` | 当前激活 provider |
| `fallbackProviderId` | 预留字段，V1 可为空 |
| `updatedBy` | 审计字段 |
| `updatedAt` | 审计字段 |

### 7.2 默认值策略

M12 要求默认 DeepSeek，因此建议：

- 初始化数据预置 `deepseek-default`
- `RoutingPolicy` 三个能力默认都指向 `deepseek-default`
- `deepseek-default` 记录写入 `meta_llm_provider`
- 若数据库中无任何配置，则系统回退到本地只读默认配置
- 回退配置仍应指向 DeepSeek，且只允许 `ADMIN` 通过页面覆盖

### 7.3 配置存储建议

建议将“模型清单”和“路由策略”存储在 **meta 库**，而不是继续放在 `application.yml`：

- 这是后台运营配置，不是部署时一次性静态配置
- 需要审计、启停、版本、连接测试
- 需要页面可视化维护

但用于加密的主密钥不要入库，应放在：

- 环境变量
- KMS / Secret Manager
- 本地 profile 安全文件

### 7.4 建议的表结构边界

本次需求至少新增以下元数据表：

| 表名 | 是否必须 | 用途 |
|------|----------|------|
| `meta_llm_provider` | **必须** | 保存在线模型 / 本地模型的主配置 |
| `meta_llm_provider_secret` | 强烈建议 | 保存加密后的密钥信息 |
| `meta_llm_routing_policy` | **必须** | 保存当前能力到模型的绑定关系 |
| `meta_llm_config_audit_log` | 强烈建议 | 保存模型配置管理审计 |

如果首期只允许新增一张业务主表，也至少要先落 `meta_llm_provider`，但文档建议实现时一并补齐 `secret` 与 `routing` 两张表，否则后续还会返工。

### 7.5 建议追加到 `sql/schema-meta.sql` 的 DDL

以下 DDL 基于当前项目元数据库风格设计，适用于 PostgreSQL，建议作为新增片段追加到 [sql/schema-meta.sql](../../sql/schema-meta.sql) 中。
设计原则如下：

- 表名前缀保持 `meta_` / `audit_` 风格
- 主表使用 `serial` / 审计表使用 `bigserial`
- 配置类表保留 `created_at` / `updated_at`
- 审计说明通过 `comment on table/column` 明确语义
- 敏感信息单独入密钥表，不与主配置表混存

```sql
-- ============================================================
-- M12: 多模型配置中心（在线模型 / 本地模型）元数据表
-- 说明：
-- 1. meta_llm_provider: 模型配置主表，保存在线模型与本地模型的公共配置
-- 2. meta_llm_provider_secret: 模型敏感凭据表，保存加密后的 API Key / Secret
-- 3. meta_llm_routing_policy: LLM 能力到模型配置的路由表
-- 4. meta_llm_config_audit_log: 模型配置管理审计表
-- ============================================================

create table meta_llm_provider
(
    id               serial
        primary key,
    provider_key     varchar(100)                             not null
        unique,
    display_name     varchar(100)                             not null,
    provider_mode    varchar(20)                              not null
        constraint meta_llm_provider_mode_check
            check ((provider_mode)::text = any
                   ((array ['ONLINE'::character varying, 'LOCAL'::character varying])::text[])),
    vendor_type      varchar(50)                              not null
        constraint meta_llm_provider_vendor_check
            check ((vendor_type)::text = any
                   ((array ['OPENAI_COMPATIBLE'::character varying,
                            'GEMINI'::character varying,
                            'ANTHROPIC'::character varying,
                            'VOLCANO_NATIVE'::character varying,
                            'OLLAMA'::character varying,
                            'LOCAL_GATEWAY'::character varying,
                            'CUSTOM'::character varying])::text[])),
    deployment_type  varchar(30) default 'SAAS'::character varying not null
        constraint meta_llm_provider_deploy_check
            check ((deployment_type)::text = any
                   ((array ['SAAS'::character varying,
                            'OLLAMA'::character varying,
                            'LOCAL_GATEWAY'::character varying])::text[])),
    model_code       varchar(100)                             not null,
    base_url         text,
    host             varchar(255),
    port             integer,
    endpoint_path    varchar(255),
    auth_type        varchar(30) default 'NONE'::character varying not null
        constraint meta_llm_provider_auth_check
            check ((auth_type)::text = any
                   ((array ['BEARER'::character varying,
                            'API_KEY'::character varying,
                            'AKSK'::character varying,
                            'NONE'::character varying])::text[])),
    json_mode        varchar(30) default 'RESPONSE_FORMAT'::character varying not null
        constraint meta_llm_provider_json_mode_check
            check ((json_mode)::text = any
                   ((array ['RESPONSE_FORMAT'::character varying,
                            'PROMPT_ONLY'::character varying,
                            'HYBRID'::character varying,
                            'NONE'::character varying])::text[])),
    timeout_seconds  integer     default 60,
    temperature      numeric(4,2) default 0.10,
    max_tokens       integer     default 2000,
    enabled          boolean     default true,
    is_default       boolean     default false,
    priority         integer     default 0,
    extra_config     jsonb,
    last_test_status varchar(20),
    last_test_message text,
    last_tested_at   timestamp,
    created_at       timestamp   default now(),
    created_by       varchar(50),
    updated_at       timestamp   default now(),
    updated_by       varchar(50),
    constraint meta_llm_provider_port_check
        check (port is null or (port >= 1 and port <= 65535)),
    constraint meta_llm_provider_timeout_check
        check (timeout_seconds is null or timeout_seconds > 0),
    constraint meta_llm_provider_tokens_check
        check (max_tokens is null or max_tokens > 0),
    constraint meta_llm_provider_temperature_check
        check (temperature is null or (temperature >= 0 and temperature <= 2)),
    constraint meta_llm_provider_local_host_check
        check (
            provider_mode <> 'LOCAL'
            or host is not null
        )
);

comment on table meta_llm_provider is 'LLM 模型配置主表，统一保存在线模型与本地模型配置，仅系统管理员可维护';
comment on column meta_llm_provider.provider_key is '系统唯一键，例如 deepseek-default、ollama-qwen-local';
comment on column meta_llm_provider.display_name is '前端展示名称';
comment on column meta_llm_provider.provider_mode is '模型模式：ONLINE=在线模型，LOCAL=本地模型';
comment on column meta_llm_provider.vendor_type is '协议/厂商类型：OPENAI_COMPATIBLE、GEMINI、ANTHROPIC、VOLCANO_NATIVE、OLLAMA、LOCAL_GATEWAY、CUSTOM';
comment on column meta_llm_provider.deployment_type is '部署类型：SAAS=公网在线模型，OLLAMA=本地 Ollama，LOCAL_GATEWAY=内网/本地网关';
comment on column meta_llm_provider.model_code is '实际调用的模型编码，如 deepseek-chat、gpt-4.1-mini、qwen2.5';
comment on column meta_llm_provider.base_url is '模型服务基础 URL，在线模型与本地网关均可使用';
comment on column meta_llm_provider.host is '本地模型主机地址，LOCAL 模式必填';
comment on column meta_llm_provider.port is '本地模型端口';
comment on column meta_llm_provider.endpoint_path is '调用端点路径，如 /chat/completions、/v1/chat/completions';
comment on column meta_llm_provider.auth_type is '鉴权类型：BEARER/API_KEY/AKSK/NONE';
comment on column meta_llm_provider.json_mode is '结构化输出策略：RESPONSE_FORMAT/PROMPT_ONLY/HYBRID/NONE';
comment on column meta_llm_provider.timeout_seconds is '调用超时时间，单位秒';
comment on column meta_llm_provider.temperature is '采样温度';
comment on column meta_llm_provider.max_tokens is '单次调用最大生成 token 数';
comment on column meta_llm_provider.enabled is '是否启用该模型配置';
comment on column meta_llm_provider.is_default is '是否为默认模型配置；默认预置一条 DeepSeek 记录';
comment on column meta_llm_provider.priority is '优先级，预留给后续 fallback / 路由扩展';
comment on column meta_llm_provider.extra_config is '供应商特有扩展配置，JSONB 存储';
comment on column meta_llm_provider.last_test_status is '最近一次连接测试结果：SUCCESS/FAILED';
comment on column meta_llm_provider.last_test_message is '最近一次连接测试返回消息或错误摘要';
comment on column meta_llm_provider.last_tested_at is '最近一次连接测试时间';
comment on column meta_llm_provider.created_by is '创建人用户名或账号标识';
comment on column meta_llm_provider.updated_by is '最后更新人用户名或账号标识';

alter table meta_llm_provider
    owner to postgres;

create index idx_meta_llm_provider_mode
    on meta_llm_provider (provider_mode);

create index idx_meta_llm_provider_vendor
    on meta_llm_provider (vendor_type);

create index idx_meta_llm_provider_enabled
    on meta_llm_provider (enabled);

create unique index uk_meta_llm_provider_default
    on meta_llm_provider (is_default)
    where (is_default = true);


create table meta_llm_provider_secret
(
    id                     serial
        primary key,
    provider_id            integer      not null
        references meta_llm_provider
            on delete cascade,
    secret_type            varchar(30)  not null
        constraint meta_llm_secret_type_check
            check ((secret_type)::text = any
                   ((array ['API_KEY'::character varying,
                            'ACCESS_KEY'::character varying,
                            'SECRET_KEY'::character varying,
                            'TOKEN'::character varying])::text[])),
    secret_ciphertext      text         not null,
    secret_mask            varchar(255),
    encryption_key_version varchar(50),
    created_at             timestamp default now(),
    updated_at             timestamp default now(),
    unique (provider_id, secret_type)
);

comment on table meta_llm_provider_secret is 'LLM 模型凭据表，保存加密后的敏感认证信息，不对前端返回明文';
comment on column meta_llm_provider_secret.provider_id is '关联 meta_llm_provider.id';
comment on column meta_llm_provider_secret.secret_type is '密钥类型：API_KEY/ACCESS_KEY/SECRET_KEY/TOKEN';
comment on column meta_llm_provider_secret.secret_ciphertext is '服务端加密后的密文';
comment on column meta_llm_provider_secret.secret_mask is '脱敏后的展示值，例如 sk-****abcd';
comment on column meta_llm_provider_secret.encryption_key_version is '加密主密钥版本号，便于密钥轮换';

alter table meta_llm_provider_secret
    owner to postgres;

create index idx_meta_llm_secret_provider
    on meta_llm_provider_secret (provider_id);


create table meta_llm_routing_policy
(
    id                   serial
        primary key,
    capability_code      varchar(30)                           not null
        unique
        constraint meta_llm_routing_capability_check
            check ((capability_code)::text = any
                   ((array ['SQL_GENERATION'::character varying,
                            'SQL_REPAIR'::character varying,
                            'RESULT_SUMMARY'::character varying])::text[])),
    provider_id          integer                               not null
        references meta_llm_provider,
    fallback_provider_id integer
        references meta_llm_provider,
    active               boolean     default true,
    created_at           timestamp   default now(),
    created_by           varchar(50),
    updated_at           timestamp   default now(),
    updated_by           varchar(50),
    constraint meta_llm_routing_fallback_check
        check (fallback_provider_id is null or fallback_provider_id <> provider_id)
);

comment on table meta_llm_routing_policy is 'LLM 能力路由表，定义 generateSQL/repairSQL/summarizeResults 使用哪个模型配置';
comment on column meta_llm_routing_policy.capability_code is '能力编码：SQL_GENERATION、SQL_REPAIR、RESULT_SUMMARY';
comment on column meta_llm_routing_policy.provider_id is '主模型配置 ID';
comment on column meta_llm_routing_policy.fallback_provider_id is '备用模型配置 ID，V1 可为空';
comment on column meta_llm_routing_policy.active is '该能力路由是否启用';

alter table meta_llm_routing_policy
    owner to postgres;

create index idx_meta_llm_routing_provider
    on meta_llm_routing_policy (provider_id);

create index idx_meta_llm_routing_fallback
    on meta_llm_routing_policy (fallback_provider_id);


create table meta_llm_config_audit_log
(
    id             bigserial
        primary key,
    request_id     uuid      default gen_random_uuid() not null,
    user_id        integer
        references sys_user,
    provider_id    integer
        references meta_llm_provider
            on delete set null,
    operation_type varchar(30)                         not null,
    target_type    varchar(30) default 'LLM_PROVIDER'::character varying,
    target_id      varchar(50),
    detail         jsonb,
    ip_address     varchar(50),
    created_at     timestamp default now()
);

comment on table meta_llm_config_audit_log is 'LLM 模型配置管理审计表，记录新增/修改/启停/激活/测试等操作';
comment on column meta_llm_config_audit_log.operation_type is '操作类型：LLM_PROVIDER_CREATE/UPDATE/ENABLE/DISABLE/ACTIVATE/TEST/ROUTING_UPDATE 等';
comment on column meta_llm_config_audit_log.target_type is '审计目标类型，默认 LLM_PROVIDER';
comment on column meta_llm_config_audit_log.target_id is '被操作对象的主键或业务键';
comment on column meta_llm_config_audit_log.detail is '操作详情 JSON，建议保存变更前后摘要与测试结果';

alter table meta_llm_config_audit_log
    owner to postgres;

create index idx_meta_llm_audit_user
    on meta_llm_config_audit_log (user_id);

create index idx_meta_llm_audit_provider
    on meta_llm_config_audit_log (provider_id);

create index idx_meta_llm_audit_type
    on meta_llm_config_audit_log (operation_type);

create index idx_meta_llm_audit_created
    on meta_llm_config_audit_log (created_at desc);
```

### 7.6 DDL 设计说明

#### 7.6.1 为什么主表与密钥表拆分

原因有三点：

- 普通配置读频率高，密钥读频率低，拆分后更容易做权限控制
- 可避免模型列表接口误把敏感信息带出
- 后续做密钥轮换、单独加密、脱敏展示都更容易

#### 7.6.2 为什么在线模型与本地模型共用 `meta_llm_provider`

虽然页面入口分成“在线模型”和“本地模型”，但从系统视角看，它们本质上都是“可路由的 LLM provider”。  
放在同一主表有三个好处：

- 列表、激活、测试、审计、路由逻辑统一
- 上层 `ProviderRoutingResolver` 无需关心来源是在线还是本地
- 后续新增内网代理模型时不需要再起一套新表

#### 7.6.3 为什么还需要 `meta_llm_routing_policy`

如果把“当前激活模型”只保存在 provider 表的 `is_default` 字段中，短期能工作，但会限制后续扩展。  
单独拆出路由表后，后续可以自然扩展为：

- `generateSQL` 用强模型
- `summarizeResults` 用低成本模型
- `repairSQL` 用稳定性更高的模型

V1 即使三种能力都指向同一条 DeepSeek 配置，路由表仍然值得保留。

#### 7.6.4 默认 DeepSeek 初始化建议

初始化时建议写入以下最小数据：

- `meta_llm_provider` 中插入一条 `provider_key='deepseek-default'`
- `provider_mode='ONLINE'`
- `vendor_type='OPENAI_COMPATIBLE'`
- `model_code='deepseek-chat'`
- `is_default=true`
- `enabled=true`

同时在 `meta_llm_routing_policy` 中插入三条能力路由：

- `SQL_GENERATION -> deepseek-default`
- `SQL_REPAIR -> deepseek-default`
- `RESULT_SUMMARY -> deepseek-default`

## 8. 后端架构设计

### 8.1 保持 `LLMService` 不变

这是本次设计最重要的兼容原则。

不建议修改 `OrchestratorService` 对 `LLMService` 的依赖方式。  
推荐将当前 `OpenAILLMService` 演进为 `UnifiedLLMService`，只负责：

- prompt 组装
- 结构化字段提取
- 错误分类
- 重试编排
- 审计上下文补充

协议相关逻辑全部下沉。

### 8.2 新增协议驱动层

建议新增接口：

```text
LLMDriver
  - supports(vendorType)
  - validateConfig(providerConfig)
  - chat(systemPrompt, userPrompt, options, providerConfig, secret) -> DriverResponse
```

建议新增实现：

- `OpenAICompatibleDriver`
- `GeminiDriver`
- `AnthropicDriver`
- `VolcanoNativeDriver`
- `LocalOpenAICompatibleDriver`（可选，若本地网关与在线协议一致可直接复用 `OpenAICompatibleDriver`）

说明：

- DeepSeek / Qwen / 一部分 Seed 网关可先复用 `OpenAICompatibleDriver`
- Gemini / Anthropic / 原生火山协议各自单独实现

### 8.3 新增路由解析层

建议新增：

```text
ProviderRoutingResolver
  - resolve(capabilityCode) -> ActiveProviderContext
```

职责：

- 读取 `RoutingPolicy`
- 校验 provider 是否启用
- 装配 ProviderConfig + Secret + Driver
- 处理默认回退
- 屏蔽“在线模型 / 本地模型”差异，使上层 `LLMService` 无感知

### 8.4 新增配置服务层

建议新增服务：

- `ProviderConfigService`
- `ProviderSecretService`
- `RoutingPolicyService`
- `ModelConnectionTestService`
- `ModelConfigAuditService`

各自职责：

| 服务 | 职责 |
|------|------|
| `ProviderConfigService` | CRUD、启停、默认值维护、字段校验 |
| `ProviderSecretService` | 密钥加解密、脱敏展示、更新轮转 |
| `RoutingPolicyService` | 能力到 provider 的绑定与切换 |
| `ModelConnectionTestService` | 从后端发起连通性测试 |
| `ModelConfigAuditService` | 记录管理动作，不混入查询审计表 |

### 8.5 新增控制器 API

建议新增管理 API：

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/v1/admin/llm/providers` | 分页查询 provider 列表 |
| `POST` | `/v1/admin/llm/providers` | 新建 provider |
| `PUT` | `/v1/admin/llm/providers/{id}` | 编辑 provider |
| `GET` | `/v1/admin/llm/providers/{id}` | 查询 provider 详情 |
| `POST` | `/v1/admin/llm/providers/{id}/enable` | 启用 |
| `POST` | `/v1/admin/llm/providers/{id}/disable` | 停用 |
| `POST` | `/v1/admin/llm/providers/{id}/test` | 连接测试 |
| `GET` | `/v1/admin/llm/routing-policies` | 查询能力路由 |
| `PUT` | `/v1/admin/llm/routing-policies/{capability}` | 更新能力路由 |
| `GET` | `/v1/admin/llm/audit-logs` | 查询模型配置审计 |

权限建议：

- 仅**系统管理员（`ADMIN`）**可访问模型配置管理 API
- `MANAGER` 与 `USER` 默认无权查看、无权编辑
- 如后续确需开放只读视图，应单独评审，不在 M12 V1 范围

### 8.6 审计设计

当前 `audit_query_logs` 适合记录查询过程，不适合记录后台配置管理。  
建议新增“模型配置审计”能力，最少记录：

- 操作人
- 操作时间
- 操作类型：新增 / 编辑 / 删除 / 启停 / 激活 / 测试
- 目标 provider
- 变更前摘要
- 变更后摘要
- 结果状态
- 脱敏后的敏感字段摘要

同时，查询链路中的请求审计应补充：

- `providerKey`
- `vendorType`
- `modelCode`
- `requestLatency`
- `tokenUsage`（如供应商返回）

这样才能满足“每次请求必须可审计”的现有红线。

### 8.7 缓存一致性设计

这是 M12 必须补充的重点。

当前 `QueryCacheService` 的 key 说明是 `md5(question+orgId)`。  
如果激活模型切换了，但缓存键没有包含模型维度，则用户可能继续命中旧模型生成的结果。

建议两种方式二选一：

1. **推荐方案**：缓存键加入 `providerKey + modelCode + routingVersion`
2. **兜底方案**：激活模型变更后主动清理查询缓存

更推荐第一种，因为：

- 便于灰度
- 保留历史缓存隔离
- 不会因为一次切换清空全站缓存

## 9. 前端页面设计

### 9.1 页面入口

建议新增管理页：`模型配置` / `LLM 配置中心`

建议位于现有 Admin 导航中，与以下页面同级：

- 数据源
- 向量管理
- 审计日志

原因：

- 它本质上也是治理配置，而不是查询域功能

### 9.2 页面拆分

建议拆为 3 个区域，而不是一个大表单：

#### A. Provider 列表区

展示：

- 展示名
- 供应商类型
- 模型编码
- 基础地址
- 认证类型
- 是否启用
- 是否默认
- 最近测试状态
- 最近更新时间

操作：

- 新建
- 编辑
- 启用 / 停用
- 测试连接
- 查看脱敏凭据状态

列表建议新增筛选：

- 模型类型：在线 / 本地
- 协议类型
- 是否启用
- 是否当前激活

#### B. 路由策略区

展示每个能力当前使用哪个 provider：

- SQL 生成
- SQL 修复
- 结果摘要

V1 可以全部绑定同一个 provider，但页面层仍建议保留“按能力路由”的结构，这样后续扩展不会推倒重来。

#### C. 审计区

展示最近的配置变更日志：

- 谁改了什么
- 改动结果
- 测试是否成功

### 9.3 新建配置交互

建议在“新建模型配置”时先弹出一个模式选择：

1. 在线模型
2. 本地模型

然后进入不同表单。

#### 在线模型表单

适用于：

- DeepSeek
- OpenAI
- Qwen
- Gemini
- Seed

核心字段：

- `displayName`
- `vendorType`
- `modelCode`
- `baseUrl`
- `endpointPath`
- `authType`
- `secret`
- `timeoutSeconds`
- `temperature`
- `maxTokens`
- `jsonMode`
- `enabled`

#### 本地模型表单

适用于：

- Ollama
- 本地 OpenAI 兼容网关
- 内网模型代理服务

核心字段：

- `displayName`
- `deploymentType`
- `modelCode`
- `host`
- `port`
- `baseUrl`
- `endpointPath`
- `authType`
- `secret`（可选）
- `timeoutSeconds`
- `temperature`
- `maxTokens`
- `jsonMode`
- `enabled`

建议体验：

- 本地模型配置可使用独立页面，也可使用弹框
- 若字段较多，优先独立抽屉或详情页，避免一个弹框塞过多配置项

### 9.4 表单字段建议

建议表单字段：

- `displayName`
- `providerMode`
- `vendorType`
- `deploymentType`
- `modelCode`
- `baseUrl`
- `host`
- `port`
- `endpointPath`
- `authType`
- `secret`
- `timeoutSeconds`
- `temperature`
- `maxTokens`
- `jsonMode`
- `enabled`
- `extraConfigJson`

页面行为建议：

- 新建时显示明文输入
- 保存后再次编辑只显示脱敏值
- 如不重新输入 secret，则保持原密钥不变
- 提交前做字段级联校验

### 9.5 前端权限控制

前端需增加严格权限控制：

- 仅系统管理员显示“模型配置”菜单
- 非 `ADMIN` 用户访问对应路由时直接拦截
- 前端隐藏不等于安全，后端仍需强校验 RBAC

### 9.6 前端 API 与 Mock

为了贴合当前项目结构，前端应同时补齐：

- `frontend/src/services/modelConfigApi.ts`
- `frontend/src/types/model-config.ts`
- `frontend/src/mocks/handlers/modelConfigHandlers.ts`
- 对应 mock data

这样才能满足“API contract aligned with frontend Mock”的 DoD。

## 10. 安全设计

### 10.1 敏感信息处理

必须满足：

- 前端永不缓存明文 key
- 日志中永不打印明文 key
- 审计中只展示脱敏片段
- 数据库存储使用服务端加密

不建议：

- 将 API key 明文存入 `meta_table` 或普通配置表
- 允许前端读取完整旧密钥

### 10.2 出网与合规

虽然项目坚持“LLM 只看元数据，不看业务数据”，但多在线模型接入后，仍需注意：

- 元数据本身可能包含业务术语和表注释
- 不同供应商的数据合规与网络策略不同
- 企业内网环境可能不允许任意出网

建议增加：

- provider allowlist
- 出网地址白名单
- 连接测试仅允许到配置的 baseUrl
- 可在配置层标注“是否允许生产使用”

### 10.3 权限边界

建议：

- 仅系统管理员（`ADMIN`）可编辑模型配置与路由
- 查询用户无权感知或切换模型
- `USER` 侧返回结果中不暴露 provider secret
- 如需展示“本次由哪个模型执行”，也只展示 `displayName/modelCode`

## 11. 协议兼容策略

### 11.1 建议的接入分期

#### 第一批：OpenAI 兼容模型

优先支持：

- DeepSeek
- Qwen（OpenAI 兼容网关）
- OpenAI
- Seed / Ark 的 OpenAI 兼容网关
- 本地 OpenAI 兼容网关
- 本地 Ollama（若走 OpenAI 兼容适配层）

原因：

- 与当前实现最接近
- 可最快落地页面化配置

#### 第二批：非 OpenAI 兼容模型

后续支持：

- Gemini
- Anthropic Claude
- Volcano Native API

原因：

- 需要 Driver 层完成后才能优雅接入

### 11.2 JSON 输出策略建议

不是所有模型都稳定支持 `response_format: json_object`。  
因此建议将结构化输出模式做成可配置策略：

- `RESPONSE_FORMAT`
- `PROMPT_ONLY`
- `HYBRID`

这样可以避免把 DeepSeek 的做法硬套到 Gemini 或其他模型上。

## 12. 运行时优化建议

### 12.1 连接池复用

当前 `LLMAsyncExecutor` 每次调用都新建 `OkHttpClient`，这在多模型场景下更不合适。  
M12 实现时建议统一使用可复用的 HTTP client 工厂，按 provider 维度复用连接池。

### 12.2 配置热加载

模型配置页上线后，管理员修改配置应尽量做到：

- 不重启服务即可生效
- 路由策略在短时间内刷新

建议：

- 读取数据库配置并加本地短 TTL 缓存
- 路由变更后定向失效

### 12.3 失败保护

建议预留这些状态：

- `ENABLED`
- `DISABLED`
- `TEST_FAILED`
- `INVALID_CONFIG`

并在运行时遵循 fail-closed：

- 激活 provider 无效时，不静默切到未知配置
- 优先回退到默认 DeepSeek
- 若默认也不可用，则明确报错并写审计

## 13. 风险与待优化点

### 13.1 不建议把“模型名”作为唯一业务主键

同一个供应商可能有多个模型版本，同一个模型也可能有多套网关配置。  
应使用 `providerKey` 作为系统唯一标识。

### 13.2 不建议把“激活模型”只放前端本地状态

激活状态必须由后端统一管理，否则多实例部署与多用户访问时会失真。

### 13.3 不建议沿用单一 `llm.provider` 配置结构

那样只是把现在的“单模型静态配置”扩写成“多段静态 YAML”，依旧不具备后台运营能力。

### 13.4 不建议一开始就做自动 fallback

自动 fallback 很诱人，但它会带来：

- 额外成本
- 结果不一致
- 审计复杂度增加

V1 先做单活切换最稳妥。

### 13.5 要补“模型版本进入审计和缓存键”

这是后续排障、复盘、比对效果最有价值的信息之一，必须在设计阶段就纳入。

## 14. 建议的实施顺序

### Phase 1：治理与配置域落地

- 在元数据数据库中建立 `meta_llm_provider` 主表
- 视实现复杂度同步建立 `meta_llm_provider_secret` / `meta_llm_routing_policy` / `meta_llm_config_audit_log`
- 新增后台 CRUD、测试、激活 API
- 新增在线模型 / 本地模型双入口管理页面与 Mock
- 默认 DeepSeek 生效

### Phase 2：运行时路由改造

- `OpenAILLMService` 演进为 `UnifiedLLMService`
- 新增 `DriverRegistry` / `ProviderRoutingResolver`
- 落地 `OpenAICompatibleDriver`
- 查询审计补充 provider 维度
- 查询缓存加入 provider 维度

### Phase 3：协议扩展

- 接入 Gemini / Anthropic / Volcano Native Driver
- 补全供应商差异化参数
- 完善测试矩阵

## 15. M12 待办清单

### P0：必须完成

- [ ] 明确 M12 范围：支持在线 chat 模型和本地 chat 模型，不包含 embedding
- [ ] 明确权限边界：仅系统管理员（`ADMIN`）可写模型配置
- [ ] 在元数据数据库中新增 `meta_llm_provider` 主表
- [ ] 设计并落地模型配置、密钥、路由、审计四类数据模型
- [ ] 设计在线模型 / 本地模型双入口页面与表单
- [ ] 设计默认 DeepSeek 初始化方案与无配置回退方案
- [ ] 设计查询缓存与模型切换的一致性策略
- [ ] 设计查询审计补充字段：provider/model/latency/token usage

### P1：建议首批实现

- [ ] 新增模型配置管理页、在线模型表单、本地模型表单、连接测试交互
- [ ] 新增后端 CRUD / 激活 / 测试 API
- [ ] 引入 `UnifiedLLMService + DriverRegistry + OpenAICompatibleDriver`
- [ ] 支持 DeepSeek / OpenAI / Qwen / Seed(OpenAI 兼容网关) / 本地 OpenAI 兼容网关 配置化接入
- [ ] 完成模型密钥加密存储与脱敏展示
- [ ] 完成模型配置变更审计
- [ ] 完成前端 Mock 与 API contract 同步

### P2：后续增强

- [ ] 接入 Gemini Driver
- [ ] 接入 Anthropic Driver
- [ ] 接入 Volcano Native Driver
- [ ] 按能力配置不同 provider
- [ ] 健康状态监测与失败标记
- [ ] 成本/延迟统计看板

## 16. 最终建议

这个需求值得做，而且和当前项目的 `LLMService` 接口式设计是匹配的。  
但真正要做成“可运营、可审计、可扩展”的企业能力，必须把它定义为：

- 后台治理能力
- 运行时路由能力
- 协议扩展能力

而不是一个单纯的“前端新增页面”。

M12 最合理的落地方式是：

1. 先以 **DeepSeek 默认 + 全局单活模型** 为主线
2. 先完成 **OpenAI 兼容模型配置化**
3. 再通过 **Provider Driver** 扩展 Gemini / Anthropic / 原生 Seed/Volcano 协议

这样既不破坏现有项目的四条红线，也能为后续多模型治理留出清晰演进路径。
