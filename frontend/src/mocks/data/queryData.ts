import type { QueryResponse, FavoriteItem } from '@/types';
import { beijingTime } from '@/utils/formatters';

// 6 scenarios for query responses - snake_case format
export const SCENARIO_MONTHLY_BAR: QueryResponse = {
  request_id: 'req-monthly-bar-001',
  is_favorited: false,
  sql: 'SELECT TOP 1000 o.name AS 部门, ROUND(SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0)*100, 2) AS 毛利率 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE o.parent_id=2 AND YEAR(d.period_date)=YEAR(GETDATE()) GROUP BY o.name',
  table: {
    columns: ['部门', '毛利率(%)'],
    rows: [
      { '部门': '销售一部', '毛利率(%)': 32.5 },
      { '部门': '销售二部', '毛利率(%)': 28.1 },
      { '部门': '市场部', '毛利率(%)': 35.7 },
    ],
  },
  result_profile: { recommended_chart: 'bar' },
  summary: '本月各部门毛利率：市场部最高(35.7%)，销售二部最低(28.1%)，整体毛利率处于健康水平。',
  warnings: [],
  audit: { confidence: 0.92 },
};

export const SCENARIO_TREND_LINE: QueryResponse = {
  request_id: 'req-trend-line-001',
  is_favorited: false,
  sql: 'SELECT TOP 1000 period_month AS 月份, SUM(revenue-cost) AS 毛利额 FROM gross_profit_detail WHERE org_id IN (2,11,12,13) AND period_year=2026 GROUP BY period_month ORDER BY period_month',
  table: {
    columns: ['月份', '毛利额(万元)'],
    rows: [
      { '月份': '2026-01', '毛利额(万元)': 1280.5 },
      { '月份': '2026-02', '毛利额(万元)': 1456.8 },
      { '月份': '2026-03', '毛利额(万元)': 1392.3 },
      { '月份': '2026-04', '毛利额(万元)': 1567.2 },
      { '月份': '2026-05', '毛利额(万元)': 1789.4 },
      { '月份': '2026-06', '毛利额(万元)': 1923.1 },
    ],
  },
  result_profile: { recommended_chart: 'line' },
  summary: '近6月毛利额呈上升趋势，6月达到峰值1923.1万元。',
  warnings: [],
  audit: { confidence: 0.88 },
};

export const SCENARIO_DEPT_COMPARE_BAR: QueryResponse = {
  request_id: 'req-dept-compare-001',
  is_favorited: false,
  sql: 'SELECT TOP 1000 o.name AS 部门, SUM(d.revenue-d.cost) AS 毛利额 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE YEAR(d.period_date)=2026 GROUP BY o.name ORDER BY 毛利额 DESC',
  table: {
    columns: ['部门', '毛利额(万元)'],
    rows: [
      { '部门': '销售一部', '毛利额(万元)': 3560.2 },
      { '部门': '市场部', '毛利额(万元)': 2890.5 },
      { '部门': '销售二部', '毛利额(万元)': 2340.8 },
    ],
  },
  result_profile: { recommended_chart: 'bar' },
  summary: '本年各部门毛利额排名：销售一部领先(3560.2万元)。',
  warnings: [],
  audit: { confidence: 0.91 },
};

export const SCENARIO_RANKING_PIE: QueryResponse = {
  request_id: 'req-ranking-pie-001',
  is_favorited: false,
  sql: 'SELECT TOP 1000 o.name AS 子公司, SUM(d.revenue-d.cost) AS 毛利额 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE o.level=2 AND YEAR(d.period_date)=YEAR(GETDATE()) GROUP BY o.name',
  table: {
    columns: ['子公司', '毛利额(万元)'],
    rows: [
      { '子公司': '华夏贸易有限公司', '毛利额(万元)': 8920.3 },
      { '子公司': '华夏科技有限公司', '毛利额(万元)': 5670.8 },
      { '子公司': '华夏服务有限公司', '毛利额(万元)': 3456.2 },
    ],
  },
  result_profile: { recommended_chart: 'pie' },
  summary: '各子公司毛利占比：华夏贸易有限公司52.3%。',
  warnings: [],
  audit: { confidence: 0.95 },
};

export const SCENARIO_TABLE_ONLY: QueryResponse = {
  request_id: 'req-table-only-001',
  is_favorited: false,
  sql: 'SELECT TOP 1000 d.period_month, o.name AS 部门, d.revenue, d.cost, d.revenue-d.cost AS 毛利额 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE YEAR(d.period_date)=2026 ORDER BY d.period_month DESC',
  table: {
    columns: ['月份', '部门', '销售收入(万元)', '销售成本(万元)', '毛利额(万元)'],
    rows: [
      { '月份': '2026-12', '部门': '销售一部', '销售收入(万元)': 3456.2, '销售成本(万元)': 2334.8, '毛利额(万元)': 1121.4 },
      { '月份': '2026-12', '部门': '销售二部', '销售收入(万元)': 2890.5, '销售成本(万元)': 1987.3, '毛利额(万元)': 903.2 },
    ],
  },
  result_profile: { recommended_chart: 'table' },
  summary: '近几月各部门毛利明细，共2条记录。',
  warnings: [],
  audit: { confidence: 0.78 },
};

