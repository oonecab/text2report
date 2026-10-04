package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.result.ApiResult;
import com.text2report.common.result.PageResult;
import com.text2report.model.dto.LlmAvailabilityVO;
import com.text2report.model.dto.LlmConnectionTestResult;
import com.text2report.model.dto.LlmProviderRequest;
import com.text2report.model.dto.LlmProviderVO;
import com.text2report.model.dto.LlmRoutingPolicyRequest;
import com.text2report.model.dto.LlmRoutingPolicyVO;
import com.text2report.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmProviderConfigService llmProviderConfigService;

    @GetMapping("/api/v1/llm/status")
    public ApiResult<LlmAvailabilityVO> status() {
        return ApiResult.success(llmProviderConfigService.getAvailability()).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/api/v1/admin/llm/providers")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<PageResult<LlmProviderVO>> listProviders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String providerMode,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResult.success(llmProviderConfigService.listProviders(page, pageSize, providerMode, enabled, keyword))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/api/v1/admin/llm/providers/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<List<LlmProviderVO>> listEnabledProviders() {
        return ApiResult.success(llmProviderConfigService.listEnabledProviders()).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/api/v1/admin/llm/providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<LlmProviderVO> getProvider(@PathVariable Integer id) {
        return ApiResult.success(llmProviderConfigService.getProvider(id)).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/api/v1/admin/llm/providers")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<LlmProviderVO> createProvider(@Valid @RequestBody LlmProviderRequest request) {
        return ApiResult.success(llmProviderConfigService.createProvider(request)).withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/api/v1/admin/llm/providers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<LlmProviderVO> updateProvider(@PathVariable Integer id, @Valid @RequestBody LlmProviderRequest request) {
        return ApiResult.success(llmProviderConfigService.updateProvider(id, request)).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/api/v1/admin/llm/providers/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> enableProvider(@PathVariable Integer id) {
        llmProviderConfigService.setEnabled(id, true);
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/api/v1/admin/llm/providers/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> disableProvider(@PathVariable Integer id) {
        llmProviderConfigService.setEnabled(id, false);
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/api/v1/admin/llm/providers/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> activateProvider(@PathVariable Integer id) {
        llmProviderConfigService.activateProvider(id);
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/api/v1/admin/llm/providers/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<LlmConnectionTestResult> testProvider(@PathVariable Integer id) {
        return ApiResult.success(llmProviderConfigService.testProvider(id)).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/api/v1/admin/llm/routing-policies")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<List<LlmRoutingPolicyVO>> listRoutingPolicies() {
        return ApiResult.success(llmProviderConfigService.listRoutingPolicies()).withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/api/v1/admin/llm/routing-policies/{capability}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<LlmRoutingPolicyVO> updateRoutingPolicy(
            @PathVariable String capability,
            @Valid @RequestBody LlmRoutingPolicyRequest request
    ) {
        return ApiResult.success(llmProviderConfigService.updateRoutingPolicy(capability, request))
                .withRequestId(TraceContext.getRequestId());
    }
}
