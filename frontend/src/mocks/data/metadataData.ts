import type { MetaTable, MetaColumn, Glossary, JoinEdge, SqlExample } from '@/types';

// Meta Tables
export const META_TABLES: MetaTable[] = [
  {
    id: 1,
    datasourceId: 1,
    tableName: 'gross_profit_detail',
    label: '毛利明细表',
    description: '按月记录各组织各产品的收入、成本、毛利数据',
    schemaName: 'dbo',
    isActive: true,
    updatedAt: '2026-04-01T10:00:00Z',
    updatedBy: 'admin',
  },
  {
    id: 2,
    datasourceId: 1,
    tableName: 'org_tree',
    label: '组织机构表',
    description: '公司组织层级结构表',
    schemaName: 'dbo',
    isActive: true,
    updatedAt: '2026-04-01T10:00:00Z',
    updatedBy: 'admin',
  },
  {
    id: 3,
    datasourceId: 1,
    tableName: 'product_master',
    label: '产品主数据',
    description: '产品分类和基本信息',
    schemaName: 'dbo',
    isActive: true,
    updatedAt: '2026-04-01T10:00:00Z',
    updatedBy: 'admin',
  },
];

// Meta Columns
export const META_COLUMNS: MetaColumn[] = [
  { id: 1, tableId: 1, columnName: 'id', dataType: 'INT', label: '主键', description: '自增主键', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 2, tableId: 1, columnName: 'period_month', dataType: 'VARCHAR(6)', label: '账期月份', description: '格式YYYYMM，如202401', isSensitive: false, isActive: true, commentSource: 'ddl' },
  { id: 3, tableId: 1, columnName: 'period_quarter', dataType: 'VARCHAR(6)', label: '账期季度', description: '格式YYYYQN，如20241Q', isSensitive: false, isActive: true, commentSource: 'ddl' },
  { id: 4, tableId: 1, columnName: 'period_year', dataType: 'INT', label: '账期年份', description: '如2024', isSensitive: false, isActive: true, commentSource: 'ddl' },
  { id: 5, tableId: 1, columnName: 'revenue', dataType: 'DECIMAL(18,2)', label: '销售收入', description: '含税金额', isSensitive: false, isActive: true, commentSource: 'ddl' },
  { id: 6, tableId: 1, columnName: 'cost', dataType: 'DECIMAL(18,2)', label: '销售成本', description: '含税成本', isSensitive: false, isActive: true, commentSource: 'ddl' },
  { id: 7, tableId: 1, columnName: 'org_id', dataType: 'INT', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 8, tableId: 1, columnName: 'product_category', dataType: 'VARCHAR(50)', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 9, tableId: 1, columnName: 'created_at', dataType: 'DATETIME', label: '创建时间', description: '记录创建时间', isSensitive: false, isActive: true, commentSource: 'ddl' },

  { id: 10, tableId: 2, columnName: 'id', dataType: 'INT', label: '主键', description: '自增主键', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 11, tableId: 2, columnName: 'name', dataType: 'VARCHAR(100)', label: '组织名称', description: '组织节点名称', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 12, tableId: 2, columnName: 'parent_id', dataType: 'INT', label: '父节点ID', description: '上级组织ID，NULL表示根节点', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 13, tableId: 2, columnName: 'level', dataType: 'INT', label: '层级', description: '1=母公司，2=子公司，3=部门', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 14, tableId: 2, columnName: 'code', dataType: 'VARCHAR(50)', label: '编码', description: '组织编码', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 15, tableId: 2, columnName: 'is_active', dataType: 'BIT', label: '是否启用', description: '1=启用，0=停用', isSensitive: false, isActive: true, commentSource: 'manual' },

  { id: 16, tableId: 3, columnName: 'category_code', dataType: 'VARCHAR(50)', label: '产品分类编码', description: '产品唯一标识', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 17, tableId: 3, columnName: 'category_name', dataType: 'VARCHAR(100)', label: '产品分类名称', description: '产品分类中文名', isSensitive: false, isActive: true, commentSource: 'manual' },
  { id: 18, tableId: 3, columnName: 'parent_category', dataType: 'VARCHAR(50)', label: '父分类编码', description: '上级产品分类', isSensitive: false, isActive: true, commentSource: 'manual' },
];

