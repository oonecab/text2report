package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.ApiResult;
import com.text2report.domain.meta.entity.DimOrg;
import com.text2report.model.dto.OrgSyncResult;
import com.text2report.model.dto.OrgTreeNode;
import com.text2report.model.dto.UserOrgGrantRequest;
import com.text2report.service.OrgService;
import com.text2report.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orgs")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final UserService userService;

    @GetMapping("/tree")
    public ApiResult<List<OrgTreeNode>> getOrgTree() {
        return ApiResult.success(orgService.getFullOrgTree()).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/my-tree")
    public ApiResult<List<OrgTreeNode>> getMyTree() {
        Integer userId = TraceContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或Token失效");
        }
        return ApiResult.success(orgService.getOrgTreeForUser(userId)).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/{orgId}")
    public ApiResult<DimOrg> getOrgById(@PathVariable Integer orgId) {
        DimOrg org = orgService.getOrgById(orgId);
        if (org == null) {
            return ApiResult.error(ErrorCode.NOT_FOUND, "Organization not found");
        }
        return ApiResult.success(org).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/{orgId}/subtree-ids")
    public ApiResult<List<Integer>> getSubtreeIds(@PathVariable Integer orgId) {
        return ApiResult.success(orgService.resolveSubtreeIds(orgId)).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/{orgId}/path")
    public ApiResult<String> getOrgPath(@PathVariable Integer orgId) {
        return ApiResult.success(orgService.resolveOrgPath(orgId)).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<OrgSyncResult> sync() {
        return ApiResult.success(orgService.syncFromBusiness()).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/sync/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<SyncStatus> syncStatus() {
        return ApiResult.success(new SyncStatus(orgService.getLastSyncTime())).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/{orgId}/users")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResult<?> getUsersByOrg(@PathVariable Integer orgId) {
        return ApiResult.success(orgService.getUsersByOrgId(orgId)).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/{orgId}/users")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResult<Void> addUserToOrg(@PathVariable Integer orgId,
                                        @RequestBody UserOrgGrantRequest request,
                                        Authentication authentication) {
        request.setOrgId(orgId);
        userService.grantOrgAccess(request, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/{orgId}/users/{userId}/scope")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResult<Void> updateUserScope(@PathVariable Integer orgId,
                                           @PathVariable Integer userId,
                                           @RequestBody ScopeRequest request,
                                           Authentication authentication) {
        UserOrgGrantRequest grantRequest = new UserOrgGrantRequest();
        grantRequest.setUserId(userId);
        grantRequest.setOrgId(orgId);
        grantRequest.setScope(request.getScope());
        grantRequest.setIsPrimary(request.getIsPrimary());
        userService.grantOrgAccess(grantRequest, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @DeleteMapping("/{orgId}/users/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResult<Void> removeUserFromOrg(@PathVariable Integer orgId,
                                             @PathVariable Integer userId,
                                             Authentication authentication) {
        userService.revokeOrgAccess(userId, orgId, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.GONE)
    public ApiResult<Void> createOrgDeprecated() {
        return ApiResult.<Void>error(ErrorCode.PARAM_INVALID, "组织 CUD 接口已废弃").withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/{orgId}")
    @ResponseStatus(HttpStatus.GONE)
    public ApiResult<Void> updateOrgDeprecated(@PathVariable Integer orgId) {
        return ApiResult.<Void>error(ErrorCode.PARAM_INVALID, "组织 CUD 接口已废弃").withRequestId(TraceContext.getRequestId());
    }

    @DeleteMapping("/{orgId}")
    @ResponseStatus(HttpStatus.GONE)
    public ApiResult<Void> deleteOrgDeprecated(@PathVariable Integer orgId) {
        return ApiResult.<Void>error(ErrorCode.PARAM_INVALID, "组织 CUD 接口已废弃").withRequestId(TraceContext.getRequestId());
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SyncStatus {
        private LocalDateTime lastSyncAt;
    }

    @lombok.Data
    public static class ScopeRequest {
        private String scope;
        private Boolean isPrimary;
    }
}
