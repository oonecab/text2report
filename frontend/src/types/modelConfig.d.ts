export interface LlmProvider {
  id: number;
  providerKey: string;
  displayName: string;
  providerMode: 'ONLINE' | 'LOCAL';
  vendorType: string;
  deploymentType: string;
  modelCode: string;
  baseUrl?: string;
  host?: string;
  port?: number;
  endpointPath?: string;
  authType: string;
  jsonMode: string;
  timeoutSeconds: number;
  temperature?: number;
  maxTokens?: number;
  enabled: boolean;
  isDefault: boolean;
  priority: number;
  configVersion?: number;
  secretMask?: string;
  lastTestStatus?: 'SUCCESS' | 'FAILED' | 'UNKNOWN';
  lastTestMessage?: string;
  lastTestedAt?: string;
  updatedAt?: string;
  updatedBy?: string;
}

export interface LlmProviderPayload {
  providerKey: string;
  displayName: string;
  providerMode: 'ONLINE' | 'LOCAL';
  vendorType: string;
  deploymentType?: string;
  modelCode: string;
  baseUrl?: string;
  host?: string;
  port?: number;
  endpointPath?: string;
  authType?: string;
  jsonMode?: string;
  timeoutSeconds?: number;
  temperature?: number;
  maxTokens?: number;
  enabled?: boolean;
  isDefault?: boolean;
  priority?: number;
  apiKey?: string;
}

export interface LlmRoutingPolicy {
  id: number;
  capabilityCode: 'SQL_GENERATION' | 'SQL_REPAIR' | 'RESULT_SUMMARY';
  providerId: number;
  providerKey?: string;
  providerName?: string;
  modelCode?: string;
  fallbackProviderId?: number;
  active: boolean;
  routingVersion: number;
  updatedAt?: string;
  updatedBy?: string;
}

export interface LlmAvailability {
  configured: boolean;
  message: string;
  providerId?: number;
  providerKey?: string;
  displayName?: string;
  modelCode?: string;
  vendorType?: string;
  routingVersion?: number;
  configVersion?: number;
}

export interface LlmConnectionTestResult {
  success: boolean;
  message: string;
  latencyMs?: number;
}
