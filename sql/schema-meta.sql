create table public.meta_datasource
(
    id          serial
        primary key,
    name        varchar(255) not null,
    db_type     varchar(50)  not null,
    jdbc_url    text         not null,
    username    varchar(255),
    password    varchar(255),
    description text,
    read_only   boolean   default true,
    active      boolean   default true,
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP
);

alter table public.meta_datasource
    owner to postgres;

create table public.meta_tables
(
    id            serial
        primary key,
    datasource_id integer
        references public.meta_datasource,
    schema_name   varchar(255),
    table_name    varchar(255) not null,
    label         varchar(255),
    description   text,
    table_type    varchar(50),
    domain        varchar(255),
    active        boolean   default true,
    published     boolean   default false,
    created_at    timestamp default CURRENT_TIMESTAMP,
    updated_at    timestamp default CURRENT_TIMESTAMP
);

alter table public.meta_tables
    owner to postgres;

comment on column public.meta_tables.published is '是否已发布为正式可查询表。DDL 导入默认 false，管理员确认后设为 true。';

create index idx_meta_tables_datasource
    on public.meta_tables (datasource_id);

create index idx_meta_tables_schema
    on public.meta_tables (schema_name);

create index idx_meta_tables_table_name
    on public.meta_tables (table_name);

create unique index uk_meta_tables_identity
    on public.meta_tables (datasource_id, COALESCE(schema_name, ''::character varying), table_name);

create table public.meta_columns
(
    id               serial
        primary key,
    table_id         integer      not null
        references public.meta_tables,
    column_name      varchar(255) not null,
    label            varchar(255),
    data_type        varchar(100),
    description      text,
    sensitivity      varchar(50) default 'normal'::character varying,
    comment_source   varchar(50) default 'manual'::character varying,
    is_metric        boolean     default false,
    aggregation_type varchar(50),
    sort_order       integer     default 0,
    active           boolean     default true,
    created_at       timestamp   default CURRENT_TIMESTAMP,
    updated_at       timestamp   default CURRENT_TIMESTAMP
);

alter table public.meta_columns
    owner to postgres;

create index idx_meta_columns_table
    on public.meta_columns (table_id);

create index idx_meta_columns_name
    on public.meta_columns (column_name);

create unique index uk_meta_columns_table_column
    on public.meta_columns (table_id, column_name);

create table public.meta_join_edges
(
    id            serial
        primary key,
    from_table_id integer      not null
        references public.meta_tables,
    to_table_id   integer      not null
        references public.meta_tables,
    from_column   varchar(255) not null,
    to_column     varchar(255) not null,
    join_type     varchar(50) default 'inner'::character varying,
    description   text,
    active        boolean     default true,
    created_at    timestamp   default CURRENT_TIMESTAMP,
    updated_at    timestamp   default CURRENT_TIMESTAMP
);

alter table public.meta_join_edges
    owner to postgres;

create index idx_meta_join_from
    on public.meta_join_edges (from_table_id);

create index idx_meta_join_to
    on public.meta_join_edges (to_table_id);

create table public.meta_ddl_uploads
(
    id            serial
        primary key,
    filename      varchar(255),
    file_size     bigint,
    table_count   integer,
    column_count  integer,
    status        varchar(50) default 'pending'::character varying,
    error_message text,
    uploaded_by   integer,
    created_at    timestamp   default CURRENT_TIMESTAMP
);

alter table public.meta_ddl_uploads
    owner to postgres;

create index idx_meta_ddl_uploads_status
    on public.meta_ddl_uploads (status);

create index idx_meta_ddl_uploads_user
    on public.meta_ddl_uploads (uploaded_by);

create table public.dim_org
(
    id        integer              not null
        primary key,
    org_name  varchar(100)         not null,
    parent_id integer
        references public.dim_org
            on delete restrict,
    org_level smallint             not null,
    org_code  varchar(50)          not null
        unique,
    is_active boolean default true not null,
    synced_at timestamp
);

comment on table public.dim_org is '组织维表（从业务库同步）';

alter table public.dim_org
    owner to postgres;

