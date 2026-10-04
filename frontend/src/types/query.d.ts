/** 查询请求 */
export interface QueryRequest {
  question: string;
  orgId: number | string;
  sessionId?: string;
  context?: string;
}

/** 查询响应 — snake_case 格式与后端 API 契约一致 */
export interface QueryResponse {
  request_id: string;
  sql?: string; // 仅 admin 角色响应中包含
  is_favorited?: boolean;
  table: TableData;
  result_profile: ResultProfile;
  summary: string;
  warnings: string[];
  audit: AuditInfo;
}

export interface TableColumn {
  key: string;
  title: string;
  dataType?: string;
}

export interface TableData {
  columns: Array<TableColumn | string>;
  rows: Record<string, unknown>[];
}

export interface ResultProfile {
  recommended_chart: 'bar' | 'line' | 'pie' | 'table' | 'kpi';
  row_count?: number;
  column_count?: number;
  has_data?: boolean;
}

export interface AuditInfo {
  confidence: number;
  retry_count?: number;
  execution_ms?: number;
  prompt_tokens?: number;
  completion_tokens?: number;
  latency_ms?: number;
}

/** 查询历史记录 */
export interface QueryHistory {
  request_id: string;
  user_id?: number | string;
  username?: string;
  display_name?: string;
  question: string;
  org_id: number | string;
  org_name?: string;
  response: QueryResponse;
  created_at: string;
  is_favorite?: boolean;
  feedback?: 'good' | 'bad' | null;
}

/** 收藏项 */
export interface FavoriteItem {
  request_id: string;
  question: string;
  org_id: string;
  org_name: string;
  recommended_chart: 'bar' | 'line' | 'pie' | 'table' | 'kpi';
  row_count: number;
  favorited_at: string;
  last_run_at: string;
}
