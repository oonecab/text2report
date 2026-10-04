package com.text2report.controller;

import com.text2report.common.context.TraceContext;
import com.text2report.common.result.ApiResult;
import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.model.dto.PasswordChangeRequest;
import com.text2report.model.dto.UserCreateRequest;
import com.text2report.model.dto.UserDTO;
import com.text2report.model.dto.UserOrgGrantRequest;
import com.text2report.model.dto.UserUpdateRequest;
import com.text2report.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/users")
@Slf4j
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ApiResult<UserDTO> create(@Valid @RequestBody UserCreateRequest request, Authentication authentication) {
        return ApiResult.success(userService.createUser(request, authentication.getName()))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping
    public ApiResult<PageResult<UserDTO>> list(@RequestParam(required = false) String role,
                                               @RequestParam(required = false) Boolean isActive,
                                               // Deprecated compatibility alias. New clients should use isActive.
                                               @RequestParam(required = false) Boolean active,
                                               @RequestParam(required = false) Integer orgId,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               Authentication authentication) {
        if (isActive != null && active != null && !isActive.equals(active)) {
            log.warn("Conflicting user status filters received, preferring isActive over active [user={}, isActive={}, active={}]",
                    authentication.getName(), isActive, active);
        }
        Boolean effectiveIsActive = isActive != null ? isActive : active;
        return ApiResult.success(userService.listUsers(role, effectiveIsActive, orgId, keyword, page, size, authentication.getName()))
                .withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/{id}")
    public ApiResult<UserDTO> getById(@PathVariable Integer id, Authentication authentication) {
        return ApiResult.success(userService.getUserById(id, authentication.getName())).withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/{id}")
    public ApiResult<UserDTO> update(@PathVariable Integer id,
                                     @RequestBody UserUpdateRequest request,
                                     Authentication authentication) {
        return ApiResult.success(userService.updateUser(id, request, authentication.getName()))
                .withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/{id}/deactivate")
    public ApiResult<Void> deactivate(@PathVariable Integer id, Authentication authentication) {
        userService.deactivateUser(id, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/{id}/activate")
    public ApiResult<Void> activate(@PathVariable Integer id, Authentication authentication) {
        userService.activateUser(id, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PutMapping("/{id}/password")
    public ApiResult<Void> changePassword(@PathVariable Integer id,
                                          @Valid @RequestBody PasswordChangeRequest request,
                                          Authentication authentication) {
        userService.changePassword(id, request.getNewPassword(), authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @PostMapping("/{id}/org-grants")
    public ApiResult<Void> grantOrg(@PathVariable Integer id,
                                    @Valid @RequestBody UserOrgGrantRequest request,
                                    Authentication authentication) {
        request.setUserId(id);
        userService.grantOrgAccess(request, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @DeleteMapping("/{id}/org-grants/{orgId}")
    public ApiResult<Void> revokeOrg(@PathVariable Integer id,
                                     @PathVariable Integer orgId,
                                     Authentication authentication) {
        userService.revokeOrgAccess(id, orgId, authentication.getName());
        return ApiResult.<Void>success(null).withRequestId(TraceContext.getRequestId());
    }

    @GetMapping("/{id}/org-grants")
    public ApiResult<List<SysUserOrg>> grants(@PathVariable Integer id, Authentication authentication) {
        return ApiResult.success(userService.getUserOrgGrants(id, authentication.getName())).withRequestId(TraceContext.getRequestId());
    }
}