create table public.meta_glossary
(
    id          serial
        primary key,
    term        varchar(255) not null,
    definition  text,
    sql_snippet text,
    aliases     text,
    org_scope   integer
        references public.dim_org,
    active      boolean   default true,
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP
);

alter table public.meta_glossary
    owner to postgres;

create index idx_meta_glossary_term
    on public.meta_glossary (term);

create index idx_meta_glossary_org
    on public.meta_glossary (org_scope);

create table public.meta_sql_examples
(
    id          serial
        primary key,
    question    text not null,
    sql_query   text not null,
    domain      varchar(255),
    time_grain  varchar(50),
    org_scope   integer
        references public.dim_org,
    usage_count integer   default 0,
    active      boolean   default true,
    created_at  timestamp default CURRENT_TIMESTAMP,
    updated_at  timestamp default CURRENT_TIMESTAMP
);

alter table public.meta_sql_examples
    owner to postgres;

create index idx_meta_sql_examples_domain
    on public.meta_sql_examples (domain);

create index idx_meta_sql_examples_time
    on public.meta_sql_examples (time_grain);

create table public.audit_query_logs
(
    id                serial
        primary key,
    request_id        varchar(255) not null
        unique,
    user_id           integer,
    org_id            integer
        constraint audit_query_log_org_id_fkey
            references public.dim_org,
    question          text,
    prompt_context    text,
    sql               text,
    status            varchar(50),
    error_message     text,
    execution_time_ms bigint,
    rows_returned     integer,
    confidence        numeric(5, 4),
    feedback          varchar(50),
    feedback_comment  text,
    retry_count       integer   default 0,
    created_at        timestamp default CURRENT_TIMESTAMP,
    updated_at        timestamp default CURRENT_TIMESTAMP,
    summary           text,
    recommended_chart varchar(50),
    cache_hit         boolean   default false,
    feedback_rating   smallint,
    feedback_at       timestamp,
    is_favorite       boolean   default false,
    favorite_at       timestamp
);

alter table public.audit_query_logs
    owner to postgres;

create index idx_audit_user
    on public.audit_query_logs (user_id);

create index idx_audit_status
    on public.audit_query_logs (status);

create index idx_audit_created
    on public.audit_query_logs (created_at);

create index idx_dim_org_parent
    on public.dim_org (parent_id);

create index idx_dim_org_code
    on public.dim_org (org_code);

create table public.sys_user
(
    id            serial
        primary key,
    username      varchar(50)                                   not null
        unique,
    password_hash varchar(200)                                  not null,
    display_name  varchar(100),
    role          varchar(20) default 'USER'::character varying not null
        constraint sys_user_role_check
            check ((role)::text = ANY
                   ((ARRAY ['ADMIN'::character varying, 'MANAGER'::character varying, 'USER'::character varying])::text[])),
    email         varchar(200),
    phone         varchar(20),
    is_active     boolean     default true,
    last_login_at timestamp,
    created_at    timestamp   default now(),
    created_by    varchar(50),
    updated_at    timestamp   default now(),
    updated_by    varchar(50)
);

comment on table public.sys_user is '系统账号表';

comment on column public.sys_user.role is 'ADMIN=超管(手动INSERT)，MANAGER=组织管理员，USER=查询用户';

alter table public.sys_user
    owner to postgres;

create index idx_sys_user_role
    on public.sys_user (role);

create index idx_sys_user_active
    on public.sys_user (is_active)
    where (is_active = true);

create table public.sys_user_org
(
    id         serial
        primary key,
    user_id    integer                                       not null
        references public.sys_user
            on delete cascade,
    org_id     integer                                       not null,
    scope      varchar(20) default 'SELF'::character varying not null
        constraint sys_user_org_scope_check
            check ((scope)::text = ANY
                   ((ARRAY ['SELF'::character varying, 'CHILDREN'::character varying, 'ALL'::character varying])::text[])),
    is_primary boolean     default false,
    granted_by varchar(50),
    granted_at timestamp   default now(),
    unique (user_id, org_id)
);

