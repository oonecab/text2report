package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryPlan;
import com.text2report.service.llm.ActiveProviderContext;
import com.text2report.service.llm.LLMService;
import com.text2report.service.llm.ProviderRoutingResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds prompts, calls the LLM and repairs SQL until it passes validation.
 */
@Service
@RequiredArgsConstructor
public class SqlGenerationWorkflow {

    private final LLMService llmService;
    private final PromptBuilder promptBuilder;
    private final SqlValidationPipeline validationPipeline;
    private final SqlRetryPolicy retryPolicy;
    private ProviderRoutingResolver providerRoutingResolver;

    @Autowired(required = false)
    public void setProviderRoutingResolver(ProviderRoutingResolver providerRoutingResolver) {
        this.providerRoutingResolver = providerRoutingResolver;
    }

    public String generateValidatedSql(QueryContext queryContext, QueryPlan plan) {
        queryContext.setPrompt(promptBuilder.buildSqlGenerationPrompt(
                "postgresql",
                plan.getOrgPath(),
                joinOrgIds(plan.getOrgScopeIds()),
                plan.getMetadataJson(),
                queryContext.getQuestion()
        ));

        String generatedSql = llmService.generateSQL(
                queryContext.getQuestion(),
                plan.getMetadataJson(),
                queryContext.getOrgId(),
                queryContext.getPrompt()
        );
        queryContext.setGeneratedSql(generatedSql);
        captureSqlGenerationProvider(queryContext);

        return validateOrRepair(queryContext, plan, generatedSql);
    }

    public String validateOrRepair(QueryContext queryContext, QueryPlan plan, String sql) {
        String currentSql = sql;
        for (int attempt = 0; attempt <= queryContext.getMaxRetries(); attempt++) {
            try {
                String validatedSql = validationPipeline.validate(currentSql, plan);
                queryContext.setValidatedSql(validatedSql);
                return validatedSql;
            } catch (BusinessException validationException) {
                if (!retryPolicy.canRetry(validationException, queryContext)) {
                    throw validationException;
                }

                retryPolicy.recordRetry(queryContext, validationException.getMessage());
                currentSql = llmService.repairSQL(currentSql, validationException.getMessage(), plan.getMetadataJson());
                queryContext.setGeneratedSql(currentSql);
            }
        }
        throw new BusinessException(com.text2report.common.exception.ErrorCode.INTERNAL_ERROR, "查询执行失败");
    }

    private String joinOrgIds(java.util.List<Integer> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return "";
        }
        return orgIds.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("");
    }

    private void captureSqlGenerationProvider(QueryContext queryContext) {
        if (providerRoutingResolver == null) {
            return;
        }
        ActiveProviderContext providerContext = providerRoutingResolver.getLastResolved();
        if (providerContext == null || providerContext.getProvider() == null || providerContext.getRoutingPolicy() == null) {
            return;
        }
        queryContext.setLlmProviderId(providerContext.getProvider().getId());
        queryContext.setLlmProviderKey(providerContext.getProvider().getProviderKey());
        queryContext.setLlmVendorType(providerContext.getProvider().getVendorType());
        queryContext.setLlmModelCode(providerContext.getProvider().getModelCode());
        queryContext.setLlmRoutingVersion(providerContext.getRoutingPolicy().getRoutingVersion());
    }
}
