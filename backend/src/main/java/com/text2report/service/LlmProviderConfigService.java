package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.MetaLlmConfigAuditLog;
import com.text2report.domain.meta.entity.MetaLlmProvider;
import com.text2report.domain.meta.entity.MetaLlmProviderSecret;
import com.text2report.domain.meta.entity.MetaLlmRoutingPolicy;
import com.text2report.domain.meta.mapper.MetaLlmConfigAuditLogMapper;
import com.text2report.domain.meta.mapper.MetaLlmProviderMapper;
import com.text2report.domain.meta.mapper.MetaLlmProviderSecretMapper;
import com.text2report.domain.meta.mapper.MetaLlmRoutingPolicyMapper;
import com.text2report.model.dto.LlmAvailabilityVO;
import com.text2report.model.dto.LlmConnectionTestResult;
import com.text2report.model.dto.LlmProviderRequest;
import com.text2report.model.dto.LlmProviderVO;
import com.text2report.model.dto.LlmRoutingPolicyRequest;
import com.text2report.model.dto.LlmRoutingPolicyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderConfigService {

    private static final List<String> ROUTED_CAPABILITIES = List.of("SQL_GENERATION", "SQL_REPAIR", "RESULT_SUMMARY");

    private final MetaLlmProviderMapper providerMapper;
    private final MetaLlmProviderSecretMapper secretMapper;
    private final MetaLlmRoutingPolicyMapper routingPolicyMapper;
    private final MetaLlmConfigAuditLogMapper auditLogMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public PageResult<LlmProviderVO> listProviders(int page, int pageSize, String providerMode, Boolean enabled, String keyword) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        QueryWrapper<MetaLlmProvider> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(providerMode)) {
            wrapper.eq("provider_mode", providerMode);
        }
        if (enabled != null) {
            wrapper.eq("enabled", enabled);
        }
        if (StringUtils.hasText(keyword)) {
            String normalizedKeyword = keyword.trim();
            wrapper.and(w -> w.like("provider_key", normalizedKeyword)
                    .or()
                    .like("display_name", normalizedKeyword)
                    .or()
                    .like("model_code", normalizedKeyword)
                    .or()
                    .like("vendor_type", normalizedKeyword)
                    .or()
                    .like("base_url", normalizedKeyword)
                    .or()
                    .like("host", normalizedKeyword));
        }
        long total = providerMapper.selectCount(wrapper);
        wrapper.orderByDesc("is_default").orderByDesc("updated_at").last("LIMIT " + safePageSize + " OFFSET " + ((safePage - 1) * safePageSize));
        List<LlmProviderVO> list = providerMapper.selectList(wrapper).stream().map(this::toVO).toList();
        return PageResult.of(list, total, safePage, safePageSize);
    }

    public List<LlmProviderVO> listEnabledProviders() {
        return providerMapper.selectList(new QueryWrapper<MetaLlmProvider>()
                        .eq("enabled", true)
                        .orderByDesc("is_default")
                        .orderByAsc("display_name"))
                .stream()
                .map(this::toVO)
                .toList();
    }

    public LlmProviderVO getProvider(Integer id) {
        MetaLlmProvider provider = requireProvider(id);
        return toVO(provider);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public LlmProviderVO createProvider(LlmProviderRequest request) {
        validateProviderRequest(request);
        if (providerMapper.selectCount(new QueryWrapper<MetaLlmProvider>().eq("provider_key", request.getProviderKey())) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "providerKey 已存在");
        }

        LocalDateTime now = LocalDateTime.now();
        MetaLlmProvider provider = new MetaLlmProvider();
        applyRequest(provider, request);
        provider.setConfigVersion(1);
        provider.setCreatedAt(now);
        provider.setUpdatedAt(now);
        provider.setCreatedBy(currentUser());
        provider.setUpdatedBy(currentUser());
        providerMapper.insert(provider);
        saveSecret(provider.getId(), request.getApiKey(), now);

        if (Boolean.TRUE.equals(provider.getIsDefault())) {
            activateProvider(provider.getId());
        } else {
            audit("LLM_PROVIDER_CREATE", provider.getId(), "SUCCESS");
        }
        return toVO(requireProvider(provider.getId()));
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public LlmProviderVO updateProvider(Integer id, LlmProviderRequest request) {
        validateProviderRequest(request);
        MetaLlmProvider provider = requireProvider(id);
        if (providerMapper.selectCount(new QueryWrapper<MetaLlmProvider>()
                .eq("provider_key", request.getProviderKey())
                .ne("id", id)) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "providerKey 已存在");
        }

        applyRequest(provider, request);
        provider.setConfigVersion(provider.getConfigVersion() == null ? 1 : provider.getConfigVersion() + 1);
        provider.setUpdatedAt(LocalDateTime.now());
        provider.setUpdatedBy(currentUser());
        providerMapper.updateById(provider);
        saveSecret(provider.getId(), request.getApiKey(), LocalDateTime.now());

        if (Boolean.TRUE.equals(provider.getIsDefault())) {
            activateProvider(provider.getId());
        } else {
            audit("LLM_PROVIDER_UPDATE", provider.getId(), "SUCCESS");
        }
        return toVO(requireProvider(id));
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void setEnabled(Integer id, boolean enabled) {
        MetaLlmProvider provider = requireProvider(id);
        provider.setEnabled(enabled);
        provider.setConfigVersion(provider.getConfigVersion() == null ? 1 : provider.getConfigVersion() + 1);
        provider.setUpdatedAt(LocalDateTime.now());
        provider.setUpdatedBy(currentUser());
        providerMapper.updateById(provider);
        audit(enabled ? "LLM_PROVIDER_ENABLE" : "LLM_PROVIDER_DISABLE", id, "SUCCESS");
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void activateProvider(Integer id) {
        MetaLlmProvider provider = requireProvider(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "停用模型不能激活");
        }

        List<MetaLlmProvider> defaults = providerMapper.selectList(new QueryWrapper<MetaLlmProvider>().eq("is_default", true));
        for (MetaLlmProvider existing : defaults) {
            if (!existing.getId().equals(id)) {
                existing.setIsDefault(false);
                existing.setUpdatedAt(LocalDateTime.now());
                existing.setUpdatedBy(currentUser());
                providerMapper.updateById(existing);
            }
        }
        provider.setIsDefault(true);
        provider.setConfigVersion(provider.getConfigVersion() == null ? 1 : provider.getConfigVersion() + 1);
        provider.setUpdatedAt(LocalDateTime.now());
        provider.setUpdatedBy(currentUser());
        providerMapper.updateById(provider);

        for (String capability : ROUTED_CAPABILITIES) {
            upsertRouting(capability, id, null, true);
        }
        audit("LLM_PROVIDER_ACTIVATE", id, "SUCCESS");
    }

    public List<LlmRoutingPolicyVO> listRoutingPolicies() {
        return routingPolicyMapper.selectList(new QueryWrapper<MetaLlmRoutingPolicy>().orderByAsc("capability_code"))
                .stream()
                .map(this::toRoutingVO)
                .toList();
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public LlmRoutingPolicyVO updateRoutingPolicy(String capability, LlmRoutingPolicyRequest request) {
        if (!ROUTED_CAPABILITIES.contains(capability)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的模型能力");
        }
        requireEnabledProvider(request.getProviderId());
        if (request.getFallbackProviderId() != null) {
            requireEnabledProvider(request.getFallbackProviderId());
        }
        MetaLlmRoutingPolicy policy = upsertRouting(
                capability,
                request.getProviderId(),
                request.getFallbackProviderId(),
                request.getActive() == null || request.getActive()
        );
        audit("LLM_ROUTING_UPDATE", request.getProviderId(), "SUCCESS");
        return toRoutingVO(policy);
    }

    public LlmAvailabilityVO getAvailability() {
        MetaLlmRoutingPolicy policy = routingPolicyMapper.selectOne(new QueryWrapper<MetaLlmRoutingPolicy>()
                .eq("capability_code", "SQL_GENERATION")
                .eq("active", true)
                .last("LIMIT 1"));
        if (policy == null) {
            return unavailable("系统尚未配置可用模型，请联系管理员完成模型配置");
        }
        MetaLlmProvider provider = providerMapper.selectById(policy.getProviderId());
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            return unavailable("系统尚未配置可用模型，请联系管理员完成模型配置");
        }
        return LlmAvailabilityVO.builder()
                .configured(true)
                .message("模型配置可用")
                .providerId(provider.getId())
                .providerKey(provider.getProviderKey())
                .displayName(provider.getDisplayName())
                .modelCode(provider.getModelCode())
                .vendorType(provider.getVendorType())
                .routingVersion(policy.getRoutingVersion())
                .configVersion(provider.getConfigVersion())
                .build();
    }

    public String resolveCacheSignature() {
        LlmAvailabilityVO availability = getAvailability();
        if (!Boolean.TRUE.equals(availability.getConfigured())) {
            throw new BusinessException(ErrorCode.LLM_ERROR, availability.getMessage());
        }
        return availability.getProviderKey() + ":" + availability.getModelCode()
                + ":p" + availability.getConfigVersion()
                + ":r" + availability.getRoutingVersion();
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public LlmConnectionTestResult testProvider(Integer id) {
        MetaLlmProvider provider = requireProvider(id);
        long start = System.nanoTime();
        try {
            if (!"OPENAI_COMPATIBLE".equals(provider.getVendorType())
                    && !"OLLAMA".equals(provider.getVendorType())
                    && !"LOCAL_GATEWAY".equals(provider.getVendorType())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "当前版本仅支持 OpenAI 兼容协议连接测试");
            }

            String secret = readSecret(provider.getId());
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", provider.getModelCode(),
                    "messages", new Object[]{
                            Map.of("role", "system", "content", "Return JSON only."),
                            Map.of("role", "user", "content", "请返回 {\"ok\":true}")
                    },
                    "temperature", 0,
                    "max_tokens", 32,
                    "response_format", Map.of("type", "json_object")
            ));
            okhttp3.RequestBody body = okhttp3.RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8"));
            Request.Builder builder = new Request.Builder()
                    .url(buildApiUrl(provider))
                    .addHeader("Content-Type", "application/json")
                    .post(body);
            if (StringUtils.hasText(secret) && !"NONE".equals(provider.getAuthType())) {
                builder.addHeader("Authorization", "Bearer " + secret);
            }
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(provider.getTimeoutSeconds()))
                    .readTimeout(Duration.ofSeconds(provider.getTimeoutSeconds()))
                    .writeTimeout(Duration.ofSeconds(provider.getTimeoutSeconds()))
                    .build();
            try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new BusinessException(ErrorCode.LLM_ERROR, "HTTP " + response.code() + " " + response.message());
                }
            }
            long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            updateTestResult(provider, "SUCCESS", "连接测试成功");
            audit("LLM_PROVIDER_TEST", id, "SUCCESS");
            return LlmConnectionTestResult.builder().success(true).message("连接测试成功").latencyMs(latencyMs).build();
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "连接测试失败";
            updateTestResult(provider, "FAILED", message);
            audit("LLM_PROVIDER_TEST", id, "FAILED");
            return LlmConnectionTestResult.builder()
                    .success(false)
                    .message(message)
                    .latencyMs(Duration.ofNanos(System.nanoTime() - start).toMillis())
                    .build();
        }
    }

    private void validateProviderRequest(LlmProviderRequest request) {
        String mode = request.getProviderMode();
        if (!"ONLINE".equals(mode) && !"LOCAL".equals(mode)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "providerMode 仅支持 ONLINE/LOCAL");
        }
        if ("ONLINE".equals(mode) && !StringUtils.hasText(request.getBaseUrl())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "在线模型必须配置 baseUrl");
        }
        if ("LOCAL".equals(mode) && !StringUtils.hasText(request.getBaseUrl()) && !StringUtils.hasText(request.getHost())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "本地模型必须配置 baseUrl 或 host");
        }
    }

    private void applyRequest(MetaLlmProvider provider, LlmProviderRequest request) {
        provider.setProviderKey(request.getProviderKey());
        provider.setDisplayName(request.getDisplayName());
        provider.setProviderMode(request.getProviderMode());
        provider.setVendorType(request.getVendorType());
        provider.setDeploymentType(defaultString(request.getDeploymentType(), "ONLINE".equals(request.getProviderMode()) ? "SAAS" : "LOCAL_GATEWAY"));
        provider.setModelCode(request.getModelCode());
        provider.setBaseUrl(request.getBaseUrl());
        provider.setHost(request.getHost());
        provider.setPort(request.getPort());
        provider.setEndpointPath(defaultString(request.getEndpointPath(), "/chat/completions"));
        provider.setAuthType(defaultString(request.getAuthType(), StringUtils.hasText(request.getApiKey()) ? "BEARER" : "NONE"));
        provider.setJsonMode(defaultString(request.getJsonMode(), "RESPONSE_FORMAT"));
        provider.setTimeoutSeconds(request.getTimeoutSeconds() == null ? 60 : request.getTimeoutSeconds());
        provider.setTemperature(request.getTemperature() == null ? new BigDecimal("0.10") : request.getTemperature());
        provider.setMaxTokens(request.getMaxTokens() == null ? 2000 : request.getMaxTokens());
        provider.setEnabled(request.getEnabled() == null || request.getEnabled());
        provider.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()));
        provider.setPriority(request.getPriority() == null ? 0 : request.getPriority());
    }

    private MetaLlmRoutingPolicy upsertRouting(String capability, Integer providerId, Integer fallbackProviderId, boolean active) {
        MetaLlmRoutingPolicy policy = routingPolicyMapper.selectOne(new QueryWrapper<MetaLlmRoutingPolicy>()
                .eq("capability_code", capability)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (policy == null) {
            policy = new MetaLlmRoutingPolicy();
            policy.setCapabilityCode(capability);
            policy.setProviderId(providerId);
            policy.setFallbackProviderId(fallbackProviderId);
            policy.setActive(active);
            policy.setRoutingVersion(1);
            policy.setCreatedAt(now);
            policy.setUpdatedAt(now);
            policy.setCreatedBy(currentUser());
            policy.setUpdatedBy(currentUser());
            routingPolicyMapper.insert(policy);
            return policy;
        }
        policy.setProviderId(providerId);
        policy.setFallbackProviderId(fallbackProviderId);
        policy.setActive(active);
        policy.setRoutingVersion(policy.getRoutingVersion() == null ? 1 : policy.getRoutingVersion() + 1);
        policy.setUpdatedAt(now);
        policy.setUpdatedBy(currentUser());
        routingPolicyMapper.updateById(policy);
        return policy;
    }

    private LlmProviderVO toVO(MetaLlmProvider provider) {
        MetaLlmProviderSecret secret = secretMapper.selectOne(new QueryWrapper<MetaLlmProviderSecret>()
                .eq("provider_id", provider.getId())
                .eq("secret_type", "API_KEY")
                .last("LIMIT 1"));
        return LlmProviderVO.builder()
                .id(provider.getId())
                .providerKey(provider.getProviderKey())
                .displayName(provider.getDisplayName())
                .providerMode(provider.getProviderMode())
                .vendorType(provider.getVendorType())
                .deploymentType(provider.getDeploymentType())
                .modelCode(provider.getModelCode())
                .baseUrl(provider.getBaseUrl())
                .host(provider.getHost())
                .port(provider.getPort())
                .endpointPath(provider.getEndpointPath())
                .authType(provider.getAuthType())
                .jsonMode(provider.getJsonMode())
                .timeoutSeconds(provider.getTimeoutSeconds())
                .temperature(provider.getTemperature())
                .maxTokens(provider.getMaxTokens())
                .enabled(provider.getEnabled())
                .isDefault(provider.getIsDefault())
                .priority(provider.getPriority())
                .configVersion(provider.getConfigVersion())
                .secretMask(secret != null ? secret.getSecretMask() : null)
                .lastTestStatus(provider.getLastTestStatus())
                .lastTestMessage(provider.getLastTestMessage())
                .lastTestedAt(provider.getLastTestedAt())
                .createdAt(provider.getCreatedAt())
                .createdBy(provider.getCreatedBy())
                .updatedAt(provider.getUpdatedAt())
                .updatedBy(provider.getUpdatedBy())
                .build();
    }

    private LlmRoutingPolicyVO toRoutingVO(MetaLlmRoutingPolicy policy) {
        MetaLlmProvider provider = providerMapper.selectById(policy.getProviderId());
        return LlmRoutingPolicyVO.builder()
                .id(policy.getId())
                .capabilityCode(policy.getCapabilityCode())
                .providerId(policy.getProviderId())
                .providerKey(provider != null ? provider.getProviderKey() : null)
                .providerName(provider != null ? provider.getDisplayName() : null)
                .modelCode(provider != null ? provider.getModelCode() : null)
                .fallbackProviderId(policy.getFallbackProviderId())
                .active(policy.getActive())
                .routingVersion(policy.getRoutingVersion())
                .updatedAt(policy.getUpdatedAt())
                .updatedBy(policy.getUpdatedBy())
                .build();
    }

    private LlmAvailabilityVO unavailable(String message) {
        return LlmAvailabilityVO.builder().configured(false).message(message).build();
    }

    private MetaLlmProvider requireProvider(Integer id) {
        MetaLlmProvider provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
        return provider;
    }

    private void requireEnabledProvider(Integer id) {
        MetaLlmProvider provider = requireProvider(id);
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "路由只能绑定启用中的模型");
        }
    }

    private void saveSecret(Integer providerId, String plainSecret, LocalDateTime now) {
        if (!StringUtils.hasText(plainSecret)) {
            return;
        }
        MetaLlmProviderSecret secret = secretMapper.selectOne(new QueryWrapper<MetaLlmProviderSecret>()
                .eq("provider_id", providerId)
                .eq("secret_type", "API_KEY")
                .last("LIMIT 1"));
        if (secret == null) {
            secret = new MetaLlmProviderSecret();
            secret.setProviderId(providerId);
            secret.setSecretType("API_KEY");
            secret.setCreatedAt(now);
            secret.setCreatedBy(currentUser());
        }
        secret.setSecretCiphertext(Base64.getEncoder().encodeToString(plainSecret.getBytes(StandardCharsets.UTF_8)));
        secret.setSecretMask(maskSecret(plainSecret));
        secret.setEncryptionKeyVersion("base64-v1");
        secret.setUpdatedAt(now);
        secret.setUpdatedBy(currentUser());
        if (secret.getId() == null) {
            secretMapper.insert(secret);
        } else {
            secretMapper.updateById(secret);
        }
    }

    private String readSecret(Integer providerId) {
        MetaLlmProviderSecret secret = secretMapper.selectOne(new QueryWrapper<MetaLlmProviderSecret>()
                .eq("provider_id", providerId)
                .eq("secret_type", "API_KEY")
                .last("LIMIT 1"));
        if (secret == null || !StringUtils.hasText(secret.getSecretCiphertext())) {
            return null;
        }
        return new String(Base64.getDecoder().decode(secret.getSecretCiphertext()), StandardCharsets.UTF_8);
    }

    private void updateTestResult(MetaLlmProvider provider, String status, String message) {
        provider.setLastTestStatus(status);
        provider.setLastTestMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        provider.setLastTestedAt(LocalDateTime.now());
        provider.setUpdatedAt(LocalDateTime.now());
        providerMapper.updateById(provider);
    }

    private String buildApiUrl(MetaLlmProvider provider) {
        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? provider.getBaseUrl()
                : provider.getHost() + (provider.getPort() == null ? "" : ":" + provider.getPort());
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String endpoint = StringUtils.hasText(provider.getEndpointPath()) ? provider.getEndpointPath() : "/chat/completions";
        String cleanPath = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return cleanBase + cleanPath;
    }

    private String maskSecret(String secret) {
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, Math.min(4, secret.length())) + "****" + secret.substring(secret.length() - 4);
    }

    private void audit(String operationType, Integer providerId, String status) {
        try {
            MetaLlmConfigAuditLog audit = new MetaLlmConfigAuditLog();
            audit.setRequestId(resolveAuditRequestId());
            audit.setUserId(TraceContext.getUserId());
            audit.setProviderId(providerId);
            audit.setOperationType(operationType);
            audit.setTargetType("LLM_PROVIDER");
            audit.setTargetId(providerId == null ? null : String.valueOf(providerId));
            audit.setStatus(status);
            audit.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            log.warn("Failed to write LLM config audit log: {}", e.getMessage());
        }
    }

    private String currentUser() {
        Integer userId = TraceContext.getUserId();
        return userId == null ? "system" : String.valueOf(userId);
    }

    private UUID resolveAuditRequestId() {
        String requestId = TraceContext.getRequestId();
        if (StringUtils.hasText(requestId)) {
            try {
                return UUID.fromString(requestId);
            } catch (IllegalArgumentException ignored) {
                log.debug("Trace requestId is not a UUID, generating audit UUID: {}", requestId);
            }
        }
        return UUID.randomUUID();
    }

    private String defaultString(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