comment on table public.sys_user_org is '账号-机构权限映射，org_id 对应业务库 dim_org.id';

comment on column public.sys_user_org.scope is 'SELF=仅本节点，CHILDREN=本节点+直接子节点，ALL=本节点+全部后代';

comment on column public.sys_user_org.is_primary is '默认组织，登录后 OrgSelector 自动选中';

alter table public.sys_user_org
    owner to postgres;

create index idx_suo_user
    on public.sys_user_org (user_id);

create index idx_suo_org
    on public.sys_user_org (org_id);

create table public.audit_operation_logs
(
    id             bigserial
        primary key,
    request_id     uuid      default gen_random_uuid() not null,
    user_id        integer
        references public.sys_user,
    operation_type varchar(30)                         not null,
    target_type    varchar(30),
    target_id      varchar(50),
    detail         jsonb,
    ip_address     varchar(50),
    created_at     timestamp default now()
);

comment on column public.audit_operation_logs.operation_type is 'USER_CREATE/USER_DEACTIVATE/ORG_GRANT/ORG_REVOKE/PASSWORD_CHANGE/EMBEDDING_SYNC 等';

alter table public.audit_operation_logs
    owner to postgres;

create index idx_aol_type
    on public.audit_operation_logs (operation_type);

create index idx_aol_user
    on public.audit_operation_logs (user_id);

create index idx_aol_created
    on public.audit_operation_logs (created_at desc);

-- ============================================================
-- M12: 多模型配置中心（在线模型 / 本地模型）元数据表
-- 说明：
-- 1. meta_llm_provider: 模型配置主表，保存在线模型与本地模型的公共配置。
-- 2. meta_llm_provider_secret: 模型敏感凭据表，保存加密后的 API Key / Secret。
-- 3. meta_llm_routing_policy: LLM 能力到模型配置的路由表。
-- 4. meta_llm_config_audit_log: 模型配置管理审计表。
-- 5. audit_query_logs 追加模型审计字段，用于追踪每次查询使用的模型配置。
--
-- 注意：本脚本不预置默认模型数据。系统启动或查询页检查时，应以
-- meta_llm_provider / meta_llm_routing_policy 中是否存在可用配置作为准入条件；
-- 若无可用模型配置，应提示联系管理员配置模型并禁止发起查询。
-- ============================================================

