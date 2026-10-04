import { apiGet, apiPost, apiPut, apiDelete } from './request';
import type {
  DataSource,
  DDLParseResponse,
  DDLParseResult,
  MetaTable,
  MetaColumn,
  Glossary,
  JoinEdge,
  SqlExample,
} from '@/types';

type BackendGlossary = {
  id: number;
  term: string;
  definition?: string;
  sqlSnippet?: string;
  aliases?: string | string[] | null;
  orgScope?: number | number[] | null;
  active?: boolean;
  updatedAt?: string;
};

type BackendJoinEdge = {
  id: number;
  fromTableId?: number;
  toTableId?: number;
  fromTable?: string;
  toTable?: string;
  fromColumn: string;
  toColumn: string;
  joinType: string;
  description?: string;
  active?: boolean;
};

type BackendSqlExample = {
  id: number;
  question: string;
  sqlQuery?: string;
  sql?: string;
  domain?: string;
  orgScope?: number | number[] | null;
  active?: boolean;
  isActive?: boolean;
  createdAt?: string;
};

const toMetaTable = (table: Record<string, unknown>): MetaTable => ({
  id: Number(table.id),
  datasourceId: Number(table.datasourceId ?? table.datasource_id ?? 1),
  tableName: String(table.tableName ?? table.table_name ?? ''),
  label: (table.label as string | undefined) ?? undefined,
  description: (table.description as string | undefined) ?? undefined,
  schemaName: (table.schemaName as string | undefined) ?? (table.schema_name as string | undefined),
  isActive: Boolean(table.active ?? table.isActive ?? table.is_active ?? true),
  isPublished: Boolean(table.published ?? table.isPublished ?? table.is_published ?? false),
  updatedAt: (table.updatedAt as string | undefined) ?? (table.updated_at as string | undefined),
});

const toMetaColumn = (column: Record<string, unknown>): MetaColumn => ({
  id: Number(column.id),
  tableId: Number(column.tableId ?? column.table_id),
  columnName: String(column.columnName ?? column.column_name ?? ''),
  label: (column.label as string | undefined) ?? undefined,
  dataType: (column.dataType as string | undefined) ?? (column.data_type as string | undefined),
  description: (column.description as string | undefined) ?? undefined,
  isSensitive: String(column.sensitivity ?? '').toLowerCase() !== 'normal',
  isActive: Boolean(column.active ?? column.isActive ?? column.is_active ?? true),
  commentSource: ((column.commentSource as 'ddl' | 'manual' | undefined)
    ?? (column.comment_source as 'ddl' | 'manual' | undefined)),
});

const toGlossary = (item: BackendGlossary): Glossary => ({
  id: item.id,
  term: item.term,
  definition: item.definition,
  sqlSnippet: item.sqlSnippet,
  aliases: Array.isArray(item.aliases)
    ? item.aliases
    : item.aliases ? item.aliases.split(',').map((value) => value.trim()).filter(Boolean) : [],
  orgScope: Array.isArray(item.orgScope)
    ? item.orgScope
    : item.orgScope != null ? [item.orgScope] : [],
  updatedAt: item.updatedAt,
});

const toBackendGlossary = (data: Partial<Glossary>): Partial<BackendGlossary> => ({
  term: data.term,
  definition: data.definition,
  sqlSnippet: data.sqlSnippet,
  aliases: data.aliases?.join(','),
  orgScope: data.orgScope && data.orgScope.length > 0 ? Number(data.orgScope[0]) : null,
  active: true,
});

const toSqlExample = (item: BackendSqlExample): SqlExample => ({
  id: item.id,
  question: item.question,
  sql: item.sqlQuery ?? item.sql ?? '',
  domain: item.domain,
  orgScope: Array.isArray(item.orgScope)
    ? item.orgScope
    : item.orgScope != null ? [item.orgScope] : [],
  isActive: Boolean(item.active ?? item.isActive ?? true),
  createdAt: item.createdAt,
});

