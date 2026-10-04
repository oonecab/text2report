# Text2Report 黄金评测集

本目录提供一套基于真实业务 DDL 的黄金评测样本，用于验证 metadata 检索、prompt 表选择、SQL 结构生成和回归保护。评测集不包含标准 SQL 答案，也不包含任何业务数据行，只描述期望使用的表、字段、术语、示例提示和 SQL 结构特征。

## 1. 数据来源说明

所有 `expected_tables` 和 `expected_columns` 均来自 `/sql/schema-business.sql`。当前评测集使用以下真实表和关键字段：

| 表名 | 用途 | 关键字段 |
| --- | --- | --- |
| `fact_gross_profit_detail` | 毛利经营事实表 | `id`, `period_date`, `period_month`, `org_id`, `product_id`, `channel_id`, `revenue`, `cost`, `qty`, `gross_profit`, `created_at` |
| `fact_cost_detail` | 成本明细事实表 | `id`, `source_fact_id`, `period_date`, `period_month`, `org_id`, `product_id`, `channel_id`, `cost_item_id`, `cost_amount`, `allocation_rule`, `created_at` |
| `fact_cost_budget` | 成本预算事实表 | `id`, `period_date`, `period_month`, `org_id`, `product_id`, `channel_id`, `cost_item_id`, `budget_cost_amount`, `budget_version`, `created_at` |
| `dim_org` | 组织维表 | `id`, `org_name`, `parent_id`, `org_level`, `org_code`, `is_active` |
| `dim_product` | 产品维表 | `product_id`, `product_code`, `product_name`, `category`, `is_active` |
| `dim_channel` | 渠道维表 | `channel_id`, `channel_code`, `channel_name`, `is_active` |
| `dim_cost_item` | 成本项目维表 | `cost_item_id`, `cost_item_code`, `cost_item_name`, `cost_category`, `is_variable`, `is_active`, `sort_no` |

组织结构按 schema 注释和演示数据约定使用：母公司 `id=1`，子公司 A/B/C 分别为 `id=2/3/4`，部门节点为 `id=11~34`。

## 2. 样本设计原则

查询意图覆盖 `query`、`aggregation`、`ranking`、`trend`、`comparison`、`extreme`，因为这六类会触发不同的 SQL 结构：明细查询、聚合、排序、时间分组、双期或双维对比、极值排序。`aggregation` 和 `ranking` 各至少 2 条，是因为它们最容易暴露表选择、GROUP BY 和 ORDER BY 方向错误。

维度组合覆盖组织、产品、渠道、成本项目，以及产品+渠道、组织+产品等双维组合。毛利主链路围绕 `fact_gross_profit_detail` 与 `dim_org`、`dim_product`、`dim_channel`，成本链路加入 `fact_cost_detail` 与 `dim_cost_item`，用于确认系统不会把所有问题都召回到毛利事实表。

假表污染回归样本用于验证阶段1-4的改进效果：当问题语义明显指向毛利时，应召回并使用 `fact_gross_profit_detail`，同时禁止生成命中 `fact_gross_profit[^_]` 的 SQL。该正则会匹配假表 `fact_gross_profit`，但不会匹配正式表 `fact_gross_profit_detail`。

## 3. 分布统计

### 按意图

| intent | 数量 | 样本 |
| --- | ---: | --- |
| `query` | 2 | GQ-006, GQ-012 |
| `aggregation` | 3 | GQ-001, GQ-005, GQ-008 |
| `ranking` | 2 | GQ-002, GQ-007 |
| `trend` | 2 | GQ-003, GQ-009 |
| `comparison` | 2 | GQ-004, GQ-011 |
| `extreme` | 1 | GQ-010 |

### 按时间粒度

| time_grain | 数量 | 样本 |
| --- | ---: | --- |
| `monthly` | 4 | GQ-002, GQ-004, GQ-006, GQ-010 |
| `quarterly` | 3 | GQ-003, GQ-007, GQ-011 |
| `yearly` | 3 | GQ-001, GQ-008, GQ-012 |
| `ytd` | 1 | GQ-005 |
| `rolling_half_year` | 1 | GQ-009 |

模糊时间表达共 2 条：GQ-005 的“今年以来”，GQ-009 的“最近半年”。

### 按维度

| 维度 | 数量 | 样本 |
| --- | ---: | --- |
| organization | 6 | GQ-002, GQ-003, GQ-005, GQ-006, GQ-008, GQ-010 |
| product | 4 | GQ-001, GQ-004, GQ-006, GQ-009 |
| channel | 3 | GQ-004, GQ-007, GQ-011 |
| time | 3 | GQ-003, GQ-009, GQ-011 |
| cost_item | 1 | GQ-012 |

双维组合至少 2 条：GQ-004 为产品+渠道，GQ-006 为组织+产品。

### 特殊场景

| 场景 | 数量 | 样本 |
| --- | ---: | --- |
| 假表污染回归 | 3 | GQ-001, GQ-008, GQ-009 |
| 跨事实表成本查询 | 1 | GQ-012 |
| 模糊时间 | 2 | GQ-005, GQ-009 |
| 双维组合 | 2 | GQ-004, GQ-006 |

