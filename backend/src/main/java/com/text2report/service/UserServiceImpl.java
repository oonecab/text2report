package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.DimOrg;
import com.text2report.domain.meta.entity.SysUser;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.domain.meta.mapper.DimOrgMapper;
import com.text2report.domain.meta.mapper.SysUserMapper;
import com.text2report.domain.meta.mapper.SysUserOrgMapper;
import com.text2report.model.dto.OrgUserVO;
import com.text2report.model.dto.UserCreateRequest;
import com.text2report.model.dto.UserDTO;
import com.text2report.model.dto.UserOrgGrantRequest;
import com.text2report.model.dto.UserOrgGrantVO;
import com.text2report.model.dto.UserUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final SysUserOrgMapper sysUserOrgMapper;
    private final DimOrgMapper dimOrgMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public UserDTO createUser(UserCreateRequest req, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        validateCreateRole(operator, req.getRole());
        if (sysUserMapper.selectByUsername(req.getUsername()) != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setDisplayName(req.getDisplayName());
        user.setRole(normalizeRole(req.getRole()));
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setIsActive(true);
        user.setCreatedBy(operatorUsername);
        user.setUpdatedBy(operatorUsername);
        sysUserMapper.insert(user);
        createDefaultGrantForManagerCreatedUser(operator, user, operatorUsername);
        return getUserById(user.getId());
    }

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public UserDTO updateUser(Integer userId, UserUpdateRequest req, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser existing = requireUserById(userId);
        validateCanManageExistingUser(operator, existing);
        if (StringUtils.hasText(req.getRole()) && !existing.getRole().equalsIgnoreCase(req.getRole())) {
            validateCreateRole(operator, req.getRole());
            existing.setRole(normalizeRole(req.getRole()));
        }
        if (req.getDisplayName() != null) existing.setDisplayName(req.getDisplayName());
        if (req.getEmail() != null) existing.setEmail(req.getEmail());
        if (req.getPhone() != null) existing.setPhone(req.getPhone());
        if (req.getIsActive() != null) existing.setIsActive(req.getIsActive());
        existing.setUpdatedBy(operatorUsername);
        sysUserMapper.updateById(existing);
        return getUserById(userId, operatorUsername);
    }

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public void deactivateUser(Integer userId, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser user = requireUserById(userId);
        validateCanManageExistingUser(operator, user);
        user.setIsActive(false);
        user.setUpdatedBy(operatorUsername);
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public void activateUser(Integer userId, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser user = requireUserById(userId);
        validateCanManageExistingUser(operator, user);
        user.setIsActive(true);
        user.setUpdatedBy(operatorUsername);
        sysUserMapper.updateById(user);
    }

    @Override
    public UserDTO getUserById(Integer userId, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser user = requireUserById(userId);
        validateCanManageExistingUser(operator, user);
        UserDTO dto = toUserDTO(user);
        dto.setOrgGrants(getUserOrgGrantDetails(userId));
        return dto;
    }

    @Override
    public UserDTO getUserById(Integer userId) {
        SysUser user = requireUserById(userId);
        UserDTO dto = toUserDTO(user);
        dto.setOrgGrants(getUserOrgGrantDetails(userId));
        return dto;
    }

    @Override
    public PageResult<UserDTO> listUsers(String role, Boolean isActive, Integer orgId, String keyword, int page, int size, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int offset = (safePage - 1) * safeSize;
        String normalizedKeyword = normalizeKeyword(keyword);
        List<Integer> allowedOrgIds = null;
        Integer excludeUserId = null;
        if ("MANAGER".equalsIgnoreCase(operator.getRole())) {
            allowedOrgIds = resolveAccessibleOrgIds(operator.getId());
            // Keep this short-circuit even though the mapper now has an AND 1 = 0 fallback:
            // it avoids issuing a pointless SQL query when the manager currently has no manageable org scope.
            if (allowedOrgIds.isEmpty()) {
                return PageResult.of(List.of(), 0, safePage, safeSize);
            }
            excludeUserId = operator.getId();
        }
        List<UserDTO> list = sysUserMapper.list(role, isActive, orgId, normalizedKeyword, allowedOrgIds, excludeUserId, safeSize, offset)
                .stream()
                .map(this::toUserDTO)
                .toList();
        long total = sysUserMapper.count(role, isActive, orgId, normalizedKeyword, allowedOrgIds, excludeUserId);
        return PageResult.of(list, total, safePage, safeSize);
    }

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public void grantOrgAccess(UserOrgGrantRequest req, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser target = requireUserById(req.getUserId());
        String normalizedScope = normalizeGrantScope(req.getScope());
        if ("MANAGER".equalsIgnoreCase(operator.getRole())) {
            validateManagerTargetUser(operator, target);
            List<Integer> accessible = resolveAccessibleOrgIds(operator.getId());
            Set<Integer> grantScopeIds = expandOrgScopeIds(req.getOrgId(), normalizedScope);
            if (!accessible.containsAll(grantScopeIds)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "权限范围外的组织不可授权");
            }
        }
        LambdaQueryWrapper<SysUserOrg> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUserOrg::getUserId, req.getUserId()).eq(SysUserOrg::getOrgId, req.getOrgId());
        SysUserOrg existing = sysUserOrgMapper.selectOne(wrapper);
        List<SysUserOrg> targetGrants = List.of();
        SysUserOrg managerDefaultGrantToReplace = null;
        if ("MANAGER".equalsIgnoreCase(operator.getRole())) {
            targetGrants = safeList(sysUserOrgMapper.selectByUserId(target.getId()));
            managerDefaultGrantToReplace = findReplaceableManagerCreatedDefaultGrant(
                    targetGrants,
                    req.getOrgId(),
                    operatorUsername
            );
            if (managerDefaultGrantToReplace != null) {
                sysUserOrgMapper.deleteByUserAndOrg(target.getId(), managerDefaultGrantToReplace.getOrgId());
                SysUserOrg replacedGrant = managerDefaultGrantToReplace;
                targetGrants = targetGrants.stream()
                        .filter(grant -> grant != replacedGrant)
                        .toList();
            }
        }

        boolean shouldSetPrimary = Boolean.TRUE.equals(req.getIsPrimary()) || managerDefaultGrantToReplace != null;
        if (shouldSetPrimary && targetGrants.isEmpty()) {
            targetGrants = safeList(sysUserOrgMapper.selectByUserId(target.getId()));
        }
        if (shouldSetPrimary) {
            clearOtherPrimaryGrants(targetGrants, req.getOrgId());
        }

        if (existing == null) {
            SysUserOrg grant = new SysUserOrg();
            grant.setUserId(req.getUserId());
            grant.setOrgId(req.getOrgId());
            grant.setScope(normalizedScope);
            grant.setIsPrimary(shouldSetPrimary);
            grant.setGrantedBy(operatorUsername);
            sysUserOrgMapper.insert(grant);
        } else {
            existing.setScope(normalizedScope);
            if (shouldSetPrimary) {
                existing.setIsPrimary(true);
            } else {
                existing.setIsPrimary(false);
            }
            existing.setGrantedBy(operatorUsername);
            sysUserOrgMapper.updateById(existing);
        }
    }

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public void revokeOrgAccess(Integer userId, Integer orgId, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser target = requireUserById(userId);
        if ("MANAGER".equalsIgnoreCase(operator.getRole())) {
            validateCanManageExistingUser(operator, target);
            if (!resolveAccessibleOrgIds(operator.getId()).contains(orgId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "权限范围外的组织不可回收授权");
            }
        }
        sysUserOrgMapper.deleteByUserAndOrg(userId, orgId);
    }

    @Override
    public List<SysUserOrg> getUserOrgGrants(Integer userId, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser target = requireUserById(userId);
        validateCanManageExistingUser(operator, target);
        return sysUserOrgMapper.selectByUserId(userId);
    }

    @Override
    public List<OrgUserVO> getUsersByOrgId(Integer orgId) {
        List<SysUserOrg> grants = sysUserOrgMapper.selectByOrgId(orgId);
        List<OrgUserVO> users = new ArrayList<>();
        for (SysUserOrg grant : grants) {
            SysUser user = sysUserMapper.selectById(grant.getUserId());
            if (user == null) {
                continue;
            }
            OrgUserVO vo = new OrgUserVO();
            vo.setUserId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setDisplayName(user.getDisplayName());
            vo.setRole(user.getRole());
            vo.setScope(grant.getScope());
            vo.setIsPrimary(grant.getIsPrimary());
            vo.setGrantedAt(grant.getGrantedAt());
            users.add(vo);
        }
        return users;
    }

    @Override
    public List<Integer> resolveAccessibleOrgIds(Integer userId) {
        SysUser user = requireUserById(userId);
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            return dimOrgMapper.selectAll().stream().map(DimOrg::getId).toList();
        }
        Set<Integer> result = new LinkedHashSet<>();
        for (SysUserOrg grant : sysUserOrgMapper.selectByUserId(userId)) {
            switch (normalizeScope(grant.getScope())) {
                case "SELF" -> result.add(grant.getOrgId());
                case "CHILDREN" -> {
                    result.add(grant.getOrgId());
                    LambdaQueryWrapper<DimOrg> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(DimOrg::getParentId, grant.getOrgId()).eq(DimOrg::getIsActive, true);
                    dimOrgMapper.selectList(wrapper).forEach(org -> result.add(org.getId()));
                }
                case "ALL" -> result.addAll(dimOrgMapper.selectSubtreeIds(grant.getOrgId()));
                default -> result.add(grant.getOrgId());
            }
        }
        return result.stream().sorted().toList();
    }

    @Override
    public boolean hasAccessToOrg(Integer userId, Integer orgId) {
        return resolveAccessibleOrgIds(userId).contains(orgId);
    }

    @Override
    @Transactional(transactionManager = "metaTransactionManager")
    public void changePassword(Integer userId, String newPassword, String operatorUsername) {
        SysUser operator = requireUserByUsername(operatorUsername);
        SysUser user = requireUserById(userId);
        validateCanManageExistingUser(operator, user);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedBy(operatorUsername);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    private List<UserOrgGrantVO> getUserOrgGrantDetails(Integer userId) {
        List<UserOrgGrantVO> details = new ArrayList<>();
        for (SysUserOrg grant : sysUserOrgMapper.selectByUserId(userId)) {
            DimOrg org = dimOrgMapper.selectById(grant.getOrgId());
            UserOrgGrantVO vo = new UserOrgGrantVO();
            vo.setId(grant.getId());
            vo.setOrgId(grant.getOrgId());
            vo.setOrgName(org != null ? org.getOrgName() : null);
            String orgPath = dimOrgMapper.selectAncestorPath(grant.getOrgId());
            vo.setOrgPath(orgPath != null ? orgPath : "未知组织");
            vo.setScope(grant.getScope());
            vo.setIsPrimary(grant.getIsPrimary());
            vo.setGrantedBy(grant.getGrantedBy());
            vo.setGrantedAt(grant.getGrantedAt());
            details.add(vo);
        }
        details.sort(Comparator.comparing(UserOrgGrantVO::getOrgId));
        return details;
    }

    private UserDTO toUserDTO(SysUser user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(user.getRole());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setIsActive(user.getIsActive());
        dto.setLastLoginAt(user.getLastLoginAt());
        return dto;
    }

    private SysUser requireUserByUsername(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private SysUser requireUserById(Integer userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private void validateCreateRole(SysUser operator, String targetRole) {
        String normalized = normalizeRole(targetRole);
        if ("ADMIN".equalsIgnoreCase(operator.getRole())) {
            return;
        }
        if ("MANAGER".equalsIgnoreCase(operator.getRole()) && "USER".equals(normalized)) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色无权创建该类型账号");
    }

    private void createDefaultGrantForManagerCreatedUser(SysUser operator, SysUser user, String operatorUsername) {
        if (!"MANAGER".equalsIgnoreCase(operator.getRole()) || !"USER".equalsIgnoreCase(user.getRole())) {
            return;
        }

        Integer defaultOrgId = resolveDefaultGrantOrgId(operator.getId());
        SysUserOrg grant = new SysUserOrg();
        grant.setUserId(user.getId());
        grant.setOrgId(defaultOrgId);
        grant.setScope("SELF");
        grant.setIsPrimary(true);
        grant.setGrantedBy(operatorUsername);
        sysUserOrgMapper.insert(grant);
    }

    private Integer resolveDefaultGrantOrgId(Integer operatorId) {
        SysUserOrg primaryGrant = sysUserOrgMapper.selectPrimaryOrg(operatorId);
        if (primaryGrant != null) {
            return primaryGrant.getOrgId();
        }

        List<SysUserOrg> grants = sysUserOrgMapper.selectByUserId(operatorId);
        if (grants == null || grants.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号缺少可授权组织");
        }
        return grants.get(0).getOrgId();
    }

    private SysUserOrg findReplaceableManagerCreatedDefaultGrant(List<SysUserOrg> targetGrants,
                                                                 Integer newOrgId,
                                                                 String operatorUsername) {
        List<SysUserOrg> candidates = targetGrants.stream()
                .filter(grant -> Boolean.TRUE.equals(grant.getIsPrimary()))
                .filter(grant -> "SELF".equals(normalizeScope(grant.getScope())))
                .filter(grant -> Objects.equals(operatorUsername, grant.getGrantedBy()))
                .filter(grant -> !Objects.equals(grant.getOrgId(), newOrgId))
                .toList();
        if (candidates.size() != 1) {
            return null;
        }

        SysUserOrg candidate = candidates.get(0);
        List<SysUserOrg> otherGrants = targetGrants.stream()
                .filter(grant -> grant != candidate)
                .toList();
        if (otherGrants.isEmpty()
                || (otherGrants.size() == 1 && Objects.equals(otherGrants.get(0).getOrgId(), newOrgId))) {
            return candidate;
        }
        return null;
    }

    private void clearOtherPrimaryGrants(List<SysUserOrg> grants, Integer primaryOrgId) {
        for (SysUserOrg grant : grants) {
            if (Boolean.TRUE.equals(grant.getIsPrimary()) && !Objects.equals(grant.getOrgId(), primaryOrgId)) {
                grant.setIsPrimary(false);
                sysUserOrgMapper.updateById(grant);
            }
        }
    }

    private List<SysUserOrg> safeList(List<SysUserOrg> grants) {
        return grants != null ? grants : List.of();
    }

    private String normalizeRole(String role) {
        return StringUtils.hasText(role) ? role.trim().toUpperCase() : "USER";
    }

    private String normalizeScope(String scope) {
        return StringUtils.hasText(scope) ? scope.trim().toUpperCase() : "SELF";
    }

    private String normalizeGrantScope(String scope) {
        String normalized = normalizeScope(scope);
        if ("SELF".equals(normalized) || "CHILDREN".equals(normalized) || "ALL".equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "无效的组织权限范围");
    }

    private Set<Integer> expandOrgScopeIds(Integer orgId, String scope) {
        Set<Integer> result = new LinkedHashSet<>();
        switch (scope) {
            case "SELF" -> result.add(orgId);
            case "CHILDREN" -> {
                result.add(orgId);
                LambdaQueryWrapper<DimOrg> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(DimOrg::getParentId, orgId).eq(DimOrg::getIsActive, true);
                dimOrgMapper.selectList(wrapper).forEach(org -> result.add(org.getId()));
            }
            case "ALL" -> result.addAll(dimOrgMapper.selectSubtreeIds(orgId));
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "无效的组织权限范围");
        }
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "授权组织不存在");
        }
        return result;
    }

    private void validateCanManageExistingUser(SysUser operator, SysUser target) {
        if ("ADMIN".equalsIgnoreCase(operator.getRole())) {
            return;
        }
        if ("MANAGER".equalsIgnoreCase(operator.getRole())
                && canManageExistingUser(operator, target, new LinkedHashSet<>(resolveAccessibleOrgIds(operator.getId())))) {
            return;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该账号");
    }

    private boolean canManageExistingUser(SysUser operator, SysUser target, Set<Integer> operatorAccessibleOrgIds) {
        if (!"MANAGER".equalsIgnoreCase(operator.getRole())) {
            return "ADMIN".equalsIgnoreCase(operator.getRole());
        }
        if (!"USER".equalsIgnoreCase(target.getRole()) || target.getId().equals(operator.getId())) {
            return false;
        }
        Set<Integer> targetScopeIds = resolveGrantedScopeIds(target.getId());
        return !targetScopeIds.isEmpty() && operatorAccessibleOrgIds.containsAll(targetScopeIds);
    }

    private void validateManagerTargetUser(SysUser operator, SysUser target) {
        if (!"USER".equalsIgnoreCase(target.getRole()) || target.getId().equals(operator.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该账号");
        }
    }

    private Set<Integer> resolveGrantedScopeIds(Integer userId) {
        Set<Integer> result = new LinkedHashSet<>();
        for (SysUserOrg grant : sysUserOrgMapper.selectByUserId(userId)) {
            result.addAll(expandOrgScopeIds(grant.getOrgId(), normalizeScope(grant.getScope())));
        }
        return result;
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "搜索关键词长度不能超过100个字符");
        }
        return normalized;
    }
}