const toDDLParseResult = (item: Record<string, unknown>): DDLParseResult => ({
  tableName: String(item.tableName ?? item.table_name ?? ''),
  label: (item.label as string | undefined)
    ?? (item.suggestedLabel as string | undefined)
    ?? (item.suggested_label as string | undefined),
  description: (item.description as string | undefined) ?? undefined,
  schemaName: (item.schemaName as string | undefined) ?? (item.schema_name as string | undefined),
  columns: Array.isArray(item.columns)
    ? item.columns.map((column) => ({
        columnName: String((column as Record<string, unknown>).columnName ?? (column as Record<string, unknown>).column_name ?? ''),
        dataType: String((column as Record<string, unknown>).dataType ?? (column as Record<string, unknown>).data_type ?? ''),
        label: ((column as Record<string, unknown>).label as string | undefined) ?? undefined,
        description: ((column as Record<string, unknown>).description as string | undefined) ?? undefined,
        commentSource: (((column as Record<string, unknown>).commentSource as 'ddl' | 'manual' | undefined)
          ?? ((column as Record<string, unknown>).comment_source as 'ddl' | 'manual' | undefined)
          ?? 'manual'),
      }))
    : [],
});

// DDL 解析
export const parseDDL = async (rawDdl: string, datasourceId?: number): Promise<DDLParseResponse> => {
  const result = await apiPost<Record<string, unknown>>('/v1/metadata/ddl/parse', { rawDdl, datasourceId });
  const tables = Array.isArray(result.tables)
    ? result.tables.map((table) => toDDLParseResult(table as Record<string, unknown>))
    : [toDDLParseResult(result)];
  const normalizedDatasourceId = result.datasourceId ?? result.datasource_id ?? datasourceId;

  return {
    uploadId: (result.uploadId as string | undefined) ?? (result.upload_id as string | undefined),
    datasourceId: normalizedDatasourceId == null ? undefined : Number(normalizedDatasourceId),
    tables,
    warnings: Array.isArray(result.warnings) ? result.warnings.map(String) : [],
  };
};

export const confirmDDL = (data: {
  parsedTables: DDLParseResult[];
  datasourceId?: number;
}): Promise<void> => {
  return apiPost('/v1/metadata/ddl/confirm', {
    datasourceId: data.datasourceId,
    tables: data.parsedTables.map((table) => ({
      schemaName: table.schemaName,
      tableName: table.tableName,
      suggestedLabel: table.label,
      description: table.description,
      columns: table.columns.map((column) => ({
        columnName: column.columnName,
        dataType: column.dataType,
        label: column.label,
        description: column.description,
        commentSource: column.commentSource,
        needsReview: column.commentSource === 'manual' && (!column.label || !column.description),
      })),
    })),
  });
};

// 表管理
export const fetchTables = (): Promise<MetaTable[]> => {
  return apiGet<Record<string, unknown>[]>('/v1/metadata/tables').then((items) => items.map(toMetaTable));
};

export const updateTable = (
  id: number,
  data: Partial<MetaTable>
): Promise<MetaTable> => {
  return apiPut<Record<string, unknown>>(`/v1/metadata/tables/${id}`, {
    label: data.label,
    description: data.description,
    active: data.isActive,
    published: data.isPublished,
  }).then(toMetaTable);
};

export const fetchColumns = (
  tableId: number
): Promise<MetaColumn[]> => {
  return apiGet<Record<string, unknown>[]>(`/v1/metadata/tables/${tableId}/columns`).then((items) => items.map(toMetaColumn));
};

export const updateColumn = (
  id: number,
  data: Partial<MetaColumn>
): Promise<MetaColumn> => {
  return apiPut<Record<string, unknown>>(`/v1/metadata/columns/${id}`, {
    label: data.label,
    description: data.description,
    commentSource: data.commentSource,
  }).then(toMetaColumn);
};

