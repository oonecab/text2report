import { HttpResponse, delay } from 'msw';

interface ApiResponse<T> {
  code: string;
  message: string;
  data: T | null;
}

export async function ok<T>(data: T, delayMs = 300): Promise<Response> {
  await delay(delayMs);
  return HttpResponse.json<ApiResponse<T>>({ code: 'SUCCESS', message: 'ok', data });
}

export async function okPage<T>(
  list: T[],
  page: number,
  pageSize: number,
  delayMs = 300
): Promise<Response> {
  await delay(delayMs);
  const start = (page - 1) * pageSize;
  return HttpResponse.json<ApiResponse<{ list: T[]; total: number; page: number; pageSize: number }>>({
    code: 'SUCCESS',
    message: 'ok',
    data: {
      list: list.slice(start, start + pageSize),
      total: list.length,
      page,
      pageSize,
    },
  });
}

export async function bizError<T = null>(
  code: string,
  message: string,
  delayMs = 200
): Promise<Response> {
  await delay(delayMs);
  return HttpResponse.json<ApiResponse<T>>({ code, message, data: null });
}

export async function httpError<T = null>(
  status: number,
  code: string,
  message: string
): Promise<Response> {
  await delay(200);
  return HttpResponse.json<ApiResponse<T>>({ code, message, data: null }, { status });
}

export function getTokenRole(request: Request): 'admin' | 'user' | null {
  const auth = request.headers.get('Authorization');
  if (!auth) return null;
  return auth.includes('admin') ? 'admin' : 'user';
}
