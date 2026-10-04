import { apiGet, apiPost, apiPut, apiDelete } from './request';
import type { DataSource } from '@/types';

type BackendDataSource = {
  id: number;
  name: string;
  dbType?: string;
  db_type?: string;
  jdbcUrl?: string;
  jdbc_url?: string;
  username: string;
  password?: string;
  description?: string;
  readOnly?: boolean;
  read_only?: boolean;
  isReadOnly?: boolean;
  is_read_only?: boolean;
  active?: boolean;
  isActive?: boolean;
  is_active?: boolean;
  createdAt?: string;
  created_at?: string;
  updatedAt?: string;
  updated_at?: string;
};

const toDataSource = (item: BackendDataSource): DataSource => ({
  id: item.id,
  name: item.name,
  dbType: (item.dbType ?? item.db_type ?? 'postgresql') as 'postgresql' | 'mysql' | 'sqlserver',
  jdbcUrl: item.jdbcUrl ?? item.jdbc_url,
  username: item.username,
  description: item.description,
  isReadOnly: Boolean(item.readOnly ?? item.read_only ?? item.isReadOnly ?? item.is_read_only ?? true),
  isActive: Boolean(item.active ?? item.isActive ?? item.is_active ?? true),
  createdAt: item.createdAt ?? item.created_at,
  updatedAt: item.updatedAt ?? item.updated_at,
});

const toBackendDataSource = (data: Partial<DataSource>): Partial<BackendDataSource> => {
  const result: Partial<BackendDataSource> = {
    name: data.name,
    dbType: data.dbType,
    jdbcUrl: data.jdbcUrl,
    username: data.username,
    description: data.description,
    active: data.isActive,
    readOnly: data.isReadOnly,
  };
  return result;
};

export const fetchDataSources = (activeOnly = false): Promise<DataSource[]> => {
  return apiGet<BackendDataSource[]>('/v1/datasources', { activeOnly }).then(
    (items) => items.map(toDataSource)
  );
};

export const fetchDataSourceById = (id: number): Promise<DataSource> => {
  return apiGet<BackendDataSource>(`/v1/datasources/${id}`).then(toDataSource);
};

export const createDataSource = (data: Partial<DataSource>): Promise<DataSource> => {
  return apiPost<BackendDataSource>('/v1/datasources', toBackendDataSource(data)).then(toDataSource);
};

export const updateDataSource = (id: number, data: Partial<DataSource>): Promise<DataSource> => {
  const payload = toBackendDataSource(data);
  if (!payload.password) {
    delete payload.password;
  }
  return apiPut<BackendDataSource>(`/v1/datasources/${id}`, payload).then(toDataSource);
};

export const deleteDataSource = (id: number): Promise<void> => {
  return apiDelete(`/v1/datasources/${id}`);
};