export const createColumn = (
  tableId: number,
  data: Partial<MetaColumn>
): Promise<MetaColumn> => {
  return apiPost<Record<string, unknown>>(`/v1/metadata/tables/${tableId}/columns`, {
    columnName: data.columnName,
    dataType: data.dataType,
    label: data.label,
    description: data.description,
    commentSource: data.commentSource ?? 'manual',
  }).then(toMetaColumn);
};

export const deleteColumn = (id: number): Promise<void> => {
  return apiDelete(`/v1/metadata/columns/${id}`);
};

// 术语词典
export const fetchGlossary = (params?: {
  keyword?: string;
  page?: number;
  pageSize?: number;
}): Promise<{ list: Glossary[]; total: number; page: number; pageSize: number }> => {
  return apiGet<BackendGlossary[] | { list: BackendGlossary[]; total: number; page: number; pageSize: number }>(
    '/v1/metadata/glossary',
    params
  ).then((result) => {
    const items = Array.isArray(result) ? result : result.list;
    return {
      list: items.map(toGlossary),
      total: Array.isArray(result) ? items.length : result.total,
      page: params?.page ?? 1,
      pageSize: params?.pageSize ?? items.length,
    };
  });
};

export const createGlossary = (data: Partial<Glossary>): Promise<Glossary> => {
  return apiPost<BackendGlossary>('/v1/metadata/glossary', toBackendGlossary(data)).then(toGlossary);
};

export const updateGlossary = (
  id: number,
  data: Partial<Glossary>
): Promise<Glossary> => {
  return apiPut<BackendGlossary>(`/v1/metadata/glossary/${id}`, toBackendGlossary(data)).then(toGlossary);
};

export const deleteGlossary = (id: number): Promise<void> => {
  return apiDelete(`/v1/metadata/glossary/${id}`);
};

// JOIN 关系
export const fetchJoins = (): Promise<JoinEdge[]> => {
  return Promise.all([
    apiGet<BackendJoinEdge[]>('/v1/metadata/joins'),
    fetchTables(),
  ]).then(([joins, tables]) => {
    const tableNameMap = new Map<number, string>(tables.map((table) => [table.id, table.tableName]));
    return joins.map((item) => ({
      id: item.id,
      fromTable: item.fromTable ?? (item.fromTableId == null ? '' : tableNameMap.get(item.fromTableId) ?? String(item.fromTableId)),
      fromColumn: item.fromColumn,
      toTable: item.toTable ?? (item.toTableId == null ? '' : tableNameMap.get(item.toTableId) ?? String(item.toTableId)),
      toColumn: item.toColumn,
      joinType: item.joinType,
      description: item.description,
    }));
  });
};

export const createJoin = (data: Partial<JoinEdge>): Promise<JoinEdge> => {
  return fetchTables().then((tables) => {
    const fromTable = tables.find((item) => item.tableName === data.fromTable);
    const toTable = tables.find((item) => item.tableName === data.toTable);
    return apiPost<BackendJoinEdge>('/v1/metadata/joins', {
      fromTableId: fromTable?.id,
      toTableId: toTable?.id,
      fromTable: data.fromTable,
      toTable: data.toTable,
      fromColumn: data.fromColumn,
      toColumn: data.toColumn,
      joinType: data.joinType,
      description: data.description,
      active: true,
    }).then((item) => ({
      id: item.id,
      fromTable: data.fromTable ?? '',
      fromColumn: item.fromColumn,
      toTable: data.toTable ?? '',
      toColumn: item.toColumn,
      joinType: item.joinType,
      description: item.description,
    }));
  });
};

export const deleteJoin = (id: number): Promise<void> => {
  return apiDelete(`/v1/metadata/joins/${id}`);
};

// 问答示例
export const fetchExamples = (params?: { enabled?: boolean }): Promise<SqlExample[]> => {
  return apiGet<BackendSqlExample[]>('/v1/metadata/examples', params).then((items) => items.map(toSqlExample));
};

