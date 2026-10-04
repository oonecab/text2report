import { http } from 'msw';
import { ok, okPage } from './utils';
import type { LlmProvider, LlmProviderPayload, LlmRoutingPolicy } from '@/types';

let providers: LlmProvider[] = [
  {
    id: 1,
    providerKey: 'deepseek-default',
    displayName: 'DeepSeek 默认',
    providerMode: 'ONLINE',
    vendorType: 'OPENAI_COMPATIBLE',
    deploymentType: 'SAAS',
    modelCode: 'deepseek-chat',
    baseUrl: 'https://api.deepseek.com/v1',
    endpointPath: '/chat/completions',
    authType: 'BEARER',
    jsonMode: 'RESPONSE_FORMAT',
    timeoutSeconds: 60,
    temperature: 0.1,
    maxTokens: 2000,
    enabled: true,
    isDefault: true,
    priority: 0,
    configVersion: 1,
    secretMask: 'sk-****demo',
    lastTestStatus: 'SUCCESS',
  },
];

let routingPolicies: LlmRoutingPolicy[] = ['SQL_GENERATION', 'SQL_REPAIR', 'RESULT_SUMMARY'].map((capabilityCode, index) => ({
  id: index + 1,
  capabilityCode: capabilityCode as LlmRoutingPolicy['capabilityCode'],
  providerId: 1,
  providerKey: 'deepseek-default',
  providerName: 'DeepSeek 默认',
  modelCode: 'deepseek-chat',
  active: true,
  routingVersion: 1,
}));

const toProvider = (payload: LlmProviderPayload, id: number): LlmProvider => ({
  id,
  providerKey: payload.providerKey,
  displayName: payload.displayName,
  providerMode: payload.providerMode,
  vendorType: payload.vendorType,
  deploymentType: payload.deploymentType || (payload.providerMode === 'ONLINE' ? 'SAAS' : 'LOCAL_GATEWAY'),
  modelCode: payload.modelCode,
  baseUrl: payload.baseUrl,
  host: payload.host,
  port: payload.port,
  endpointPath: payload.endpointPath || '/chat/completions',
  authType: payload.authType || (payload.apiKey ? 'BEARER' : 'NONE'),
  jsonMode: payload.jsonMode || 'RESPONSE_FORMAT',
  timeoutSeconds: payload.timeoutSeconds || 60,
  temperature: payload.temperature ?? 0.1,
  maxTokens: payload.maxTokens ?? 2000,
  enabled: payload.enabled ?? true,
  isDefault: payload.isDefault ?? false,
  priority: payload.priority ?? 0,
  configVersion: 1,
  secretMask: payload.apiKey ? 'sk-****mock' : undefined,
});

const hydrateRouting = (policy: LlmRoutingPolicy): LlmRoutingPolicy => {
  const provider = providers.find((item) => item.id === policy.providerId);
  return {
    ...policy,
    providerKey: provider?.providerKey,
    providerName: provider?.displayName,
    modelCode: provider?.modelCode,
  };
};

export const modelConfigHandlers = [
  http.get('/api/v1/llm/status', () => {
    const generation = routingPolicies.find((item) => item.capabilityCode === 'SQL_GENERATION' && item.active);
    const provider = generation ? providers.find((item) => item.id === generation.providerId && item.enabled) : null;
    return ok(provider ? {
      configured: true,
      message: '模型配置可用',
      providerId: provider.id,
      providerKey: provider.providerKey,
      displayName: provider.displayName,
      modelCode: provider.modelCode,
      vendorType: provider.vendorType,
      routingVersion: generation?.routingVersion,
      configVersion: provider.configVersion,
    } : {
      configured: false,
      message: '系统尚未配置可用模型，请联系管理员完成模型配置',
    });
  }),

  http.get('/api/v1/admin/llm/providers', ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') || 1);
    const pageSize = Number(url.searchParams.get('pageSize') || 20);
    const providerMode = url.searchParams.get('providerMode');
    const enabled = url.searchParams.get('enabled');
    const keyword = (url.searchParams.get('keyword') || '').trim().toLowerCase();
    const filtered = providers.filter((item) => {
      const matchesMode = !providerMode || item.providerMode === providerMode;
      const matchesEnabled = enabled == null || String(item.enabled) === enabled;
      const matchesKeyword = !keyword || [
        item.providerKey,
        item.displayName,
        item.modelCode,
        item.vendorType,
        item.baseUrl,
        item.host,
      ].some((value) => value?.toLowerCase().includes(keyword));
      return matchesMode && matchesEnabled && matchesKeyword;
    });
    return okPage(filtered, page, pageSize);
  }),

  http.get('/api/v1/admin/llm/providers/enabled', () => {
    return ok(providers.filter((item) => item.enabled));
  }),

  http.post('/api/v1/admin/llm/providers', async ({ request }) => {
    const payload = await request.json() as LlmProviderPayload;
    const provider = toProvider(payload, Math.max(0, ...providers.map((item) => item.id)) + 1);
    if (provider.isDefault) {
      providers = providers.map((item) => ({ ...item, isDefault: false }));
    }
    providers = [provider, ...providers];
    return ok(provider);
  }),

  http.put('/api/v1/admin/llm/providers/:id', async ({ request, params }) => {
    const id = Number(params.id);
    const payload = await request.json() as LlmProviderPayload;
    providers = providers.map((item) => item.id === id ? { ...item, ...toProvider(payload, id), configVersion: (item.configVersion || 1) + 1 } : item);
    return ok(providers.find((item) => item.id === id));
  }),

  http.post('/api/v1/admin/llm/providers/:id/enable', ({ params }) => {
    const id = Number(params.id);
    providers = providers.map((item) => item.id === id ? { ...item, enabled: true } : item);
    return ok(null);
  }),

  http.post('/api/v1/admin/llm/providers/:id/disable', ({ params }) => {
    const id = Number(params.id);
    providers = providers.map((item) => item.id === id ? { ...item, enabled: false, isDefault: false } : item);
    return ok(null);
  }),

  http.post('/api/v1/admin/llm/providers/:id/activate', ({ params }) => {
    const id = Number(params.id);
    providers = providers.map((item) => ({ ...item, isDefault: item.id === id }));
    routingPolicies = routingPolicies.map((item) => ({ ...item, providerId: id, routingVersion: item.routingVersion + 1 }));
    return ok(null);
  }),

  http.post('/api/v1/admin/llm/providers/:id/test', ({ params }) => {
    const id = Number(params.id);
    providers = providers.map((item) => item.id === id ? { ...item, lastTestStatus: 'SUCCESS' } : item);
    return ok({ success: true, message: '连接测试成功', latencyMs: 120 });
  }),

  http.get('/api/v1/admin/llm/routing-policies', () => {
    return ok(routingPolicies.map(hydrateRouting));
  }),

  http.put('/api/v1/admin/llm/routing-policies/:capability', async ({ request, params }) => {
    const body = await request.json() as { providerId: number };
    const capability = String(params.capability) as LlmRoutingPolicy['capabilityCode'];
    routingPolicies = routingPolicies.map((item) => item.capabilityCode === capability
      ? { ...item, providerId: body.providerId, active: true, routingVersion: item.routingVersion + 1 }
      : item);
    return ok(hydrateRouting(routingPolicies.find((item) => item.capabilityCode === capability)!));
  }),
];
