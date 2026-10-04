import { http } from 'msw';
import { ok, httpError } from './utils';
import { orgStorage } from '../data/orgData';
import type { OrgNode } from '@/types';
import type { OrgUserVO } from '@/types/user';
import { nowInBeijing } from '@/utils/formatters';

const orgUsersMap: Record<string, OrgUserVO[]> = {
  'org-root': [
    { userId: 1, username: 'admin', displayName: '系统管理员', role: 'ADMIN', scope: 'ALL', isPrimary: true, grantedAt: '2026-04-16T09:00:00' },
  ],
  'org-sub-1': [
    { userId: 2, username: 'manager', displayName: '业务经理', role: 'MANAGER', scope: 'CHILDREN', isPrimary: true, grantedAt: '2026-04-16T09:30:00' },
    { userId: 4, username: 'analyst', displayName: '数据分析员', role: 'USER', scope: 'SELF', isPrimary: false, grantedAt: '2026-04-16T09:40:00' },
  ],
  'org-dept-1': [
    { userId: 3, username: 'user', displayName: '普通用户', role: 'USER', scope: 'SELF', isPrimary: true, grantedAt: '2026-04-16T10:00:00' },
  ],
};

function findNode(nodes: OrgNode[], id: string): OrgNode | null {
  for (const node of nodes) {
    if (String(node.id) === id) return node;
    if (node.children) {
      const found = findNode(node.children, id);
      if (found) return found;
    }
  }
  return null;
}

export const orgHandlers = [
  http.get('/api/v1/orgs/tree', async () => ok(orgStorage)),

  http.get('/api/v1/orgs/my-tree', async () => ok(orgStorage)),

  http.get('/api/v1/orgs/:orgId/subtree-ids', async ({ params }) => {
    const node = findNode(orgStorage, String(params.orgId));
    if (!node) {
      return httpError(404, 'ORG_NOT_FOUND', '组织节点不存在');
    }
    return ok([Number(params.orgId)]);
  }),

  http.post('/api/v1/orgs/sync', async () =>
    ok({ inserted: 2, updated: 5, deactivated: 0, total: 16 })),

  http.get('/api/v1/orgs/sync/status', async () =>
    ok({ lastSyncAt: '2026-04-16T20:30:00' })),

  http.get('/api/v1/orgs/:orgId/users', async ({ params }) =>
    ok(orgUsersMap[String(params.orgId)] ?? [])),

  http.post('/api/v1/orgs/:orgId/users', async ({ params, request }) => {
    const orgId = String(params.orgId);
    const body = await request.json() as {
      userId: number;
      scope: 'SELF' | 'CHILDREN' | 'ALL';
      isPrimary?: boolean;
      username?: string;
      displayName?: string;
      role?: string;
    };

    const existing = orgUsersMap[orgId] ?? [];
    if (!existing.some((item) => item.userId === body.userId)) {
      existing.push({
        userId: body.userId,
        username: body.username ?? `user${body.userId}`,
        displayName: body.displayName ?? `用户${body.userId}`,
        role: body.role ?? 'USER',
        scope: body.scope,
        isPrimary: Boolean(body.isPrimary),
        grantedAt: nowInBeijing(),
      });
    }
    orgUsersMap[orgId] = existing;
    return ok(null);
  }),

  http.put('/api/v1/orgs/:orgId/users/:userId/scope', async ({ params, request }) => {
    const orgId = String(params.orgId);
    const userId = Number(params.userId);
    const body = await request.json() as { scope: 'SELF' | 'CHILDREN' | 'ALL'; isPrimary?: boolean };
    const target = (orgUsersMap[orgId] ?? []).find((item) => item.userId === userId);
    if (!target) {
      return httpError(404, 'USER_NOT_FOUND', '用户不存在');
    }
    target.scope = body.scope;
    target.isPrimary = Boolean(body.isPrimary);
    return ok(null);
  }),

  http.delete('/api/v1/orgs/:orgId/users/:userId', async ({ params }) => {
    const orgId = String(params.orgId);
    const userId = Number(params.userId);
    orgUsersMap[orgId] = (orgUsersMap[orgId] ?? []).filter((item) => item.userId !== userId);
    return ok(null);
  }),
];
