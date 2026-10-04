create table public.dim_org
(
    id        integer              not null
        primary key,
    org_name  varchar(100)         not null,
    parent_id integer
        references public.dim_org,
    org_level smallint             not null
        constraint dim_org_org_level_check
            check (org_level = ANY (ARRAY [1, 2, 3])),
    org_code  varchar(50)          not null
        unique,
    is_active boolean default true not null
);

comment on table public.dim_org is '组织维表，用于存储母公司、子公司、部门等组织层级结构信息。';

comment on column public.dim_org.id is '组织主键ID，手工指定，便于演示时固定引用。';

comment on column public.dim_org.org_name is '组织名称，如母公司、子公司A、销售部等。';

comment on column public.dim_org.parent_id is '父级组织ID，根节点为空；子公司指向母公司，部门指向所属子公司。';

comment on column public.dim_org.org_level is '组织层级，1=母公司，2=子公司，3=部门。';

comment on column public.dim_org.org_code is '组织编码，业务唯一标识，如 ORG-HQ、ORG-A-SALES。';

comment on column public.dim_org.is_active is '是否启用，TRUE 表示有效组织，FALSE 表示停用组织。';

alter table public.dim_org
    owner to postgres;

create table public.dim_product
(
    product_id   integer              not null
        primary key,
    product_code varchar(20)          not null
        unique,
    product_name varchar(100)         not null,
    category     varchar(50)          not null,
    is_active    boolean default true not null
);

comment on table public.dim_product is '产品维表，用于存储产品或产品线基础信息。';

comment on column public.dim_product.product_id is '产品主键ID，手工指定，便于演示和固定映射。';

comment on column public.dim_product.product_code is '产品编码，如 P01、P02。';

comment on column public.dim_product.product_name is '产品名称，如木材加工、林业装备等。';

comment on column public.dim_product.category is '产品类别或产线分类，用于更高层级的汇总分析。';

comment on column public.dim_product.is_active is '是否启用，TRUE 表示可用产品，FALSE 表示停用产品。';

alter table public.dim_product
    owner to postgres;

create table public.dim_channel
(
    channel_id   integer              not null
        primary key,
    channel_code varchar(20)          not null
        unique,
    channel_name varchar(100)         not null,
    is_active    boolean default true not null
);

comment on table public.dim_channel is '销售渠道维表，用于存储直营、经销商、电商、大客户等渠道信息。';

comment on column public.dim_channel.channel_id is '渠道主键ID，手工指定，便于演示和固定映射。';

comment on column public.dim_channel.channel_code is '渠道编码，如 C01、C02。';

comment on column public.dim_channel.channel_name is '渠道名称，如直营、经销商、电商、大客户。';

comment on column public.dim_channel.is_active is '是否启用，TRUE 表示可用渠道，FALSE 表示停用渠道。';

alter table public.dim_channel
    owner to postgres;

create table public.fact_gross_profit_detail
(
    id           bigserial
        primary key,
    period_date  date                                not null,
    period_month char(6)                             not null,
    org_id       integer                             not null
        references public.dim_org,
    product_id   integer                             not null
        references public.dim_product,
    channel_id   integer                             not null
        references public.dim_channel,
    revenue      numeric(18, 2)                      not null
        constraint fact_gross_profit_detail_revenue_check
            check (revenue >= (0)::numeric),
    cost         numeric(18, 2)                      not null
        constraint fact_gross_profit_detail_cost_check
            check (cost >= (0)::numeric),
    qty          integer   default 0                 not null
        constraint fact_gross_profit_detail_qty_check
            check (qty >= 0),
    gross_profit numeric(18, 2) generated always as ((revenue - cost)) stored,
    created_at   timestamp default CURRENT_TIMESTAMP not null
);

comment on table public.fact_gross_profit_detail is '毛利经营事实表，存储某月、某部门、某产品、某渠道下的收入、成本、数量等经营明细数据。';

comment on column public.fact_gross_profit_detail.id is '事实表主键ID，自增生成。';

comment on column public.fact_gross_profit_detail.period_date is '账期日期，通常取自然月第一天，如 2024-01-01。';

