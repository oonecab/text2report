# Text2Report Demo 系统架构建议

> 适用场景：毛利表查询 Demo，SQL Server 数据源，React + Ant Design 前端，Java 后端，支持后期扩展至本地 LLM 部署。

---

## 一、元数据应该用什么格式保存？

**结论：不要用 docx，也不要只用静态 json，而是分两层存储。**

### 第一层：结构化存储（PostgreSQL 数据库表）

元数据主体存在 PostgreSQL 里，方便检索、版本管理和后台维护。

```sql
-- 组织机构表（支持母公司→子公司→部门层级）
CREATE TABLE org_tree (
  id          SERIAL PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  parent_id   INT REFERENCES org_tree(id),  -- NULL 表示根节点（母公司）
  level       INT NOT NULL,                 -- 1=母公司，2=子公司，3=部门
  code        VARCHAR(50),
  is_active   BOOLEAN DEFAULT TRUE
);

-- 数据源配置
CREATE TABLE meta_datasource (
  id          SERIAL PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  db_type     VARCHAR(20) DEFAULT 'sqlserver',
  description TEXT,
  is_active   BOOLEAN DEFAULT TRUE
);

-- 表信息
CREATE TABLE meta_tables (
  id            SERIAL PRIMARY KEY,
  datasource_id INT REFERENCES meta_datasource(id),
  table_name    VARCHAR(100) NOT NULL,
  label         VARCHAR(100),            -- 中文名
  description   TEXT,                    -- 业务说明
  schema_name   VARCHAR(50),             -- SQL Server 常用 dbo
  is_active     BOOLEAN DEFAULT TRUE,
  updated_at    TIMESTAMP,
  updated_by    VARCHAR(50)
);

-- 字段信息
CREATE TABLE meta_columns (
  id              SERIAL PRIMARY KEY,
  table_id        INT REFERENCES meta_tables(id),
  column_name     VARCHAR(100) NOT NULL,
  label           VARCHAR(100),           -- 中文名
  data_type       VARCHAR(50),
  description     TEXT,                   -- 口径说明
  is_sensitive    BOOLEAN DEFAULT FALSE,
  is_active       BOOLEAN DEFAULT TRUE,
  comment_source  VARCHAR(20)             -- ddl=从建表语句解析，manual=人工填写
);

-- 业务术语词典
CREATE TABLE meta_glossary (
  id          SERIAL PRIMARY KEY,
  term        VARCHAR(100) NOT NULL,
  definition  TEXT,
  sql_snippet TEXT,                    -- 对应 SQL 片段
  aliases     TEXT[],                  -- 别名，如 ["毛利润率","gross margin"]
  org_scope   INT[],                   -- 适用组织ID，NULL 表示全局
  updated_at  TIMESTAMP
);

-- JOIN 关系图
CREATE TABLE meta_join_edges (
  id           SERIAL PRIMARY KEY,
  from_table   VARCHAR(100),
  from_column  VARCHAR(100),
  to_table     VARCHAR(100),
  to_column    VARCHAR(100),
  join_type    VARCHAR(20) DEFAULT 'LEFT',
  description  TEXT
);

-- 示例问答对
CREATE TABLE meta_sql_examples (
  id          SERIAL PRIMARY KEY,
  question    TEXT NOT NULL,
  sql         TEXT NOT NULL,
  domain      VARCHAR(50),
  org_scope   INT[],           -- NULL 表示通用
  is_active   BOOLEAN DEFAULT TRUE,
  created_at  TIMESTAMP
);

-- DDL 上传记录（审计追溯）
CREATE TABLE meta_ddl_uploads (
  id            SERIAL PRIMARY KEY,
  raw_ddl       TEXT NOT NULL,
  parsed_tables JSONB,
  upload_by     VARCHAR(50),
  upload_at     TIMESTAMP DEFAULT NOW(),
  status        VARCHAR(20)   -- pending / completed / failed
);
```

### 第二层：动态 JSON（用于 Prompt 拼装）

每次用户提问时，后端从数据库动态查出相关元数据，组装 JSON 传给 LLM。这个 JSON **不是手写的，是代码动态生成的**。

```json
{
  "dialect": "sqlserver",
  "org_context": { "company": "子公司A", "dept_ids": [11, 12, 13] },
  "tables": [
    {
      "name": "gross_profit_detail",
      "label": "毛利明细表",
      "columns": [
        { "name": "period_month", "label": "账期月份", "type": "VARCHAR", "note": "格式YYYYMM" },
        { "name": "revenue",      "label": "销售收入", "type": "DECIMAL", "note": "含税金额" },
        { "name": "cost",         "label": "销售成本", "type": "DECIMAL" },
        { "name": "org_id",       "label": "组织ID",   "type": "INT" }
      ]
    }
  ],
  "glossary": [
    { "term": "毛利",   "sql": "revenue - cost" },
    { "term": "毛利率", "sql": "(revenue - cost) / NULLIF(revenue, 0)" },
    { "term": "本季度", "sql": "DATEPART(QUARTER, col) = DATEPART(QUARTER, GETDATE()) AND YEAR(col) = YEAR(GETDATE())" }
  ],
  "joins": [
    { "desc": "毛利明细关联组织机构", "sql": "gross_profit_detail d LEFT JOIN org_tree o ON d.org_id = o.id" }
  ],
  "examples": [
    {
      "q": "子公司A今年各部门毛利率",
      "sql": "SELECT o.name, SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0) AS margin FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE o.parent_id=2 AND YEAR(d.period_date)=YEAR(GETDATE()) GROUP BY o.name"
    }
  ]
}
```