## 4. 字段含义说明

| 字段 | 含义和填写规则 |
| --- | --- |
| `id` | 样本编号，格式为 `GQ-XXX`，三位递增。 |
| `question` | 自然中文问题，不写成 SQL 化描述。 |
| `org_id` | 查询入口组织 ID，用于权限和组织范围上下文。 |
| `intent` | 期望查询意图：`query`、`aggregation`、`ranking`、`trend`、`comparison`、`extreme`。 |
| `time_grain` | 时间粒度：`monthly`、`quarterly`、`yearly`、`ytd`、`rolling_half_year` 等。 |
| `dimensions` | 业务分析维度，例如 `organization`、`product`、`channel`、`time`、`cost_item`。 |
| `expected_tables` | 期望 RAG 召回并进入 prompt 的真实表，必须存在于 `/sql/schema-business.sql`。 |
| `expected_columns` | 期望 SQL 可使用的关键字段，必须存在于 `expected_tables` 中至少一张表。 |
| `expected_glossary_terms` | 期望命中的业务术语，用于检查 glossary 检索和 prompt 上下文质量。 |
| `expected_example_hints` | 期望相关 SQL example 或业务示例提供的提示方向，不要求逐字匹配。 |
| `acceptable_sql_patterns` | 正向 SQL 结构正则。生成 SQL 应尽量全部命中，例如 `JOIN.*dim_product` 表示应连接产品维表，`SUM.*gross_profit\|SUM.*revenue.*-.*cost` 表示可以直接汇总计算列，也可以用收入减成本。 |
| `forbidden_sql_patterns` | 负向 SQL 结构正则。生成 SQL 不应命中，例如 `fact_gross_profit[^_]` 用于拦截假表但允许 `fact_gross_profit_detail`。 |
| `tags` | 便于筛选和统计的标签，可包含意图、维度、时间、特殊场景。 |
| `notes` | 说明样本验证目的。假表污染回归样本必须包含 `invalid table pollution regression case`。 |

`acceptable_sql_patterns` 和 `forbidden_sql_patterns` 是回归判断的结构特征，不是完整 SQL 答案。它们适合检查表选择、JOIN、WHERE、GROUP BY、ORDER BY、LIMIT 等关键结构，避免把评测集变成训练数据。

## 5. 回归目标

1. 正确表召回：`expected_tables` 中的表应被 `MetadataService.retrieveForPrompt()` 的 TopK 表覆盖，并且主事实表应排序靠前。
2. 假表排除：生成 SQL 不应命中 `forbidden_sql_patterns`，尤其不能出现未发布或不存在的 `fact_gross_profit`。
3. 意图-维度对齐：生成 SQL 的 `GROUP BY`、`ORDER BY`、时间过滤和 JOIN 应与 `intent` + `dimensions` 匹配。

## 6. 后续扩充指南

新增样本编号使用 `GQ-XXX`，按现有最大编号递增，不复用历史编号。新增样本必须先确认表名和字段名存在于 `/sql/schema-business.sql` 或后续正式 schema 文件中。

新增样本覆盖检查清单：

- 是否有明确自然语言问题，而不是 SQL 提示词。
- `expected_tables` 是否覆盖事实表和必要维表。
- `expected_columns` 是否全部来自 `expected_tables`。
- 是否设置至少 2 条正向结构正则。
- 是否设置必要的负向正则，特别是相似表、假表、错误事实表。
- 是否补充能用于统计的 `tags`。
- 是否说明该样本的回归目的。

建议扩充方向：

- 纳入更多业务事实表，例如预算、库存、应收、利润中心。
- 增加更复杂 JOIN 路径，例如成本预算与实际成本对比。
- 增加多轮对话场景，例如“继续按渠道拆分”“只看子公司A”。
- 增加权限边界样本，例如跨组织查询被 org scope 收敛。

## 7. 未来 Runner 使用方式

本阶段只提供数据集和轻量校验脚本，不实现自动化 runner。未来 runner 可以按以下方式使用：

1. 对每条样本调用 `MetadataService.retrieveForPrompt(question, datasourceId)`。
2. 计算 Recall@K：检查返回的 TopK 表名是否覆盖 `expected_tables`。单条样本可以记为 `covered_tables / expected_tables`，总体 Recall@K 取平均值或全覆盖率。
3. 调用 SQL 生成链路，但不执行真实业务数据查询，或在受控测试库中执行只读链路。
4. 计算 SQL pass rate：生成 SQL 需要匹配 `acceptable_sql_patterns` 中的关键结构，并且不能命中任何 `forbidden_sql_patterns`。
5. 对失败样本记录 prompt metadata、推荐表排序、生成 SQL、validator 错误信息和 repair 后 SQL，作为下一轮 metadata、glossary、example 或 prompt 优化输入。

## 校验脚本

运行：

```bash
python docs/evaluation/scripts/validate_golden_queries.py
```

脚本会读取 `docs/evaluation/datasets/golden_queries.json` 和 `sql/schema-business.sql`，输出：

- DDL 中解析出的表和字段数量。
- 每条样本的表/字段合法性校验。
- `intent`、`time_grain`、`dimensions` 分布统计。
- 通过或失败报告。
