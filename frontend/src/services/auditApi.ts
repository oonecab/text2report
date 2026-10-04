import { apiGet } from './request';

interface AuditLogItem {
  requestId: string;
  userId: string;
  username: string;
  displayName: string;
  orgId: string;
  orgName: string;
  question: string;
  sqlSnippet: string;
  status: 'success' | 'failed' | 'low_confidence';
  confidence: number;
  promptTokens: number;
  completionTokens: number;
  latencyMs: number;
  rowCount: number;
  createdAt: string;
}

interface AuditLogDetail extends AuditLogItem {
  fullSql: string;
  promptSummary: string;
  warnings: string[];
  retryCount: number;
  errorMessage: string | null;
}

interface AuditStats {
  totalQueries: number;
  successRate: number;
  avgLatencyMs: number;
  avgConfidence: number;
}

interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  pageSize: number;
}

export const fetchAuditLogs = async (params?: {
  page?: number;
  pageSize?: number;
  startDate?: string;
  endDate?: string;
  userId?: string;
  orgId?: string;
  status?: string;
  minConfidence?: number;
  maxConfidence?: number;
}): Promise<PageResult<AuditLogItem>> => {
  return apiGet<PageResult<AuditLogItem>>('/v1/audit/logs', params);
};

export const fetchAuditLogDetail = async (requestId: string): Promise<AuditLogDetail> => {
  return apiGet<AuditLogDetail>(`/v1/audit/logs/${requestId}`);
};

export const fetchAuditStats = async (): Promise<AuditStats> => {
  return apiGet<AuditStats>('/v1/audit/stats');
};
