import { http } from 'msw';
import { ok } from './utils';

export const embeddingHandlers = [
  http.get('/api/v1/embeddings/stats', async () =>
    ok({ sql_example: 20, glossary: 15, table_description: 4, total: 39 })),

  http.post('/api/v1/embeddings/sync', async () =>
    ok({ examples: 20, glossary: 15, tables: 4 })),

  http.post('/api/v1/embeddings/search', async ({ request }) => {
    const body = await request.json() as { query?: string; sourceType?: string; topK?: number };
    const topK = Math.min(Math.max(body.topK ?? 5, 1), 10);
    return ok(
      Array.from({ length: topK }).map((_, index) => ({
        id: index + 1,
        sourceType: body.sourceType ?? 'sql_example',
        sourceId: index + 101,
        contentText: `${body.query ?? '测试问题'} 相关结果 ${index + 1}，用于检索验证展示`,
        similarity: Number((0.92 - index * 0.08).toFixed(4)),
      }))
    );
  }),
];