---

## 二、组织层级设计

### 层级结构

```
母公司（level=1, id=1）
├── 子公司A（level=2, id=2）
│   ├── 部门A1（level=3, id=11）
│   └── 部门A2（level=3, id=12）
├── 子公司B（level=2, id=3）
│   ├── 部门B1（level=3, id=21）
│   └── 部门B2（level=3, id=22）
└── 子公司C（level=2, id=4）
    ├── 部门C1（level=3, id=31）
    └── 部门C2（level=3, id=32）
```

### 对查询的影响

用户查询时，系统需要将组织上下文注入 SQL：

```sql
-- 查子公司A所有部门（包含子公司本身及其下所有部门）
WHERE org_id IN (
  SELECT id FROM org_tree
  WHERE id = 2 OR parent_id = 2
)

-- 按子公司汇总（母公司视角）
GROUP BY (SELECT name FROM org_tree WHERE id = o.parent_id)
```

---

## 三、DDL 上传与解析功能设计

### 功能流程

```
用户在管理后台粘贴或上传 DDL 文件
        │
        ▼
后端解析 DDL
（提取表名、字段名、字段类型、已有注释）
        │
        ▼
前端展示解析结果（可编辑表格）
  - 已有注释：直接显示，来源标记为"DDL"
  - 无注释字段：显示为待填写，标记为"待补充"
        │
        ▼
用户逐行填写/修改中文名和业务说明
        │
        ▼
用户确认保存 → 写入 meta_tables + meta_columns
        │
        ▼
记录本次上传到 meta_ddl_uploads（审计追溯）
```

### DDL 解析规则

后端需要处理 SQL Server 的几种注释格式：

```sql
-- 格式一：行内注释（常见）
CREATE TABLE gross_profit_detail (
    period_month VARCHAR(6),     -- 账期月份，格式YYYYMM
    revenue      DECIMAL(18,2),  -- 销售收入（含税）
    cost         DECIMAL(18,2)   -- 销售成本（含税）
)

-- 格式二：sp_addextendedproperty（SQL Server 标准）
EXEC sp_addextendedproperty
    @name = N'MS_Description', @value = N'账期月份',
    @level1type = N'Table',  @level1name = N'gross_profit_detail',
    @level2type = N'Column', @level2name = N'period_month'

-- 格式三：COMMENT ON（部分工具导出使用）
COMMENT ON COLUMN gross_profit_detail.period_month IS '账期月份';
```

三种格式都需要能解析，注释内容填入 `meta_columns.description`，来源标记为 `comment_source = 'ddl'`。

### 解析结果 UI 示意

```
表名：gross_profit_detail  中文名：[毛利明细表______]  说明：[___________]

┌──────────────┬───────────────┬──────────────┬──────────────────────┬──────────┐
│ 字段名        │ 数据类型       │ 中文名        │ 业务说明              │ 注释来源  │
├──────────────┼───────────────┼──────────────┼──────────────────────┼──────────┤
│ period_month │ VARCHAR(6)    │ 账期月份      │ 格式YYYYMM，如202401  │ ✅ DDL   │
│ revenue      │ DECIMAL(18,2) │ 销售收入      │ [待填写]              │ ✏️ 手动  │
│ cost         │ DECIMAL(18,2) │ [待填写]      │ [待填写]              │ ✏️ 手动  │
│ org_id       │ INT           │ [待填写]      │ [待填写]              │ ✏️ 手动  │
└──────────────┴───────────────┴──────────────┴──────────────────────┴──────────┘

                                              [取消]  [保存并导入元数据库]
```

### 元数据管理接口

```
POST /api/v1/metadata/ddl/parse       # 解析DDL，返回预览（不存库）
POST /api/v1/metadata/ddl/confirm     # 用户确认后写入元数据表
GET  /api/v1/metadata/tables          # 查询已录入表列表
PUT  /api/v1/metadata/columns/{id}    # 修改单个字段注释
GET  /api/v1/metadata/glossary        # 查询术语词典
POST /api/v1/metadata/glossary        # 新增/修改术语
GET  /api/v1/org/tree                 # 查询组织树结构
```

---

## 四、系统整体架构

