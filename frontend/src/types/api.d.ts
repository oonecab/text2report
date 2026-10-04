/** API 响应码 */
export type ApiCode = number | string;

/** 后端统一响应封装 */
export interface ApiResponse<T = unknown> {
  request_id?: string;
  code: ApiCode;
  message: string;
  data: T | null;
}

/** 分页数据 */
export interface PageData<T> {
  list: T[];
  total: number;
  page: number;
  pageSize: number;
}
