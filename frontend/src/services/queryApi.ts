import { apiGet, apiPost, apiDelete } from './request';
import type { QueryRequest, QueryResponse, QueryHistory, FavoriteItem, PageData } from '@/types';

export const submitQuery = async (data: QueryRequest): Promise<QueryResponse> => {
  return apiPost<QueryResponse>('/v1/query', data);
};

export const fetchHistory = async (params?: {
  page?: number;
  current?: number;
  pageSize?: number;
  keyword?: string;
  username?: string;
  orgId?: string | number;
  startDate?: string;
  endDate?: string;
  minConfidence?: number;
  maxConfidence?: number;
}): Promise<PageData<QueryHistory>> => {
  const response = await apiGet<PageData<QueryHistory>>('/v1/query/history', {
    page: params?.page ?? params?.current ?? 1,
    pageSize: params?.pageSize,
    keyword: params?.keyword,
    username: params?.username,
    orgId: params?.orgId,
    startDate: params?.startDate,
    endDate: params?.endDate,
    minConfidence: params?.minConfidence,
    maxConfidence: params?.maxConfidence,
  });
  return {
    list: response.list ?? [],
    total: response.total ?? 0,
    page: response.page ?? params?.page ?? params?.current ?? 1,
    pageSize: response.pageSize ?? params?.pageSize ?? 20,
  };
};

export const submitFeedback = (
  requestId: string,
  feedback: 'good' | 'bad'
): Promise<void> => {
  return apiPost(`/v1/query/${requestId}/feedback`, { feedback }).then(() => undefined);
};

export const fetchFavorites = async (): Promise<FavoriteItem[]> => {
  return apiGet<FavoriteItem[]>('/v1/query/favorites');
};

export const addToFavorites = (requestId: string): Promise<void> => {
  return apiPost(`/v1/query/${requestId}/favorite`, {}).then(() => undefined);
};

export const removeFromFavorites = (requestId: string): Promise<void> => {
  return apiDelete(`/v1/query/${requestId}/favorite`);
};
