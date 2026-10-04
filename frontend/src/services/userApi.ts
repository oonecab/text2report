import { apiGet, apiPost, apiPut } from './request';
import type { PageData } from '@/types';
import type { SysUser, UserCreateRequest, UserOrgGrant, UserUpdateRequest } from '@/types/user';

export interface UserListParams {
  page?: number;
  size?: number;
  role?: SysUser['role'];
  isActive?: boolean;
  keyword?: string;
}

export const userApi = {
  list: (params?: UserListParams): Promise<PageData<SysUser>> => apiGet('/v1/users', params),
  getById: (id: number): Promise<SysUser> => apiGet(`/v1/users/${id}`),
  create: (data: UserCreateRequest): Promise<SysUser> => apiPost('/v1/users', data),
  update: (id: number, data: UserUpdateRequest): Promise<SysUser> => apiPut(`/v1/users/${id}`, data),
  updateStatus: (id: number, isActive: boolean): Promise<void> =>
    apiPut(`/v1/users/${id}/${isActive ? 'activate' : 'deactivate'}`),
  changePassword: (id: number, newPassword: string): Promise<void> =>
    apiPut(`/v1/users/${id}/password`, { newPassword }),
  getOrgGrants: (id: number): Promise<UserOrgGrant[]> => apiGet(`/v1/users/${id}/org-grants`),
};