create table public.meta_llm_provider
(
    id               serial
        primary key,
    provider_key     varchar(100)                                        not null
        unique,
    display_name     varchar(100)                                        not null,
    provider_mode    varchar(20)                                         not null
        constraint meta_llm_provider_mode_check
            check ((provider_mode)::text = any
                   ((array ['ONLINE'::character varying, 'LOCAL'::character varying])::text[])),
    vendor_type      varchar(50)                                         not null
        constraint meta_llm_provider_vendor_check
            check ((vendor_type)::text = any
                   ((array ['OPENAI_COMPATIBLE'::character varying,
                            'GEMINI'::character varying,
                            'ANTHROPIC'::character varying,
                            'VOLCANO_NATIVE'::character varying,
                            'OLLAMA'::character varying,
                            'LOCAL_GATEWAY'::character varying,
                            'CUSTOM'::character varying])::text[])),
    deployment_type  varchar(30) default 'SAAS'::character varying       not null
        constraint meta_llm_provider_deploy_check
            check ((deployment_type)::text = any
                   ((array ['SAAS'::character varying,
                            'OLLAMA'::character varying,
                            'LOCAL_GATEWAY'::character varying])::text[])),
    model_code       varchar(100)                                        not null,
    base_url         text,
    host             varchar(255),
    port             integer,
    endpoint_path    varchar(255) default '/chat/completions'::character varying,
    auth_type        varchar(30) default 'NONE'::character varying       not null
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
    timeout_seconds  integer     default 60                              not null,
    temperature      numeric(4, 2) default 0.10,
    max_tokens       integer     default 2000,
    enabled          boolean     default true                            not null,
    is_default       boolean     default false                           not null,
    priority         integer     default 0                               not null,
    config_version   integer     default 1                               not null,
    extra_config     jsonb,
    last_test_status varchar(20)
        constraint meta_llm_provider_test_status_check
            check (last_test_status is null
                   or (last_test_status)::text = any
                      ((array ['SUCCESS'::character varying,
                               'FAILED'::character varying,
                               'UNKNOWN'::character varying])::text[])),
    last_test_message text,
    last_tested_at   timestamp,
    created_at       timestamp   default now()                           not null,
    created_by       varchar(50),
    updated_at       timestamp   default now()                           not null,
    updated_by       varchar(50),
    constraint meta_llm_provider_key_not_blank
        check (length(btrim((provider_key)::text)) > 0),
    constraint meta_llm_provider_name_not_blank
        check (length(btrim((display_name)::text)) > 0),
    constraint meta_llm_provider_model_not_blank
        check (length(btrim((model_code)::text)) > 0),
    constraint meta_llm_provider_port_check
        check (port is null or (port >= 1 and port <= 65535)),
    constraint meta_llm_provider_timeout_check
        check (timeout_seconds > 0 and timeout_seconds <= 300),
    constraint meta_llm_provider_tokens_check
        check (max_tokens is null or max_tokens > 0),
    constraint meta_llm_provider_temperature_check
        check (temperature is null or (temperature >= 0 and temperature <= 2)),
    constraint meta_llm_provider_version_check
        check (config_version > 0),
    constraint meta_llm_provider_online_base_url_check
        check (provider_mode <> 'ONLINE' or base_url is not null),
    constraint meta_llm_provider_local_endpoint_check
        check (provider_mode <> 'LOCAL' or base_url is not null or host is not null),
    constraint meta_llm_provider_mode_deploy_check
        check (
            (provider_mode = 'ONLINE' and deployment_type = 'SAAS')
            or (provider_mode = 'LOCAL' and deployment_type in ('OLLAMA', 'LOCAL_GATEWAY'))
        )
);

comment on table public.meta_llm_provider is 'LLM 模型配置主表，统一保存在线模型与本地模型配置，仅系统管理员可维护';
comment on column public.meta_llm_provider.provider_key is '系统唯一键，例如 deepseek-default、ollama-qwen-local';
comment on column public.meta_llm_provider.display_name is '前端展示名称';
comment on column public.meta_llm_provider.provider_mode is '模型模式：ONLINE=在线模型，LOCAL=本地模型';
comment on column public.meta_llm_provider.vendor_type is '协议/厂商类型：OPENAI_COMPATIBLE、GEMINI、ANTHROPIC、VOLCANO_NATIVE、OLLAMA、LOCAL_GATEWAY、CUSTOM';
comment on column public.meta_llm_provider.deployment_type is '部署类型：SAAS=公网在线模型，OLLAMA=本地 Ollama，LOCAL_GATEWAY=内网/本地网关';
comment on column public.meta_llm_provider.model_code is '实际调用的模型编码，如 deepseek-chat、gpt-4.1-mini、qwen2.5';
comment on column public.meta_llm_provider.base_url is '模型服务基础 URL，在线模型与本地网关均可使用';
comment on column public.meta_llm_provider.host is '本地模型主机地址；LOCAL 模式可与 base_url 二选一';
comment on column public.meta_llm_provider.port is '本地模型端口';
comment on column public.meta_llm_provider.endpoint_path is '调用端点路径，如 /chat/completions、/v1/chat/completions';
comment on column public.meta_llm_provider.auth_type is '鉴权类型：BEARER/API_KEY/AKSK/NONE';
comment on column public.meta_llm_provider.json_mode is '结构化输出策略：RESPONSE_FORMAT/PROMPT_ONLY/HYBRID/NONE';
comment on column public.meta_llm_provider.timeout_seconds is '调用超时时间，单位秒';
comment on column public.meta_llm_provider.temperature is '采样温度';
comment on column public.meta_llm_provider.max_tokens is '单次调用最大生成 token 数';
comment on column public.meta_llm_provider.enabled is '是否启用该模型配置';
comment on column public.meta_llm_provider.is_default is '是否为默认模型配置；最多允许一条';
comment on column public.meta_llm_provider.priority is '优先级，预留给后续 fallback / 路由扩展';
comment on column public.meta_llm_provider.config_version is '配置版本，模型配置变更时递增，可参与查询缓存隔离';
comment on column public.meta_llm_provider.extra_config is '供应商特有扩展配置，JSONB 存储';
comment on column public.meta_llm_provider.last_test_status is '最近一次连接测试结果：SUCCESS/FAILED/UNKNOWN';
comment on column public.meta_llm_provider.last_test_message is '最近一次连接测试返回消息或错误摘要';
comment on column public.meta_llm_provider.last_tested_at is '最近一次连接测试时间';
comment on column public.meta_llm_provider.created_by is '创建人用户名或账号标识';
comment on column public.meta_llm_provider.updated_by is '最后更新人用户名或账号标识';

