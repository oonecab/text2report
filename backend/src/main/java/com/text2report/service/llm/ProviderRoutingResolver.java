package com.text2report.service.llm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaLlmProvider;
import com.text2report.domain.meta.entity.MetaLlmProviderSecret;
import com.text2report.domain.meta.entity.MetaLlmRoutingPolicy;
import com.text2report.domain.meta.mapper.MetaLlmProviderMapper;
import com.text2report.domain.meta.mapper.MetaLlmProviderSecretMapper;
import com.text2report.domain.meta.mapper.MetaLlmRoutingPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Resolves the currently active provider for each LLM capability.
 */
@Service
@RequiredArgsConstructor
public class ProviderRoutingResolver {

    private static final ThreadLocal<ActiveProviderContext> LAST_RESOLVED = new ThreadLocal<>();

    private final MetaLlmProviderMapper providerMapper;
    private final MetaLlmProviderSecretMapper secretMapper;
    private final MetaLlmRoutingPolicyMapper routingPolicyMapper;

    public ActiveProviderContext resolve(LlmCapability capability) {
        MetaLlmRoutingPolicy policy = routingPolicyMapper.selectOne(
                new QueryWrapper<MetaLlmRoutingPolicy>()
                        .eq("capability_code", capability.name())
                        .eq("active", true)
                        .last("LIMIT 1")
        );
        if (policy == null) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "系统尚未配置可用模型，请联系管理员完成模型配置");
        }

        MetaLlmProvider provider = providerMapper.selectById(policy.getProviderId());
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "系统尚未配置可用模型，请联系管理员完成模型配置");
        }

        ActiveProviderContext context = ActiveProviderContext.builder()
                .capability(capability)
                .routingPolicy(policy)
                .provider(provider)
                .secret(resolveSecret(provider.getId()))
                .build();
        LAST_RESOLVED.set(context);
        return context;
    }

    public ActiveProviderContext getLastResolved() {
        return LAST_RESOLVED.get();
    }

    public void clearLastResolved() {
        LAST_RESOLVED.remove();
    }

    public String resolveCacheSignature() {
        return resolve(LlmCapability.SQL_GENERATION).cacheSignature();
    }

    private String resolveSecret(Integer providerId) {
        MetaLlmProviderSecret secret = secretMapper.selectOne(
                new QueryWrapper<MetaLlmProviderSecret>()
                        .eq("provider_id", providerId)
                        .eq("secret_type", "API_KEY")
                        .last("LIMIT 1")
        );
        if (secret == null || !StringUtils.hasText(secret.getSecretCiphertext())) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(secret.getSecretCiphertext()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return secret.getSecretCiphertext();
        }
    }
}
