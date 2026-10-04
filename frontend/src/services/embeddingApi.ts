import { apiGet, apiPost } from './request';
import type { EmbeddingSearchResult, EmbeddingStats, EmbeddingSyncResult } from '@/types/user';

export const embeddingApi = {
  stats: (): Promise<EmbeddingStats> => apiGet('/v1/embeddings/stats'),
  sync: (): Promise<EmbeddingSyncResult> => apiPost('/v1/embeddings/sync'),
  testSearch: (query: string, sourceType: string, topK: number): Promise<EmbeddingSearchResult[]> =>
    apiPost('/v1/embeddings/search', { query, sourceType, topK }),
};
