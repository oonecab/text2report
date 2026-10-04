/** 数据源 */
export interface DataSource {
  id: number;
  name: string;
  dbType: 'postgresql' | 'mysql' | 'sqlserver';
  jdbcUrl?: string;
  username?: string;
  description?: string;
  isReadOnly?: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/** 表元数据 */
export interface MetaTable {
  id: number;
  datasourceId: number;
  tableName: string;
  label?: string; // 中文名
  description?: string; // 业务说明
  schemaName?: string; // 如 dbo
  isActive: boolean;
  isPublished?: boolean;
  updatedAt?: string;
  updatedBy?: string;
}

/** 字段元数据 */
export interface MetaColumn {
  id: number;
  tableId: number;
  columnName: string;
  label?: string; // 中文名
  dataType?: string;
  description?: string; // 口径说明
  isSensitive: boolean;
  isActive: boolean;
  commentSource?: 'ddl' | 'manual'; // 注释来源
}

/** 业务术语 */
export interface Glossary {
  id: number;
  term: string;
  definition?: string;
  sqlSnippet?: string; // 对应 SQL 片段
  aliases?: string[]; // 别名
  orgScope?: number[]; // 适用组织 ID，null 表示全局
  updatedAt?: string;
}

/** JOIN 关系 */
export interface JoinEdge {
  id: number;
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
  joinType: string; // 'LEFT' | 'INNER' | 'RIGHT'
  description?: string;
}

/** 问答示例对 */
export interface SqlExample {
  id: number;
  question: string;
  sql: string;
  domain?: string;
  orgScope?: number[];
  isActive: boolean;
  createdAt?: string;
}

/** DDL 解析结果（预览用，未入库） */
export interface DDLParseResult {
  tableName: string;
  label?: string;
  description?: string;
  schemaName?: string;
  columns: DDLParsedColumn[];
}

/** 后端 DDL 解析响应 */
export interface DDLParseResponse {
  uploadId?: string;
  datasourceId?: number;
  tables: DDLParseResult[];
  warnings?: string[];
}

export interface DDLParsedColumn {
  columnName: string;
  dataType: string;
  label?: string;
  description?: string;
  commentSource: 'ddl' | 'manual';
}

/** DDL 上传记录 */
export interface DDLUploadRecord {
  id: number;
  rawDdl: string;
  parsedTables?: DDLParseResult[];
  uploadBy?: string;
  uploadAt?: string;
  status: 'pending' | 'completed' | 'failed';
}