// Glossary
export const META_GLOSSARY: Glossary[] = [
  { id: 1, term: '毛利', definition: '销售收入减去销售成本的差额', sqlSnippet: 'revenue - cost', aliases: ['毛利润', 'gross_profit'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 2, term: '毛利率', definition: '毛利占销售收入的比例，反映盈利能力', sqlSnippet: '(revenue - cost) / NULLIF(revenue, 0)', aliases: ['毛利润率', 'gross_margin'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 3, term: '毛利额', definition: '与毛利相同，强调绝对金额', sqlSnippet: 'revenue - cost', aliases: ['绝对毛利'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 4, term: '本月', definition: '当前自然月', sqlSnippet: "YEAR(col) = YEAR(GETDATE()) AND MONTH(col) = MONTH(GETDATE())", aliases: ['当月', '本月'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 5, term: '本季度', definition: '当前自然季度', sqlSnippet: "DATEPART(QUARTER, col) = DATEPART(QUARTER, GETDATE()) AND YEAR(col) = YEAR(GETDATE())", aliases: ['当前季度'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 6, term: '本年', definition: '当前自然年', sqlSnippet: 'YEAR(col) = YEAR(GETDATE())', aliases: ['今年', '本年度'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 7, term: '上月', definition: '上一个自然月', sqlSnippet: "DATEADD(MONTH, -1, GETDATE())", aliases: ['上个月'], updatedAt: '2026-04-01T10:00:00Z' },
  { id: 8, term: '上季度', definition: '上一个自然季度', sqlSnippet: "DATEPART(QUARTER, DATEADD(QUARTER, -1, GETDATE()))", aliases: ['上个季度'], updatedAt: '2026-04-01T10:00:00Z' },
];

// Join Edges
export const META_JOINS: JoinEdge[] = [
  { id: 1, fromTable: 'gross_profit_detail', fromColumn: 'org_id', toTable: 'org_tree', toColumn: 'id', joinType: 'LEFT', description: '毛利明细关联组织机构' },
  { id: 2, fromTable: 'gross_profit_detail', fromColumn: 'product_category', toTable: 'product_master', toColumn: 'category_code', joinType: 'LEFT', description: '毛利明细关联产品分类' },
];

// SQL Examples
export const META_EXAMPLES: SqlExample[] = [
  { id: 1, question: '子公司A今年各部门毛利率', sql: "SELECT o.name AS 部门, SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0) AS 毛利率 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE o.parent_id=2 AND YEAR(d.period_year)=YEAR(GETDATE()) GROUP BY o.name", domain: '毛利分析', orgScope: [2], isActive: true, createdAt: '2026-04-01T10:00:00Z' },
  { id: 2, question: '本月各子公司毛利额对比', sql: "SELECT o.name AS 子公司, SUM(d.revenue-d.cost) AS 毛利额 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE d.level=2 AND MONTH(d.period_month)=MONTH(GETDATE()) AND YEAR(d.period_month)=YEAR(GETDATE()) GROUP BY o.name", domain: '毛利分析', orgScope: undefined, isActive: true, createdAt: '2026-04-01T10:00:00Z' },
  { id: 3, question: '华东分公司上季度毛利趋势', sql: "SELECT d.period_quarter AS 季度, SUM(d.revenue-d.cost) AS 毛利额 FROM gross_profit_detail d WHERE d.org_id IN (SELECT id FROM org_tree WHERE id=2 OR parent_id=2) AND DATEPART(QUARTER, d.period_year*100+d.period_month/100) = DATEPART(QUARTER, DATEADD(QUARTER, -1, GETDATE())) GROUP BY d.period_quarter", domain: '毛利分析', orgScope: [2], isActive: true, createdAt: '2026-04-01T10:00:00Z' },
  { id: 4, question: '全公司本年毛利率', sql: "SELECT SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0) AS 本年毛利率 FROM gross_profit_detail d WHERE YEAR(d.period_year)=YEAR(GETDATE())", domain: '毛利分析', orgScope: undefined, isActive: true, createdAt: '2026-04-01T10:00:00Z' },
  { id: 5, question: '销售一部最近三个月毛利', sql: "SELECT d.period_month AS 月份, SUM(d.revenue-d.cost) AS 毛利额 FROM gross_profit_detail d WHERE d.org_id=11 AND d.period_month >= FORMAT(DATEADD(MONTH, -3, GETDATE()), 'yyyyMM') GROUP BY d.period_month ORDER BY d.period_month", domain: '毛利分析', orgScope: [11], isActive: true, createdAt: '2026-04-01T10:00:00Z' },
  { id: 6, question: '各产品分类本月收入成本', sql: "SELECT p.category_name AS 产品分类, SUM(d.revenue) AS 销售收入, SUM(d.cost) AS 销售成本 FROM gross_profit_detail d LEFT JOIN product_master p ON d.product_category=p.category_code WHERE MONTH(d.period_month)=MONTH(GETDATE()) AND YEAR(d.period_month)=YEAR(GETDATE()) GROUP BY p.category_name", domain: '收入分析', orgScope: undefined, isActive: true, createdAt: '2026-04-01T10:00:00Z' },
];
