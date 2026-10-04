import { http, delay } from 'msw';
import { ok, bizError, httpError, okPage } from './utils';
import { isAdminFromToken } from '../helpers/authHelpers';
import { orgStorage } from '../data/orgData';
import {
  SCENARIO_MONTHLY_BAR,
  SCENARIO_TREND_LINE,
  SCENARIO_DEPT_COMPARE_BAR,
  SCENARIO_RANKING_PIE,
  SCENARIO_TABLE_ONLY,
  SCENARIO_WARNING,
  MOCK_HISTORY,
  MOCK_FAVORITES,
} from '../data/queryData';
import type { QueryHistoryItem } from '../data/queryData';
import type { FavoriteItem, QueryResponse } from '@/types';
import { nowInBeijing } from '@/utils/formatters';

// In-memory storage for feedback and favorites
const feedbackStorage: Map<string, 'good' | 'bad'> = new Map();
const favoritesStorage = new Map<string, FavoriteItem>(MOCK_FAVORITES.map(f => [f.request_id, {
  request_id: f.request_id,
  question: f.question,
  org_id: f.org_id,
  org_name: f.org_name,
  recommended_chart: f.recommended_chart,
  row_count: f.row_count,
  favorited_at: f.favorited_at,
  last_run_at: f.last_run_at,
}]));
const queryResultStorage = new Map<string, { question: string; org_id: string; org_name: string; recommended_chart: FavoriteItem['recommended_chart']; row_count: number; last_run_at: string }>();

// SQL Guard blocked keywords (case-insensitive) - already验收通过，不得修改
const BLOCKED_KEYWORDS = [
  // Write operations
  'insert', 'update', 'delete', 'merge',
  // DDL
  'drop', 'truncate', 'alter', 'create',
  // Permissions
  'grant', 'revoke',
  // Dangerous execution
  'exec', 'execute', 'xp_cmdshell', 'sp_executesql',
  // Chinese write operations
  '删除', '修改', '更新', '插入', '清空', '删表',
];

function isSqlGuardBlocked(question: string): boolean {
  const lower = question.toLowerCase();
  return BLOCKED_KEYWORDS.some(kw => lower.includes(kw.toLowerCase()));
}

// Filter response based on role - remove sql field for non-admin
function filterResponseByRole<T extends Partial<QueryResponse>>(response: T, isAdmin: boolean): T {
  if (isAdmin) {
    return response;
  }
  const rest = { ...response };
  delete rest.sql;
  return rest as T;
}

function findOrgName(orgId: string): string {
  const stack = [...orgStorage];
  while (stack.length > 0) {
    const node = stack.pop();
    if (!node) {
      continue;
    }
    if (String(node.id) === String(orgId)) {
      return node.name;
    }
    if (node.children?.length) {
      stack.push(...node.children);
    }
  }
  return orgId;
}