export const SCENARIO_WARNING: QueryResponse = {
  request_id: 'req-warning-001',
  is_favorited: false,
  sql: 'SELECT TOP 1000 o.name AS 部门, SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0)*100 AS 毛利率 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE YEAR(d.period_date)=YEAR(GETDATE()) GROUP BY o.name',
  table: {
    columns: ['部门', '毛利率(%)'],
    rows: [
      { '部门': '销售一部', '毛利率(%)': 31.2 },
      { '部门': '销售二部', '毛利率(%)': null },
      { '部门': '市场部', '毛利率(%)': 34.8 },
    ],
  },
  result_profile: { recommended_chart: 'bar' },
  summary: '本年各部门毛利率分布。部分部门数据可能不完整。',
  warnings: [
    '查询结果已限制为 TOP 1000 行',
    '部分部门数据可能不完整（销售二部缺少数据）',
    '数据更新存在2天延迟',
  ],
  audit: { confidence: 0.61 },
};

// History items for mock data - snake_case + full response
export interface QueryHistoryItem {
  request_id: string;
  user_id?: number | string;
  username?: string;
  display_name?: string;
  question: string;
  org_id: string;
  org_name: string;
  status: 'success' | 'failed' | 'low_confidence';
  recommended_chart: 'bar' | 'line' | 'pie' | 'table';
  row_count: number;
  confidence: number;
  latency_ms: number;
  created_at: string;
  is_favorite: boolean;
  feedback: 'good' | 'bad' | null;
  response: QueryResponse;
}

// Generate 15 history items spanning 30 days
const now = new Date();
export const MOCK_HISTORY: QueryHistoryItem[] = Array.from({ length: 15 }, (_, i) => {
  const date = new Date(now);
  date.setDate(date.getDate() - Math.floor(i * 2));
  const statuses: QueryHistoryItem['status'][] = ['success', 'success', 'success', 'success', 'success', 'success', 'success', 'low_confidence', 'low_confidence', 'success', 'success', 'success', 'success', 'failed', 'success'];
  const charts: QueryHistoryItem['recommended_chart'][] = ['bar', 'line', 'pie', 'bar', 'table', 'line', 'bar', 'pie', 'bar', 'line', 'bar', 'table', 'pie', 'bar', 'line'];
  const scenarios = [SCENARIO_MONTHLY_BAR, SCENARIO_TREND_LINE, SCENARIO_RANKING_PIE, SCENARIO_TABLE_ONLY, SCENARIO_DEPT_COMPARE_BAR];
  const scenario = scenarios[i % 5];
  const feedbackOptions: ('good' | 'bad' | null)[] = ['good', 'bad', null, null, 'good', null, null, null, null, null, null, null, null, null, null];

  return {
    request_id: `req-history-${String(i + 1).padStart(3, '0')}`,
    user_id: [1, 2, 3][i % 3],
    username: ['admin', 'manager', 'demo'][i % 3],
    display_name: ['系统管理员', '部门经理', '演示用户'][i % 3],
    question: ['本月各部门毛利对比', '近12月毛利趋势', '各子公司毛利占比', '本季度明细查询', '部门排名分析'][i % 5],
    org_id: ['org-root', 'org-sub-1', 'org-sub-2', 'org-sub-3'][i % 4],
    org_name: ['华夏集团', '华夏贸易有限公司', '华夏科技有限公司', '华夏服务有限公司'][i % 4],
    status: statuses[i],
    recommended_chart: charts[i],
    row_count: [12, 8, 15, 6, 9, 10, 14, 5, 11, 7, 13, 8, 16, 4, 12][i],
    confidence: [0.92, 0.88, 0.95, 0.78, 0.85, 0.91, 0.87, 0.65, 0.72, 0.89, 0.93, 0.76, 0.94, 0.55, 0.86][i],
    latency_ms: [1850, 2100, 1650, 1200, 1450, 1980, 1720, 1550, 1890, 2100, 1780, 1320, 2050, 950, 1680][i],
    created_at: beijingTime(date).format('YYYY-MM-DD HH:mm:ss'),
    is_favorite: i % 4 === 0,
    feedback: feedbackOptions[i],
    response: {
      ...scenario,
      request_id: `req-history-${String(i + 1).padStart(3, '0')}`,
    },
  };
});

// Favorites list
export const MOCK_FAVORITES: FavoriteItem[] = [
  {
    request_id: 'req-history-001',
    question: '本月各部门毛利对比',
    org_id: 'org-sub-1',
    org_name: '华夏贸易有限公司',
    recommended_chart: 'bar',
    row_count: 12,
    favorited_at: '2026-04-01T10:30:00Z',
    last_run_at: '2026-04-08T14:22:00Z',
  },
  {
    request_id: 'req-history-002',
    question: '近12月毛利趋势',
    org_id: 'org-root',
    org_name: '华夏集团',
    recommended_chart: 'line',
    row_count: 12,
    favorited_at: '2026-03-28T09:15:00Z',
    last_run_at: '2026-04-07T16:45:00Z',
  },
];