alter table public.meta_llm_provider
    owner to postgres;

create index idx_meta_llm_provider_mode
    on public.meta_llm_provider (provider_mode);

create index idx_meta_llm_provider_vendor
    on public.meta_llm_provider (vendor_type);

create index idx_meta_llm_provider_enabled
    on public.meta_llm_provider (enabled);

create unique index uk_meta_llm_provider_default
    on public.meta_llm_provider (is_default)
    where (is_default = true);

create table public.meta_llm_provider_secret
(
    id                     serial
        primary key,
    provider_id            integer      not null
        references public.meta_llm_provider
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
    created_at             timestamp default now() not null,
    created_by             varchar(50),
    updated_at             timestamp default now() not null,
    updated_by             varchar(50),
    unique (provider_id, secret_type),
    constraint meta_llm_secret_ciphertext_not_blank
        check (length(btrim(secret_ciphertext)) > 0)
);

comment on table public.meta_llm_provider_secret is 'LLM 模型凭据表，保存加密后的敏感认证信息，不对前端返回明文';
comment on column public.meta_llm_provider_secret.provider_id is '关联 meta_llm_provider.id';
comment on column public.meta_llm_provider_secret.secret_type is '密钥类型：API_KEY/ACCESS_KEY/SECRET_KEY/TOKEN';
comment on column public.meta_llm_provider_secret.secret_ciphertext is '服务端加密后的密文';
comment on column public.meta_llm_provider_secret.secret_mask is '脱敏后的展示值，例如 sk-****abcd';
comment on column public.meta_llm_provider_secret.encryption_key_version is '加密主密钥版本号，便于密钥轮换';

alter table public.meta_llm_provider_secret
    owner to postgres;

create index idx_meta_llm_secret_provider
    on public.meta_llm_provider_secret (provider_id);

create table public.meta_llm_routing_policy
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
        references public.meta_llm_provider,
    fallback_provider_id integer
        references public.meta_llm_provider,
    active               boolean     default true              not null,
    routing_version      integer     default 1                 not null,
    created_at           timestamp   default now()             not null,
    created_by           varchar(50),
    updated_at           timestamp   default now()             not null,
    updated_by           varchar(50),
    constraint meta_llm_routing_fallback_check
        check (fallback_provider_id is null or fallback_provider_id <> provider_id),
    constraint meta_llm_routing_version_check
        check (routing_version > 0)
);

comment on table public.meta_llm_routing_policy is 'LLM 能力路由表，定义 generateSQL/repairSQL/summarizeResults 使用哪个模型配置';
comment on column public.meta_llm_routing_policy.capability_code is '能力编码：SQL_GENERATION、SQL_REPAIR、RESULT_SUMMARY';
comment on column public.meta_llm_routing_policy.provider_id is '主模型配置 ID';
comment on column public.meta_llm_routing_policy.fallback_provider_id is '备用模型配置 ID，V1 可为空';
comment on column public.meta_llm_routing_policy.active is '该能力路由是否启用';
comment on column public.meta_llm_routing_policy.routing_version is '路由版本，路由变更时递增，可参与查询缓存隔离';

alter table public.meta_llm_routing_policy
    owner to postgres;