export const createExample = (data: Partial<SqlExample>): Promise<SqlExample> => {
  return apiPost<BackendSqlExample>('/v1/metadata/examples', {
    question: data.question,
    sqlQuery: data.sql,
    sql: data.sql,
    domain: data.domain,
    orgScope: data.orgScope && data.orgScope.length > 0 ? Number(data.orgScope[0]) : null,
    active: data.isActive ?? true,
    isActive: data.isActive ?? true,
  }).then(toSqlExample);
};

export const updateExample = (
  id: number,
  data: Partial<SqlExample>
): Promise<SqlExample> => {
  return apiPut<BackendSqlExample>(`/v1/metadata/examples/${id}`, {
    question: data.question,
    sqlQuery: data.sql,
    sql: data.sql,
    domain: data.domain,
    orgScope: data.orgScope && data.orgScope.length > 0 ? Number(data.orgScope[0]) : null,
    active: data.isActive,
    isActive: data.isActive,
  }).then(toSqlExample);
};

export const deleteExample = (id: number): Promise<void> => {
  return apiDelete(`/v1/metadata/examples/${id}`);
};

export const toggleExample = (id: number): Promise<SqlExample> => {
  return apiGet<BackendSqlExample[]>(`/v1/metadata/examples`, { id }).then((items) => {
    const item = items.find((example) => example.id === id);
    if (!item) {
      throw new Error('示例不存在');
    }
    return updateExample(id, { isActive: !Boolean(item.active ?? true) });
  });
};

export const fetchMetadataDatasources = (): Promise<DataSource[]> => {
  return apiGet<Record<string, unknown>[]>('/v1/datasources').then((items) =>
    items.map((item) => ({
      id: Number(item.id),
      name: String(item.name ?? ''),
      dbType: String(item.dbType ?? item.db_type ?? 'postgresql') as DataSource['dbType'],
      jdbcUrl: (item.jdbcUrl as string | undefined) ?? (item.jdbc_url as string | undefined),
      username: (item.username as string | undefined) ?? undefined,
      description: (item.description as string | undefined) ?? undefined,
      isReadOnly: Boolean(item.readOnly ?? item.read_only ?? true),
      isActive: Boolean(item.active ?? item.isActive ?? item.is_active ?? true),
      createdAt: (item.createdAt as string | undefined) ?? (item.created_at as string | undefined),
      updatedAt: (item.updatedAt as string | undefined) ?? (item.updated_at as string | undefined),
    }))
  );
};

export const fetchTablesByDatasource = (datasourceId: number): Promise<MetaTable[]> => {
  return apiGet<Record<string, unknown>[]>('/v1/metadata/tables', { datasourceId }).then((items) =>
    items.map((item) => toMetaTable(item))
  );
};

export const fetchJoinsByDatasource = (datasourceId: number): Promise<JoinEdge[]> => {
  return Promise.all([
    apiGet<BackendJoinEdge[]>('/v1/metadata/joins'),
    fetchTablesByDatasource(datasourceId),
  ]).then(([joins, tables]) => {
    const tableNameMap = new Map<number, string>(tables.map((table) => [table.id, table.tableName]));
    const tableIds = new Set(tables.map((table) => table.id));
    const tableNames = new Set(tables.map((table) => table.tableName));

    return joins
      .filter((item) => (
        (item.fromTableId != null && item.toTableId != null && tableIds.has(item.fromTableId) && tableIds.has(item.toTableId)) ||
        (item.fromTable != null && item.toTable != null && tableNames.has(item.fromTable) && tableNames.has(item.toTable))
      ))
      .map((item) => ({
        id: item.id,
        fromTable: item.fromTable ?? (item.fromTableId == null ? '' : tableNameMap.get(item.fromTableId) ?? String(item.fromTableId)),
        fromColumn: item.fromColumn,
        toTable: item.toTable ?? (item.toTableId == null ? '' : tableNameMap.get(item.toTableId) ?? String(item.toTableId)),
        toColumn: item.toColumn,
        joinType: item.joinType,
        description: item.description,
      }));
  });
};