comment on column public.fact_gross_profit_detail.period_month is '账期月份，格式为 YYYYMM，如 202401。';

comment on column public.fact_gross_profit_detail.org_id is '组织ID，关联 dim_org.id，一般指向部门级组织。';

comment on column public.fact_gross_profit_detail.product_id is '产品ID，关联 dim_product.product_id。';

comment on column public.fact_gross_profit_detail.channel_id is '渠道ID，关联 dim_channel.channel_id。';

comment on column public.fact_gross_profit_detail.revenue is '销售收入，模拟含税或口径统一后的营业收入金额。';

comment on column public.fact_gross_profit_detail.cost is '销售成本，与收入口径一致，用于计算毛利。';

comment on column public.fact_gross_profit_detail.qty is '销售数量或业务数量，用于辅助分析。';

comment on column public.fact_gross_profit_detail.gross_profit is '毛利金额，由 revenue - cost 自动生成。';

comment on column public.fact_gross_profit_detail.created_at is '记录创建时间。';

alter table public.fact_gross_profit_detail
    owner to postgres;

create index idx_fact_gross_profit_detail_period_date
    on public.fact_gross_profit_detail (period_date);

create index idx_fact_gross_profit_detail_period_month
    on public.fact_gross_profit_detail (period_month);

create index idx_fact_gross_profit_detail_org_id
    on public.fact_gross_profit_detail (org_id);

create index idx_fact_gross_profit_detail_product_id
    on public.fact_gross_profit_detail (product_id);

create index idx_fact_gross_profit_detail_channel_id
    on public.fact_gross_profit_detail (channel_id);

create index idx_fact_gross_profit_detail_org_period
    on public.fact_gross_profit_detail (org_id, period_date);

create table public.dim_cost_item
(
    cost_item_id   integer              not null
        primary key,
    cost_item_code varchar(20)          not null
        unique,
    cost_item_name varchar(100)         not null,
    cost_category  varchar(50)          not null,
    is_variable    boolean default true not null,
    is_active      boolean default true not null,
    sort_no        smallint             not null
);

comment on table public.dim_cost_item is '成本项目维表，用于存储原材料、人工、制造费用、物流费用、渠道费用、管理分摊等成本项目。';

comment on column public.dim_cost_item.cost_item_id is '成本项目主键ID。';

comment on column public.dim_cost_item.cost_item_code is '成本项目编码，如 CI01、CI02。';

comment on column public.dim_cost_item.cost_item_name is '成本项目名称。';

comment on column public.dim_cost_item.cost_category is '成本项目分类，如生产成本、履约成本、期间费用。';

comment on column public.dim_cost_item.is_variable is '是否为变动成本，TRUE 表示变动成本，FALSE 表示固定/分摊成本。';

comment on column public.dim_cost_item.is_active is '是否启用。';

comment on column public.dim_cost_item.sort_no is '排序号。';

alter table public.dim_cost_item
    owner to postgres;

create table public.fact_cost_detail
(
    id              bigserial
        primary key,
    source_fact_id  bigint
        references public.fact_gross_profit_detail,
    period_date     date                                not null,
    period_month    char(6)                             not null,
    org_id          integer                             not null
        references public.dim_org,
    product_id      integer                             not null
        references public.dim_product,
    channel_id      integer                             not null
        references public.dim_channel,
    cost_item_id    integer                             not null
        references public.dim_cost_item,
    cost_amount     numeric(18, 2)                      not null
        constraint fact_cost_detail_cost_amount_check
            check (cost_amount >= (0)::numeric),
    allocation_rule varchar(100)                        not null,
    created_at      timestamp default CURRENT_TIMESTAMP not null
);

comment on table public.fact_cost_detail is '成本明细事实表，用于存储某月、某部门、某产品、某渠道、某成本项目下的成本金额。';

comment on column public.fact_cost_detail.id is '成本明细事实表主键ID。';

comment on column public.fact_cost_detail.source_fact_id is '来源毛利事实表ID，关联 fact_gross_profit_detail.id，用于追溯成本拆分来源。';

