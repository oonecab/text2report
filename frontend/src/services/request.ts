import axios, { type AxiosInstance, type AxiosError, type AxiosResponse } from 'axios';
import type { MessageInstance } from 'antd/es/message/interface';
import { v4 as uuidv4 } from 'uuid';
import type { ApiResponse } from '@/types';
import { useAuthStore } from '@/store/useAuthStore';
import { AppApiError, resolveApiErrorMessage } from '@/utils/apiError';

const BASE_URL = '/api';

declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean;
  }
}

const request: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 120000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
});

const isLoginRequest = (url?: string) => (url ?? '').endsWith('/v1/auth/login');
const isSessionBootstrapRequest = (url?: string) =>
  (url ?? '').endsWith('/v1/auth/me') || (url ?? '').endsWith('/v1/auth/csrf');

const buildApiError = (
  response: Pick<ApiResponse<unknown>, 'code' | 'message' | 'request_id'>,
  options?: { handled?: boolean; preferServerMessage?: boolean },
) => {
  const displayMessage = resolveApiErrorMessage(response.code, response.message, {
    preferServerMessage: options?.preferServerMessage,
  });
  return new AppApiError({
    code: response.code,
    displayMessage,
    requestId: response.request_id,
    serverMessage: response.message,
    handled: options?.handled ?? false,
  });
};

// 全局 message 实例引用（由 App 组件初始化时设置）
let messageInstance: MessageInstance | null = null;

/**
 * 设置全局 message 实例
 * 在 App 组件中调用，将 useApp().message 注入到这里
 */
export const setGlobalMessage = (msg: MessageInstance) => {
  messageInstance = msg;
};

/**
 * 安全的 message 调用（兼容静态和动态两种方式）
 */
const showError = (content: string) => {
  if (messageInstance) {
    messageInstance.error(content);
  } else {
    // 降级到静态 message（首次加载前可能用到）
    import('antd').then(({ message }) => {
      message.error(content);
    });
  }
};

// 请求拦截器
request.interceptors.request.use(
  (config) => {
    // 添加 requestId 用于链路追踪
    config.headers['X-Request-ID'] = uuidv4();
    return config;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  }
);

// 响应拦截器 - 返回 res.data（已解包）
request.interceptors.response.use(
  (response: AxiosResponse): AxiosResponse => {
    const res = response.data as ApiResponse<unknown> | undefined;

    // 如果响应不是标准格式，直接返回
    if (!res || typeof res !== 'object' || res.code === undefined) {
      return response;
    }

    const isSuccess = res.code === 'SUCCESS' || res.code === 0 || res.code === 200;
    if (!isSuccess) {
      const apiError = buildApiError(res, { handled: !response.config.silent });
      if (!response.config.silent) {
        showError(apiError.displayMessage);
      }
      throw apiError;
    }

    return {
      ...response,
      data: res.data,
    };
  },
  (error: AxiosError) => {
    const requestPath = error.config?.url;
    const silent = Boolean(error.config?.silent);
    const responseBody = error.response?.data as ApiResponse<unknown> | undefined;
    const hasApiCode = responseBody?.code !== undefined;
    const apiError = hasApiCode
      ? buildApiError(responseBody, {
          handled: !silent,
          preferServerMessage: isLoginRequest(requestPath),
        })
      : null;

    // HTTP 状态码 401 → 清除 store 内存状态 → 跳转登录
    if (error.response?.status === 401) {
      if (isLoginRequest(requestPath)) {
        if (apiError) {
          if (!silent) {
            showError(apiError.displayMessage);
          }
          return Promise.reject(apiError);
        }
        return Promise.reject(error);
      }

      if (isSessionBootstrapRequest(requestPath)) {
        useAuthStore.getState().clearAuth();
        return Promise.reject(apiError ?? error);
      }

      useAuthStore.getState().clearAuth();
      if (!silent && window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
      if (apiError) {
        if (!silent) {
          showError(apiError.displayMessage);
        }
        return Promise.reject(apiError);
      }
      return Promise.reject(error);
    }

    // 其他错误
    if (apiError) {
      if (!silent) {
        showError(apiError.displayMessage);
      }
      return Promise.reject(apiError);
    }

    const errMsg = error.message || '网络错误';
    if (!silent) {
      showError(errMsg);
    }
    return Promise.reject(error);
  }
);

export default request;

// Helper functions to properly type API calls with interceptor unwrapping
export const apiGet = async <T>(url: string, params?: object, options?: { silent?: boolean }): Promise<T> => {
  const response = await request.get(url, { params, silent: options?.silent });
  return response.data as T;
};

export const apiPost = async <T>(url: string, data?: unknown): Promise<T> => {
  const response = await request.post(url, data);
  return response.data as T;
};

export const apiPut = async <T>(url: string, data?: unknown): Promise<T> => {
  const response = await request.put(url, data);
  return response.data as T;
};

export const apiDelete = async <T>(url: string): Promise<T> => {
  const response = await request.delete(url);
  return response.data as T;
};

export const apiPatch = async <T>(url: string, data?: unknown): Promise<T> => {
  const response = await request.patch(url, data);
  return response.data as T;
};
