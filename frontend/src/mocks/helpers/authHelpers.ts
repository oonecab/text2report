// Shared token-user mapping for MSW handlers
// All handlers must import from this file - no duplicate token definitions allowed

export interface MockUser {
  id: string;
  userId: number;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'MANAGER' | 'USER';
  orgGrants: Array<{
    orgId: number;
    orgName: string;
    scope: 'SELF' | 'CHILDREN' | 'ALL';
    isPrimary: boolean;
  }>;
}

export const TOKEN_USER_MAP: Record<string, MockUser> = {
  'mock-jwt-admin-token': {
    id: '1',
    userId: 1,
    username: 'admin',
    displayName: '系统管理员',
    role: 'ADMIN',
    orgGrants: [{ orgId: 1, orgName: '集团总部', scope: 'ALL', isPrimary: true }],
  },
  'mock-jwt-manager-token': {
    id: '2',
    userId: 2,
    username: 'manager',
    displayName: '业务经理',
    role: 'MANAGER',
    orgGrants: [{ orgId: 2, orgName: '华东大区', scope: 'CHILDREN', isPrimary: true }],
  },
  'mock-jwt-user-token': {
    id: '3',
    userId: 3,
    username: 'user',
    displayName: '普通用户',
    role: 'USER',
    orgGrants: [{ orgId: 3, orgName: '销售一部', scope: 'SELF', isPrimary: true }],
  },
};

export function getUserFromToken(authHeader: string | null | undefined): MockUser | null {
  if (!authHeader) return null;
  const token = authHeader.replace('Bearer ', '').trim();
  return TOKEN_USER_MAP[token] ?? null;
}

export function isAdminFromToken(authHeader: string | null | undefined): boolean {
  return getUserFromToken(authHeader)?.role === 'ADMIN';
}