comment on column public.fact_cost_detail.period_date is '账期日期，通常取自然月第一天。';

comment on column public.fact_cost_detail.period_month is '账期月份，格式 YYYYMM。';

comment on column public.fact_cost_detail.org_id is '组织ID，关联 dim_org.id，一般指向部门级组织。';

comment on column public.fact_cost_detail.product_id is '产品ID，关联 dim_product.product_id。';

comment on column public.fact_cost_detail.channel_id is '渠道ID，关联 dim_channel.channel_id。';

comment on column public.fact_cost_detail.cost_item_id is '成本项目ID，关联 dim_cost_item.cost_item_id。';

comment on column public.fact_cost_detail.cost_amount is '该成本项目对应的成本金额。';

comment on column public.fact_cost_detail.allocation_rule is '成本拆分或分摊规则说明。';

comment on column public.fact_cost_detail.created_at is '记录创建时间。';

alter table public.fact_cost_detail
    owner to postgres;

create index idx_fact_cost_detail_period_date
    on public.fact_cost_detail (period_date);

create index idx_fact_cost_detail_period_month
    on public.fact_cost_detail (period_month);

create index idx_fact_cost_detail_org_id
    on public.fact_cost_detail (org_id);

create index idx_fact_cost_detail_product_id
    on public.fact_cost_detail (product_id);

create index idx_fact_cost_detail_channel_id
    on public.fact_cost_detail (channel_id);

create index idx_fact_cost_detail_cost_item_id
    on public.fact_cost_detail (cost_item_id);

create index idx_fact_cost_detail_org_period
    on public.fact_cost_detail (org_id, period_date);

create table public.fact_cost_budget
(
    id                 bigserial
        primary key,
    period_date        date                                        not null,
    period_month       char(6)                                     not null,
    org_id             integer                                     not null
        references public.dim_org,
    product_id         integer                                     not null
        references public.dim_product,
    channel_id         integer                                     not null
        references public.dim_channel,
    cost_item_id       integer                                     not null
        references public.dim_cost_item,
    budget_cost_amount numeric(18, 2)                              not null
        constraint fact_cost_budget_budget_cost_amount_check
            check (budget_cost_amount >= (0)::numeric),
    budget_version     varchar(30) default 'V1'::character varying not null,
    created_at         timestamp   default CURRENT_TIMESTAMP       not null
);

comment on table public.fact_cost_budget is '成本预算事实表，用于存储某月、某部门、某产品、某渠道、某成本项目下的预算成本金额。';

comment on column public.fact_cost_budget.id is '成本预算事实表主键ID。';

comment on column public.fact_cost_budget.period_date is '账期日期，通常取自然月第一天。';

comment on column public.fact_cost_budget.period_month is '账期月份，格式 YYYYMM。';

comment on column public.fact_cost_budget.org_id is '组织ID，关联 dim_org.id。';

comment on column public.fact_cost_budget.product_id is '产品ID，关联 dim_product.product_id。';

comment on column public.fact_cost_budget.channel_id is '渠道ID，关联 dim_channel.channel_id。';

comment on column public.fact_cost_budget.cost_item_id is '成本项目ID，关联 dim_cost_item.cost_item_id。';

comment on column public.fact_cost_budget.budget_cost_amount is '预算成本金额。';

comment on column public.fact_cost_budget.budget_version is '预算版本，如 V1、V2。';

comment on column public.fact_cost_budget.created_at is '记录创建时间。';

alter table public.fact_cost_budget
    owner to postgres;

create index idx_fact_cost_budget_period_date
    on public.fact_cost_budget (period_date);

create index idx_fact_cost_budget_period_month
    on public.fact_cost_budget (period_month);

create index idx_fact_cost_budget_org_period
    on public.fact_cost_budget (org_id, period_date);

create index idx_fact_cost_budget_product_id
    on public.fact_cost_budget (product_id);

create index idx_fact_cost_budget_channel_id
    on public.fact_cost_budget (channel_id);

create index idx_fact_cost_budget_cost_item_id
    on public.fact_cost_budget (cost_item_id);