create index idx_meta_llm_routing_provider
    on public.meta_llm_routing_policy (provider_id);

create index idx_meta_llm_routing_fallback
    on public.meta_llm_routing_policy (fallback_provider_id);

create index idx_meta_llm_routing_active
    on public.meta_llm_routing_policy (active);

create table public.meta_llm_config_audit_log
(
    id             bigserial
        primary key,
    request_id     uuid      default gen_random_uuid() not null,
    user_id        integer
        references public.sys_user,
    provider_id    integer
        references public.meta_llm_provider
            on delete set null,
    operation_type varchar(40)                         not null,
    target_type    varchar(30) default 'LLM_PROVIDER'::character varying not null,
    target_id      varchar(50),
    status         varchar(20) default 'SUCCESS'::character varying not null
        constraint meta_llm_config_audit_status_check
            check ((status)::text = any
                   ((array ['SUCCESS'::character varying,
                            'FAILED'::character varying])::text[])),
    detail         jsonb,
    ip_address     varchar(50),
    created_at     timestamp default now()             not null
);

comment on table public.meta_llm_config_audit_log is 'LLM 模型配置管理审计表，记录新增/修改/启停/激活/测试等操作';
comment on column public.meta_llm_config_audit_log.operation_type is '操作类型：LLM_PROVIDER_CREATE/UPDATE/ENABLE/DISABLE/ACTIVATE/TEST/ROUTING_UPDATE 等';
comment on column public.meta_llm_config_audit_log.target_type is '审计目标类型，默认 LLM_PROVIDER';
comment on column public.meta_llm_config_audit_log.target_id is '被操作对象的主键或业务键';
comment on column public.meta_llm_config_audit_log.status is '操作结果：SUCCESS/FAILED';
comment on column public.meta_llm_config_audit_log.detail is '操作详情 JSON，建议保存变更前后摘要与测试结果；敏感字段必须脱敏';

alter table public.meta_llm_config_audit_log
    owner to postgres;

create index idx_meta_llm_audit_user
    on public.meta_llm_config_audit_log (user_id);

create index idx_meta_llm_audit_provider
    on public.meta_llm_config_audit_log (provider_id);

create index idx_meta_llm_audit_type
    on public.meta_llm_config_audit_log (operation_type);

create index idx_meta_llm_audit_status
    on public.meta_llm_config_audit_log (status);

create index idx_meta_llm_audit_created
    on public.meta_llm_config_audit_log (created_at desc);

alter table public.audit_query_logs
    add column llm_provider_id integer
        references public.meta_llm_provider
            on delete set null,
    add column llm_provider_key varchar(100),
    add column llm_vendor_type varchar(50),
    add column llm_model_code varchar(100),
    add column llm_routing_version integer,
    add column llm_latency_ms bigint,
    add column llm_prompt_tokens integer,
    add column llm_completion_tokens integer,
    add column llm_total_tokens integer;

comment on column public.audit_query_logs.llm_provider_id is '本次查询使用的 LLM provider ID，通常记录 SQL_GENERATION 路由结果';
comment on column public.audit_query_logs.llm_provider_key is '本次查询使用的 LLM provider_key 快照';
comment on column public.audit_query_logs.llm_vendor_type is '本次查询使用的 LLM vendor_type 快照';
comment on column public.audit_query_logs.llm_model_code is '本次查询使用的模型编码快照';
comment on column public.audit_query_logs.llm_routing_version is '本次查询使用的路由版本快照';
comment on column public.audit_query_logs.llm_latency_ms is 'LLM 主调用耗时，单位毫秒';
comment on column public.audit_query_logs.llm_prompt_tokens is '供应商返回的 prompt token 数';
comment on column public.audit_query_logs.llm_completion_tokens is '供应商返回的 completion token 数';
comment on column public.audit_query_logs.llm_total_tokens is '供应商返回的总 token 数';

create index idx_audit_llm_provider
    on public.audit_query_logs (llm_provider_id);

create index idx_audit_llm_model
    on public.audit_query_logs (llm_provider_key, llm_model_code);
