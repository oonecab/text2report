import { apiGet, apiPost } from './request';
import type { LoginRequest, LoginResponse, UserInfo } from '@/types';

const normalizeUser = (user: Record<string, unknown>): UserInfo => ({
  id: (user.id as number | string) ?? (user.userId as number | string) ?? '',
  userId: (user.userId as number | string | undefined) ?? (user.id as number | string | undefined),
  username: String(user.username ?? ''),
  displayName: String(user.displayName ?? user.display_name ?? user.name ?? user.username ?? ''),
  role: (() => {
    const role = String(user.role ?? '').toUpperCase();
    if (role === 'ADMIN' || role === 'MANAGER' || role === 'USER') return role as UserInfo['role'];
    return String(user.role ?? '').toLowerCase() === 'admin' ? 'ADMIN' : 'USER';
  })(),
  orgId: (user.orgId as number | string | undefined) ?? (user.org_id as number | string | undefined),
  orgName: (user.orgName as string | undefined) ?? (user.org_name as string | undefined),
  orgGrants: Array.isArray(user.orgGrants)
    ? (user.orgGrants as Array<Record<string, unknown>>).map((grant) => ({
        orgId: Number(grant.orgId),
        orgName: String(grant.orgName ?? ''),
        scope: String(grant.scope ?? 'SELF') as 'SELF' | 'CHILDREN' | 'ALL',
        isPrimary: Boolean(grant.isPrimary),
      }))
    : [],
});

export const login = (data: LoginRequest): Promise<LoginResponse> => {
  return apiPost<Record<string, unknown>>('/v1/auth/login', data).then((response) => ({
    expiresIn: Number(response.expiresIn ?? 0),
    user: normalizeUser((response.user as Record<string, unknown>) ?? {}),
  }));
};

export const logout = (): Promise<void> => {
  return apiPost('/v1/auth/logout');
};

export const getCurrentUser = (options?: { silent?: boolean }): Promise<UserInfo> => {
  return apiGet<Record<string, unknown>>('/v1/auth/me', undefined, options).then(normalizeUser);
};

export const getCsrfToken = (options?: { silent?: boolean }): Promise<void> => {
  return apiGet('/v1/auth/csrf', undefined, options);
};
