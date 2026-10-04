export interface UserOrgGrant {
  id?: number;
  orgId: number;
  orgName: string;
  orgPath?: string;
  scope: 'SELF' | 'CHILDREN' | 'ALL';
  isPrimary: boolean;
  grantedBy?: string;
  grantedAt?: string;
}

export interface SysUser {
  id: number;
  username: string;
  displayName?: string;
  role: 'ADMIN' | 'MANAGER' | 'USER';
  email?: string;
  phone?: string;
  isActive: boolean;
  lastLoginAt?: string;
  orgGrants?: UserOrgGrant[];
}

export interface UserCreateRequest {
  username: string;
  password: string;
  displayName: string;
  role: 'ADMIN' | 'MANAGER' | 'USER';
  email?: string;
  phone?: string;
}

export interface UserUpdateRequest {
  displayName?: string;
  role?: 'ADMIN' | 'MANAGER' | 'USER';
  email?: string;
  phone?: string;
  isActive?: boolean;
}

export interface UserOrgGrantRequest {
  userId: number;
  orgId: number;
  scope: 'SELF' | 'CHILDREN' | 'ALL';
  isPrimary?: boolean;
}

export interface OrgUserVO {
  userId: number;
  username: string;
  displayName?: string;
  role: 'ADMIN' | 'MANAGER' | 'USER' | string;
  scope: 'SELF' | 'CHILDREN' | 'ALL';
  isPrimary: boolean;
  grantedAt?: string;
}

export interface OrgSyncResult {
  inserted: number;
  updated: number;
  deactivated: number;
  total: number;
}

export interface EmbeddingSyncResult {
  examples: number;
  glossary: number;
  tables: number;
}

export interface EmbeddingStats {
  sql_example: number;
  glossary: number;
  table_description: number;
  total: number;
}

export interface EmbeddingSearchResult {
  id: number;
  sourceType: string;
  sourceId: number;
  contentText: string;
  similarity?: number;
}
