import { apiGet, apiPost, apiPut } from './request';
import type {
  LlmAvailability,
  LlmConnectionTestResult,
  LlmProvider,
  LlmProviderPayload,
  LlmRoutingPolicy,
  PageData,
} from '@/types';

export const fetchLlmStatus = (): Promise<LlmAvailability> => {
  return apiGet<LlmAvailability>('/v1/llm/status');
};

export const fetchLlmProviders = (params?: {
  page?: number;
  pageSize?: number;
  providerMode?: string;
  enabled?: boolean;
  keyword?: string;
}): Promise<PageData<LlmProvider>> => {
  return apiGet<PageData<LlmProvider>>('/v1/admin/llm/providers', params);
};

export const fetchEnabledLlmProviders = (): Promise<LlmProvider[]> => {
  return apiGet<LlmProvider[]>('/v1/admin/llm/providers/enabled');
};

export const createLlmProvider = (payload: LlmProviderPayload): Promise<LlmProvider> => {
  return apiPost<LlmProvider>('/v1/admin/llm/providers', payload);
};

export const updateLlmProvider = (id: number, payload: LlmProviderPayload): Promise<LlmProvider> => {
  return apiPut<LlmProvider>(`/v1/admin/llm/providers/${id}`, payload);
};

export const enableLlmProvider = (id: number): Promise<void> => {
  return apiPost(`/v1/admin/llm/providers/${id}/enable`, {}).then(() => undefined);
};

export const disableLlmProvider = (id: number): Promise<void> => {
  return apiPost(`/v1/admin/llm/providers/${id}/disable`, {}).then(() => undefined);
};

export const activateLlmProvider = (id: number): Promise<void> => {
  return apiPost(`/v1/admin/llm/providers/${id}/activate`, {}).then(() => undefined);
};

export const testLlmProvider = (id: number): Promise<LlmConnectionTestResult> => {
  return apiPost<LlmConnectionTestResult>(`/v1/admin/llm/providers/${id}/test`, {});
};

export const fetchLlmRoutingPolicies = (): Promise<LlmRoutingPolicy[]> => {
  return apiGet<LlmRoutingPolicy[]>('/v1/admin/llm/routing-policies');
};

export const updateLlmRoutingPolicy = (
  capability: LlmRoutingPolicy['capabilityCode'],
  providerId: number,
): Promise<LlmRoutingPolicy> => {
  return apiPut<LlmRoutingPolicy>(`/v1/admin/llm/routing-policies/${capability}`, {
    providerId,
    active: true,
  });
};
