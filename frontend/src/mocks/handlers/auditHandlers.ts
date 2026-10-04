import { http } from 'msw';
import { ok, httpError, okPage } from './utils';
import { isAdminFromToken } from '../helpers/authHelpers';
import { AUDIT_LOGS, AUDIT_STATS, type AuditLogDetail } from '../data/auditData';

export const auditHandlers = [
  // Get audit logs with filtering and pagination
  http.get('/api/v1/audit/logs', async ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? 1);
    const pageSize = Number(url.searchParams.get('pageSize') ?? 10);
    const startDate = url.searchParams.get('startDate');
    const endDate = url.searchParams.get('endDate');
    const userId = url.searchParams.get('userId');
    const orgId = url.searchParams.get('orgId');
    const status = url.searchParams.get('status');
    const minConfidence = Number(url.searchParams.get('minConfidence') ?? 0);
    const maxConfidence = Number(url.searchParams.get('maxConfidence') ?? 1);

    let filtered = [...AUDIT_LOGS];

    if (startDate) {
      filtered = filtered.filter(l => l.createdAt >= startDate);
    }
    if (endDate) {
      filtered = filtered.filter(l => l.createdAt <= endDate);
    }
    if (userId) {
      filtered = filtered.filter(l => l.userId === userId);
    }
    if (orgId) {
      filtered = filtered.filter(l => l.orgId === orgId);
    }
    if (status) {
      filtered = filtered.filter(l => l.status === status);
    }
    filtered = filtered.filter(l => l.confidence >= minConfidence && l.confidence <= maxConfidence);

    // Sort by createdAt descending
    filtered.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

    return okPage(filtered, page, pageSize);
  }),

  // Get audit log detail (admin only)
  http.get('/api/v1/audit/logs/:requestId', async ({ params, request }) => {
    const authHeader = request.headers.get('Authorization') ?? '';
    if (!isAdminFromToken(authHeader)) {
      return httpError(403, 'FORBIDDEN', '无权限访问审计日志详情');
    }

    const log = AUDIT_LOGS.find(l => l.requestId === params.requestId);
    if (!log) {
      return httpError(404, 'NOT_FOUND', '审计日志不存在');
    }

    const detail: AuditLogDetail = {
      ...log,
      fullSql: 'SELECT TOP 1000 o.name AS 部门, ROUND(SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0)*100, 2) AS 毛利率 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE o.parent_id=2 AND YEAR(d.period_date)=YEAR(GETDATE()) GROUP BY o.name',
      promptSummary: log.question,
      warnings: log.status === 'low_confidence' ? ['LLM 置信度较低(0.61)，结果仅供参考'] : [],
      retryCount: 0,
      errorMessage: log.status === 'failed' ? 'SQL 执行失败：连接超时' : null,
    };

    return ok(detail);
  }),

  // Get audit stats
  http.get('/api/v1/audit/stats', async () => {
    return ok(AUDIT_STATS);
  }),
];
