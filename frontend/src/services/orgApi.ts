import { apiDelete, apiGet, apiPost, apiPut } from './request';
import type { OrgNode } from '@/types';
import type { OrgSyncResult, OrgUserVO, UserOrgGrantRequest } from '@/types/user';

export const orgApi = {
  getTree: (): Promise<OrgNode[]> => apiGet('/v1/orgs/tree'),
  getMyTree: (): Promise<OrgNode[]> => apiGet('/v1/orgs/my-tree'),
  getSubtreeIds: (orgId: string | number): Promise<number[]> => apiGet(`/v1/orgs/${orgId}/subtree-ids`),
  sync: (): Promise<OrgSyncResult> => apiPost('/v1/orgs/sync'),
  getSyncStatus: (): Promise<{ lastSyncAt?: string | null }> => apiGet('/v1/orgs/sync/status'),
  getUsersByOrg: (orgId: string | number): Promise<OrgUserVO[]> => apiGet(`/v1/orgs/${orgId}/users`),
  addUserToOrg: (orgId: string | number, data: UserOrgGrantRequest): Promise<void> => apiPost(`/v1/orgs/${orgId}/users`, data),
  updateUserScope: (orgId: string | number, userId: string | number, scope: string, isPrimary?: boolean): Promise<void> =>
    apiPut(`/v1/orgs/${orgId}/users/${userId}/scope`, { scope, isPrimary }),
  removeUserFromOrg: (orgId: string | number, userId: string | number): Promise<void> =>
    apiDelete(`/v1/orgs/${orgId}/users/${userId}`),
};

// Backward-compatible exports used by existing pages/stores.
export const fetchOrgTree = orgApi.getTree;
export const fetchMyOrgTree = orgApi.getMyTree;
export const fetchOrgChildren = orgApi.getSubtreeIds;