```
┌──────────────────────────────────────────────────┐
│           前端层  React + Ant Design              │
│                                                  │
│  ┌─────────────────┐   ┌───────────────────────┐ │
│  │   查询页面       │   │   元数据管理后台        │ │
│  │ - 自然语言输入   │   │ - DDL上传解析          │ │
│  │ - 结果表格/图表  │   │ - 字段注释编辑         │ │
│  │ - 历史记录       │   │ - 术语词典管理         │ │
│  └─────────────────┘   │ - 组织机构管理         │ │
│                         └───────────────────────┘ │
└──────────────────┬───────────────────────────────┘
                   │ HTTP/REST
┌──────────────────▼───────────────────────────────┐
│              Java 后端（Spring Boot）              │
│                                                   │
│  QueryController          MetadataController      │
│       │                         │                 │
│  OrchestratorService      DDLParserService        │
│       │                   MetadataService         │
│       │                                           │
│  LLMService（接口）        SqlGuardService         │
│  ├─ OpenAILLMService       QueryExecutorService   │
│  └─ LocalLLMService(预留)  AuditService           │
└──────────────────┬────────────────────────────────┘
                   │
        ┌──────────┴──────────┐
        │                     │
┌───────▼──────┐    ┌─────────▼───────┐
│  SQL Server  │    │   PostgreSQL    │
│  业务数据库   │    │ 元数据 + 审计   │
│  （只读账号） │    │ + 组织机构树    │
└──────────────┘    └─────────────────┘
```

---

## 五、项目目录结构

```
text2report/
├── frontend/
│   └── src/
│       ├── pages/
│       │   ├── QueryPage.tsx
│       │   ├── HistoryPage.tsx
│       │   └── metadata/
│       │       ├── DDLUploadPage.tsx      # DDL上传与解析
│       │       ├── TableListPage.tsx      # 表与字段管理
│       │       ├── GlossaryPage.tsx       # 术语词典管理
│       │       └── OrgTreePage.tsx        # 组织机构管理
│       ├── components/
│       │   ├── QueryInput.tsx
│       │   ├── ResultTable.tsx
│       │   ├── ResultChart.tsx
│       │   └── DDLColumnEditor.tsx        # 字段注释编辑表格
│       └── api/
│           ├── queryApi.ts
│           └── metadataApi.ts
│
├── backend/
│   └── src/main/java/com/text2report/
│       ├── controller/
│       │   ├── QueryController.java
│       │   └── MetadataController.java
│       ├── service/
│       │   ├── OrchestratorService.java
│       │   ├── MetadataService.java
│       │   ├── DDLParserService.java       # DDL解析核心
│       │   ├── llm/
│       │   │   ├── LLMService.java         # 接口（不可绕过）
│       │   │   ├── OpenAILLMService.java
│       │   │   └── LocalLLMService.java    # 预留本地模型
│       │   ├── SqlGuardService.java
│       │   ├── QueryExecutorService.java
│       │   └── AuditService.java
│       ├── model/
│       │   ├── QueryRequest.java
│       │   ├── QueryResponse.java
│       │   ├── DDLParseRequest.java
│       │   └── DDLParseResult.java
│       └── config/
│           └── LLMConfig.java
│
├── metadata/                              # 种子数据（初始导入用）
│   ├── tables/
│   ├── glossary.json
│   ├── joins.json
│   └── examples/
│
├── prompts/                               # Prompt模板（版本管理）
│   ├── sql_generation_v1.txt
│   └── sql_repair_v1.txt
│
└── docs/
    ├── architecture-guide.md
    ├── metadata-guide.md
    └── api.md
```

---

## 六、主查询流程

```
用户输入："子公司A 2024年第三季度各部门毛利率"
    │
    ▼
OrchestratorService.query()
    ├─→ 解析组织上下文 → 查 org_tree 获取子公司A下所有部门ID
    ├─→ MetadataService.retrieve() → 检索相关表/字段/术语/示例
    ├─→ LLMService.generateSQL() → 调用外部API或本地模型
    ├─→ SqlGuardService.validate() → 只读校验 + 注入TOP和超时
    ├─→ QueryExecutorService.execute() → SQL Server只读执行
    ├─→ 组装响应（表格 + 图表建议 + 摘要）
    └─→ AuditService.log() → 记录全链路信息
```

---

## 七、SQL Server 特别注意事项

```sql
-- 限制行数（SQL Server 用 TOP，不用 LIMIT）
SELECT TOP 1000 ...

-- 本月
MONTH(col) = MONTH(GETDATE()) AND YEAR(col) = YEAR(GETDATE())

-- 本季度
DATEPART(QUARTER, col) = DATEPART(QUARTER, GETDATE())
AND YEAR(col) = YEAR(GETDATE())

-- 查子公司及其下所有部门
WHERE org_id IN (
  SELECT id FROM org_tree WHERE id = @id OR parent_id = @id
)
```

这些规则必须写入 Prompt 模板，明确告知 LLM 使用 SQL Server 方言。

---

## 八、Demo 阶段可以先跳过的内容

- 复杂用户权限系统（Demo 阶段用固定用户即可）
- 向量检索（元数据量小，关键词检索足够）
- 报表导出（Excel/PDF）
- 自动重试（Demo 阶段手动处理错误）
- 审计报表 UI

---

## 九、后期切换本地模型步骤

1. 实现 `LocalLLMService implements LLMService`
2. 修改配置 `llm.provider: local`
3. 重启服务，上层代码零改动
