/** 用户信息 */
export interface UserInfo {
  id: number | string;
  userId?: number | string;
  username: string;
  displayName?: string;
  role: 'ADMIN' | 'MANAGER' | 'USER' | 'admin' | 'user';
  orgId?: number | string;
  orgName?: string;
  orgGrants?: Array<{
    orgId: number;
    orgName: string;
    scope: 'SELF' | 'CHILDREN' | 'ALL';
    isPrimary: boolean;
  }>;
}

/** 登录请求 */
export interface LoginRequest {
  username: string;
  password: string;
}

/** 登录响应 */
export interface LoginResponse {
  expiresIn: number;
  user: UserInfo;
}
