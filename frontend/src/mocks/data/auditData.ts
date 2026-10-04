import { beijingTime } from '@/utils/formatters';

// Generate 30 days of audit logs
const now = new Date();

export interface AuditLogItem {
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

export interface AuditLogDetail extends AuditLogItem {
  fullSql: string;
  promptSummary: string;
  warnings: string[];
  retryCount: number;
  errorMessage: string | null;
}

const users = [
  { userId: 'u001', username: 'admin', displayName: '系统管理员' },
  { userId: 'u002', username: 'zhangsan', displayName: '张三' },
  { userId: 'u003', username: 'lisi', displayName: '李四' },
];

const orgs = [
  { orgId: 'org-root', orgName: '华夏集团' },
  { orgId: 'org-sub-1', orgName: '华夏贸易有限公司' },
  { orgId: 'org-sub-2', orgName: '华夏科技有限公司' },
  { orgId: 'org-sub-3', orgName: '华夏服务有限公司' },
];

const questions = [
  '本月各部门毛利率对比',
  '近12月毛利趋势分析',
  '各子公司毛利占比排名',
  '本季度明细数据查询',
  '部门收入成本汇总',
  '上月与本月对比',
  '年度毛利率走势',
  '各产品线毛利分析',
];

const statuses: AuditLogItem['status'][] = ['success', 'success', 'success', 'success', 'success', 'success', 'success', 'low_confidence', 'low_confidence', 'success', 'success', 'success', 'success', 'failed', 'success', 'success', 'success', 'success', 'success', 'low_confidence', 'success', 'success', 'success', 'success', 'success', 'success', 'success', 'low_confidence', 'success', 'success'];

// Generate audit logs
export const AUDIT_LOGS: AuditLogItem[] = Array.from({ length: 30 }, (_, i) => {
  const date = new Date(now);
  date.setDate(date.getDate() - Math.floor(i * 1));
  date.setHours(9 + Math.floor(Math.random() * 8));
  date.setMinutes(Math.floor(Math.random() * 60));

  const user = users[i % users.length];
  const org = orgs[i % orgs.length];
  const status = statuses[i];
  const question = questions[i % questions.length];
  const confidence = status === 'success' ? 0.75 + Math.random() * 0.2 : status === 'low_confidence' ? 0.45 + Math.random() * 0.15 : 0.3 + Math.random() * 0.2;

  return {
    requestId: `audit-${String(i + 1).padStart(4, '0')}`,
    userId: user.userId,
    username: user.username,
    displayName: user.displayName,
    orgId: org.orgId,
    orgName: org.orgName,
    question,
    sqlSnippet: `SELECT TOP 1000 o.name AS 部门, SUM(d.revenue-d.cost)/NULLIF...`,
    status,
    confidence: Math.round(confidence * 100) / 100,
    promptTokens: 1200 + Math.floor(Math.random() * 800),
    completionTokens: 200 + Math.floor(Math.random() * 300),
    latencyMs: 1200 + Math.floor(Math.random() * 1500),
    rowCount: 3 + Math.floor(Math.random() * 15),
    createdAt: beijingTime(date).format('YYYY-MM-DD HH:mm:ss'),
  };
});

// Audit stats
export const AUDIT_STATS = {
  totalQueries: AUDIT_LOGS.length,
  successRate: Math.round((AUDIT_LOGS.filter(l => l.status === 'success').length / AUDIT_LOGS.length) * 100),
  avgLatencyMs: Math.round(AUDIT_LOGS.reduce((sum, l) => sum + l.latencyMs, 0) / AUDIT_LOGS.length),
  avgConfidence: Math.round(AUDIT_LOGS.reduce((sum, l) => sum + l.confidence, 0) / AUDIT_LOGS.length * 100) / 100,
};
