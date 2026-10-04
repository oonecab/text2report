import type { ApiCode } from '@/types';

const DEFAULT_ERROR_MESSAGE = '系统开小差了，请稍后重试';

const API_ERROR_MESSAGES: Record<string, string> = {
  '4000': '请求参数有误，请检查后重试',
  '4001': 'SQL 解析失败，请调整查询条件后重试',
  '4002': '查询已被安全策略拦截，请修改问题后重试',
  '4003': '当前查询超出组织权限范围，请切换组织或调整筛选条件',
  '4006': '提交的信息不符合要求，请修改后重试',
  '401': '登录状态已失效，请重新登录',
  '403': '当前账号没有执行该操作的权限',
  '404': '请求的资源不存在或已被删除',
  '429': '操作过于频繁，请稍后再试',
  '5001': '系统内部处理异常，请稍后重试',
  '5002': '数据源暂时不可用，请稍后重试',
  '5003': '元数据处理失败，请稍后重试',
  '5004': '外部服务调用失败，请稍后重试',
  '5005': '智能服务响应超时，请稍后重试',
  '5006': '智能服务返回结果无法解析，请稍后重试',
  '5007': '智能服务调用失败，请稍后重试',
};

interface ResolveMessageOptions {
  preferServerMessage?: boolean;
}

interface AppApiErrorParams {
  code: ApiCode;
  displayMessage: string;
  requestId?: string;
  serverMessage?: string;
  handled?: boolean;
}

export class AppApiError extends Error {
  code: ApiCode;
  displayMessage: string;
  requestId?: string;
  serverMessage?: string;
  handled: boolean;

  constructor(params: AppApiErrorParams) {
    super(params.displayMessage);
    this.name = 'AppApiError';
    this.code = params.code;
    this.displayMessage = params.displayMessage;
    this.requestId = params.requestId;
    this.serverMessage = params.serverMessage;
    this.handled = params.handled ?? false;
  }
}

const normalizeCode = (code: ApiCode | undefined): string => String(code ?? '');

const isFormValidationError = (error: unknown): boolean => (
  typeof error === 'object'
  && error !== null
  && 'errorFields' in error
  && Array.isArray((error as { errorFields?: unknown[] }).errorFields)
);

const shouldPreferServerMessage = (code: string): boolean => (
  code === '4000'
  || code === '4001'
  || code === '4002'
  || code === '4003'
  || code === '4006'
  || code === '404'
);

export const resolveApiErrorMessage = (
  code: ApiCode | undefined,
  serverMessage?: string,
  options: ResolveMessageOptions = {},
): string => {
  const normalizedCode = normalizeCode(code);
  const mappedMessage = API_ERROR_MESSAGES[normalizedCode] ?? DEFAULT_ERROR_MESSAGE;
  const trimmedServerMessage = serverMessage?.trim();

  if (!trimmedServerMessage) {
    return mappedMessage;
  }
  if (options.preferServerMessage || shouldPreferServerMessage(normalizedCode)) {
    return trimmedServerMessage;
  }
  return mappedMessage;
};

export const isAppApiError = (error: unknown): error is AppApiError => error instanceof AppApiError;

export const presentRequestError = (
  error: unknown,
  notify: (content: string) => void,
  fallbackMessage: string,
): void => {
  if (isFormValidationError(error)) {
    return;
  }
  if (isAppApiError(error) && error.handled) {
    return;
  }
  if (error instanceof Error && error.message) {
    notify(error.message);
    return;
  }
  notify(fallbackMessage);
};
