package com.text2report.service.llm;

import com.text2report.domain.meta.entity.MetaLlmProvider;
import com.text2report.domain.meta.entity.MetaLlmRoutingPolicy;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ActiveProviderContext {
    private LlmCapability capability;
    private MetaLlmProvider provider;
    private MetaLlmRoutingPolicy routingPolicy;
    private String secret;

    public String cacheSignature() {
        if (provider == null || routingPolicy == null) {
            return "unconfigured";
        }
        return provider.getProviderKey() + ":" + provider.getModelCode()
                + ":p" + provider.getConfigVersion()
                + ":r" + routingPolicy.getRoutingVersion();
    }
}