export const queryHandlers = [
  // Submit query
  http.post('/api/v1/query', async ({ request }) => {
    const body = await request.json() as { question: string; orgId: string };
    const q = body.question ?? '';
    const authHeader = request.headers.get('Authorization') ?? '';
    const isAdmin = isAdminFromToken(authHeader);

    // SQL Guard rejection - expanded keyword list
    if (isSqlGuardBlocked(q)) {
      return httpError(422, 'SQL_GUARD_REJECTED', '问题意图包含不允许的数据操作，已拒绝处理');
    }

    // Low confidence
    if (q.trim().length < 4) {
      return bizError('LOW_CONFIDENCE', '问题描述过于简短（置信度 0.28），请补充更多上下文');
    }

    // LLM timeout simulation
    if (q.includes('超时测试')) {
      await delay(3000);
      return httpError(504, 'LLM_TIMEOUT', 'LLM 调用超时，请稍后重试');
    }

    // Route to scenario - 不得修改场景路由逻辑
    await delay(1500 + Math.random() * 1000);

    let scenarioResponse: QueryResponse;
    if (q.includes('趋势') || q.includes('折线') || q.includes('走势')) {
      scenarioResponse = SCENARIO_TREND_LINE;
    } else if (q.includes('部门') && (q.includes('对比') || q.includes('比较'))) {
      scenarioResponse = SCENARIO_DEPT_COMPARE_BAR;
    } else if (q.includes('排名') || /top\s*\d/i.test(q)) {
      scenarioResponse = SCENARIO_RANKING_PIE;
    } else if (q.includes('警告') || q.includes('数据质量')) {
      scenarioResponse = SCENARIO_WARNING;
    } else if (q.includes('明细') || q.includes('清单')) {
      scenarioResponse = SCENARIO_TABLE_ONLY;
    } else {
      scenarioResponse = SCENARIO_MONTHLY_BAR;
    }

    // Filter sql field based on role
    const filteredResponse = filterResponseByRole({
      ...scenarioResponse,
      is_favorited: favoritesStorage.has(scenarioResponse.request_id),
    }, isAdmin);
    queryResultStorage.set(filteredResponse.request_id, {
      question: q,
      org_id: body.orgId,
      org_name: findOrgName(body.orgId),
      recommended_chart: filteredResponse.result_profile.recommended_chart,
      row_count: filteredResponse.table.rows.length,
      last_run_at: nowInBeijing(),
    });
    return ok(filteredResponse);
  }),

  // Get history
  http.get('/api/v1/query/history', async ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? url.searchParams.get('current') ?? 1);
    const pageSize = Number(url.searchParams.get('pageSize') ?? 10);
    const keyword = url.searchParams.get('keyword')?.trim() ?? '';
    const username = url.searchParams.get('username')?.trim() ?? '';
    const orgId = url.searchParams.get('orgId');
    const startDate = url.searchParams.get('startDate');
    const endDate = url.searchParams.get('endDate');
    const minConfidence = url.searchParams.get('minConfidence');
    const maxConfidence = url.searchParams.get('maxConfidence');
    const authHeader = request.headers.get('Authorization') ?? '';
    const isAdmin = isAdminFromToken(authHeader);

    // Build history with full response object
    const historyWithResponse: QueryHistoryItem[] = MOCK_HISTORY
      .filter(item => !keyword || item.question.includes(keyword))
      .filter(item => !username || item.username?.includes(username) || item.display_name?.includes(username))
      .filter(item => !orgId || String(item.org_id) === String(orgId))
      .filter(item => !startDate || item.created_at.slice(0, 10) >= startDate)
      .filter(item => !endDate || item.created_at.slice(0, 10) <= endDate)
      .filter(item => !minConfidence || item.confidence >= Number(minConfidence))
      .filter(item => !maxConfidence || item.confidence <= Number(maxConfidence))
      .map(item => {
        const filteredResponse = filterResponseByRole({
          ...getScenarioResponse(item.question),
          is_favorited: favoritesStorage.has(item.request_id),
        }, isAdmin);
        return {
          ...item,
          response: filteredResponse,
          feedback: feedbackStorage.get(item.request_id) ?? null,
          is_favorite: favoritesStorage.has(item.request_id),
        };
      });

    return okPage(historyWithResponse, page, pageSize);
  }),

  // Get history detail
  http.get('/api/v1/query/history/:requestId', async ({ params, request }) => {
    const { requestId } = params;
    const authHeader = request.headers.get('Authorization') ?? '';
    const isAdmin = isAdminFromToken(authHeader);

    const item = MOCK_HISTORY.find(h => h.request_id === requestId);
    if (!item) {
      return httpError(404, 'NOT_FOUND', '查询记录不存在');
    }
    const response = getScenarioResponse(item.question);
    const filteredResponse = filterResponseByRole(response, isAdmin);
    return ok({ ...item, response: filteredResponse });
  }),

  // Submit feedback
  http.post('/api/v1/query/:requestId/feedback', async ({ params, request }) => {
    const requestId = String(params.requestId);
    const body = await request.json() as { feedback?: 'good' | 'bad'; comment?: string };

    const item = MOCK_HISTORY.find(h => h.request_id === requestId);
    if (!item) {
      return httpError(404, 'NOT_FOUND', '查询记录不存在');
    }

    // Store feedback
    const feedbackValue = body.feedback ?? 'good';
    feedbackStorage.set(requestId, feedbackValue);

    return ok({ success: true });
  }),

  // Get favorites
  http.get('/api/v1/query/favorites', async ({ request }) => {
    const authHeader = request.headers.get('Authorization') ?? '';
    const isAdmin = isAdminFromToken(authHeader);

    const favorites = Array.from(favoritesStorage.values()).map(fav => {
      const historyItem = MOCK_HISTORY.find(h => h.request_id === fav.request_id);
      const response = historyItem ? getScenarioResponse(historyItem.question) : null;
      const filteredResponse = response ? filterResponseByRole(response, isAdmin) : null;
      return {
        ...fav,
        response: filteredResponse,
      };
    });
    return ok(favorites);
  }),

  // Add to favorites
  http.post('/api/v1/query/:requestId/favorite', async ({ params }) => {
    const requestId = String(params.requestId);
    const item = MOCK_HISTORY.find(h => h.request_id === requestId);
    const queryResult = queryResultStorage.get(requestId);
    if (!item && !queryResult) {
      return httpError(404, 'NOT_FOUND', '查询记录不存在');
    }

    const favorite: FavoriteItem = {
      request_id: requestId,
      question: item?.question ?? queryResult!.question,
      org_id: item?.org_id ?? queryResult!.org_id,
      org_name: item?.org_name ?? queryResult!.org_name,
      recommended_chart: item?.recommended_chart ?? queryResult!.recommended_chart,
      row_count: item?.row_count ?? queryResult!.row_count,
      favorited_at: nowInBeijing(),
      last_run_at: item?.created_at ?? queryResult!.last_run_at,
    };

    favoritesStorage.set(requestId, favorite);
    return ok(favorite);
  }),

  // Remove from favorites - correct path: DELETE /api/v1/query/:requestId/favorite
  http.delete('/api/v1/query/:requestId/favorite', async ({ params }) => {
    const requestId = String(params.requestId);
    favoritesStorage.delete(requestId);
    return ok(null);
  }),
];

// Helper to get scenario response by question keyword - 不得修改场景路由逻辑
function getScenarioResponse(question: string): QueryResponse {
  if (question.includes('趋势') || question.includes('折线')) {
    return SCENARIO_TREND_LINE;
  }
  if (question.includes('对比') || question.includes('比较')) {
    return SCENARIO_DEPT_COMPARE_BAR;
  }
  if (question.includes('排名')) {
    return SCENARIO_RANKING_PIE;
  }
  if (question.includes('明细') || question.includes('清单')) {
    return SCENARIO_TABLE_ONLY;
  }
  if (question.includes('警告') || question.includes('数据质量')) {
    return SCENARIO_WARNING;
  }
  return SCENARIO_MONTHLY_BAR;
}
