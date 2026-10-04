package com.text2report.service;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.DimOrg;
import com.text2report.domain.meta.entity.SysUser;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.domain.meta.mapper.DimOrgMapper;
import com.text2report.domain.meta.mapper.SysUserMapper;
import com.text2report.domain.meta.mapper.SysUserOrgMapper;
import com.text2report.model.dto.UserCreateRequest;
import com.text2report.model.dto.UserOrgGrantRequest;
import com.text2report.model.dto.UserDTO;
import com.text2report.model.dto.UserUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysUserOrgMapper sysUserOrgMapper;
    @Mock
    private DimOrgMapper dimOrgMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(sysUserMapper, sysUserOrgMapper, dimOrgMapper, passwordEncoder);
    }

    @Nested
    @DisplayName("Manager org grant upper bound")
    class ManagerOrgGrantUpperBoundTests {

        @Test
        @DisplayName("MANAGER 仅有 SELF 时不能给 USER 授予 CHILDREN")
        void managerWithSelfCannotGrantChildren() {
            stubOperatorAndTarget("MANAGER", "SELF");
            when(dimOrgMapper.selectList(any())).thenReturn(List.of(org(5)));
            UserOrgGrantRequest request = grantRequest(20, 2, "CHILDREN");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.grantOrgAccess(request, "manager"));

            assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
            assertEquals("权限范围外的组织不可授权", ex.getMessage());
            verify(sysUserOrgMapper, never()).insert(any(SysUserOrg.class));
        }

        @Test
        @DisplayName("MANAGER 仅有 SELF 时不能给 USER 授予 ALL")
        void managerWithSelfCannotGrantAll() {
            stubOperatorAndTarget("MANAGER", "SELF");
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            UserOrgGrantRequest request = grantRequest(20, 2, "ALL");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.grantOrgAccess(request, "manager"));

            assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
            assertEquals("权限范围外的组织不可授权", ex.getMessage());
            verify(sysUserOrgMapper, never()).insert(any(SysUserOrg.class));
        }

        @Test
        @DisplayName("MANAGER 自己有 ALL 时可以在上界内给 USER 授予 CHILDREN")
        void managerWithAllCanGrantChildrenWithinOwnScope() {
            stubOperatorAndTarget("MANAGER", "ALL");
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            when(dimOrgMapper.selectList(any())).thenReturn(List.of(org(5)));
            when(sysUserOrgMapper.selectOne(any())).thenReturn(null);
            UserOrgGrantRequest request = grantRequest(20, 2, "children");

            userService.grantOrgAccess(request, "manager");

            ArgumentCaptor<SysUserOrg> captor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).insert(captor.capture());
            assertEquals(20, captor.getValue().getUserId());
            assertEquals(2, captor.getValue().getOrgId());
            assertEquals("CHILDREN", captor.getValue().getScope());
        }

        @Test
        @DisplayName("请求未带 scope 时默认按 SELF 落库")
        void missingScopeDefaultsToSelf() {
            stubOperatorAndTarget("MANAGER", "SELF");
            when(sysUserOrgMapper.selectOne(any())).thenReturn(null);
            UserOrgGrantRequest request = grantRequest(20, 2, null);

            userService.grantOrgAccess(request, "manager");

            ArgumentCaptor<SysUserOrg> captor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).insert(captor.capture());
            assertEquals("SELF", captor.getValue().getScope());
        }

        @Test
        @DisplayName("未知 scope 不应被静默当作 SELF")
        void invalidScopeIsRejected() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser target = user(20, "user", "USER");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(20)).thenReturn(target);
            UserOrgGrantRequest request = grantRequest(20, 2, "ROOT");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.grantOrgAccess(request, "manager"));

            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
            assertEquals("无效的组织权限范围", ex.getMessage());
            verify(sysUserOrgMapper, never()).insert(any(SysUserOrg.class));
        }

        @Test
        @DisplayName("MANAGER 账号列表只能看到自己权限范围内的 USER")
        void managerListOnlyShowsManageableUsers() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser inScopeUser = user(20, "user-a", "USER");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(10)).thenReturn(manager);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(grant(10, 2, "ALL")));
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            when(sysUserMapper.list(null, null, null, null, List.of(2, 5), 10, 20, 0)).thenReturn(List.of(inScopeUser));
            when(sysUserMapper.count(null, null, null, null, List.of(2, 5), 10)).thenReturn(1L);

            PageResult<UserDTO> result = userService.listUsers(null, null, null, null, 1, 20, "manager");

            assertEquals(1, result.getTotal());
            assertEquals(List.of("user-a"), result.getList().stream().map(UserDTO::getUsername).toList());
        }

        @Test
        @DisplayName("账号列表查询应透传状态和关键字筛选条件")
        void listUsersPassesStatusAndKeywordFiltersToMapper() {
            SysUser admin = user(1, "admin", "ADMIN");
            when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);
            when(sysUserMapper.list("USER", false, null, "wang", null, null, 20, 0)).thenReturn(List.of());
            when(sysUserMapper.count("USER", false, null, "wang", null, null)).thenReturn(0L);

            PageResult<UserDTO> result = userService.listUsers("USER", false, null, "wang", 1, 20, "admin");

            assertEquals(0, result.getTotal());
            verify(sysUserMapper).list("USER", false, null, "wang", null, null, 20, 0);
            verify(sysUserMapper).count("USER", false, null, "wang", null, null);
        }

        @Test
        @DisplayName("账号列表查询应将页码转换为 limit 和 offset")
        void listUsersConvertsPageToLimitAndOffset() {
            SysUser admin = user(1, "admin", "ADMIN");
            when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);
            when(sysUserMapper.list(null, true, null, null, null, null, 10, 20)).thenReturn(List.of());
            when(sysUserMapper.count(null, true, null, null, null, null)).thenReturn(0L);

            PageResult<UserDTO> result = userService.listUsers(null, true, null, null, 3, 10, "admin");

            assertEquals(3, result.getPage());
            assertEquals(10, result.getPageSize());
            verify(sysUserMapper).list(null, true, null, null, null, null, 10, 20);
            verify(sysUserMapper).count(null, true, null, null, null, null);
        }

        @Test
        @DisplayName("搜索关键词过长时应拒绝查询")
        void listUsersRejectsOverlongKeyword() {
            SysUser admin = user(1, "admin", "ADMIN");
            when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);
            String longKeyword = "x".repeat(101);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.listUsers(null, null, null, longKeyword, 1, 20, "admin"));

            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
            assertEquals("搜索关键词长度不能超过100个字符", ex.getMessage());
        }

        @Test
        @DisplayName("MANAGER 不能编辑权限范围外的 USER")
        void managerCannotUpdateOutOfScopeUser() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser target = user(30, "user-b", "USER");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(10)).thenReturn(manager);
            when(sysUserMapper.selectById(30)).thenReturn(target);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(grant(10, 2, "ALL")));
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            when(sysUserOrgMapper.selectByUserId(30)).thenReturn(List.of(grant(30, 9, "SELF")));
            UserUpdateRequest request = new UserUpdateRequest();
            request.setDisplayName("越权编辑");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.updateUser(30, request, "manager"));

            assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
            assertEquals("无权管理该账号", ex.getMessage());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }

        @Test
        @DisplayName("MANAGER 不能编辑其他 MANAGER 或 ADMIN")
        void managerCannotUpdateManagerOrAdmin() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser target = user(11, "other-manager", "MANAGER");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(10)).thenReturn(manager);
            when(sysUserMapper.selectById(11)).thenReturn(target);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(grant(10, 2, "ALL")));
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            UserUpdateRequest request = new UserUpdateRequest();
            request.setDisplayName("越权编辑");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.updateUser(11, request, "manager"));

            assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
            assertEquals("无权管理该账号", ex.getMessage());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }

        @Test
        @DisplayName("MANAGER 创建 USER 时应自动绑定默认 SELF 组织权限，避免创建后列表不可见")
        void managerCreateUserGetsDefaultSelfGrant() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser created = user(21, "new-user", "USER");
            SysUserOrg primaryGrant = grant(10, 2, "ALL");
            SysUserOrg createdGrant = grant(21, 2, "SELF");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectByUsername("new-user")).thenReturn(null);
            when(passwordEncoder.encode("password123")).thenReturn("encoded");
            when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
                SysUser user = invocation.getArgument(0);
                user.setId(21);
                return 1;
            });
            when(sysUserOrgMapper.selectPrimaryOrg(10)).thenReturn(primaryGrant);
            when(sysUserMapper.selectById(21)).thenReturn(created);
            when(sysUserOrgMapper.selectByUserId(21)).thenReturn(List.of(createdGrant));
            when(dimOrgMapper.selectById(2)).thenReturn(org(2));
            when(dimOrgMapper.selectAncestorPath(2)).thenReturn("总公司 > 子公司A");
            UserCreateRequest request = createRequest("new-user", "password123", "新用户", "USER");

            UserDTO result = userService.createUser(request, "manager");

            ArgumentCaptor<SysUserOrg> grantCaptor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).insert(grantCaptor.capture());
            assertEquals(21, grantCaptor.getValue().getUserId());
            assertEquals(2, grantCaptor.getValue().getOrgId());
            assertEquals("SELF", grantCaptor.getValue().getScope());
            assertEquals(true, grantCaptor.getValue().getIsPrimary());
            assertEquals("manager", grantCaptor.getValue().getGrantedBy());
            assertEquals("new-user", result.getUsername());
        }

        @Test
        @DisplayName("MANAGER 没有主组织时创建 USER 使用第一条授权作为默认 SELF 组织")
        void managerCreateUserFallsBackToFirstGrantWhenNoPrimaryOrg() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser created = user(21, "new-user", "USER");
            SysUserOrg fallbackGrant = grant(10, 5, "SELF");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectByUsername("new-user")).thenReturn(null);
            when(passwordEncoder.encode("password123")).thenReturn("encoded");
            when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
                SysUser user = invocation.getArgument(0);
                user.setId(21);
                return 1;
            });
            when(sysUserOrgMapper.selectPrimaryOrg(10)).thenReturn(null);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(fallbackGrant));
            when(sysUserMapper.selectById(21)).thenReturn(created);
            when(sysUserOrgMapper.selectByUserId(21)).thenReturn(List.of(grant(21, 5, "SELF")));
            when(dimOrgMapper.selectById(5)).thenReturn(org(5));
            when(dimOrgMapper.selectAncestorPath(5)).thenReturn("总公司 > 子公司A > 部门一");
            UserCreateRequest request = createRequest("new-user", "password123", "新用户", "USER");

            userService.createUser(request, "manager");

            ArgumentCaptor<SysUserOrg> grantCaptor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).insert(grantCaptor.capture());
            assertEquals(5, grantCaptor.getValue().getOrgId());
            assertEquals("SELF", grantCaptor.getValue().getScope());
        }

        @Test
        @DisplayName("ADMIN 创建 USER 不自动绑定组织权限")
        void adminCreateUserDoesNotCreateDefaultOrgGrant() {
            SysUser admin = user(1, "admin", "ADMIN");
            SysUser created = user(21, "new-user", "USER");
            when(sysUserMapper.selectByUsername("admin")).thenReturn(admin);
            when(sysUserMapper.selectByUsername("new-user")).thenReturn(null);
            when(passwordEncoder.encode("password123")).thenReturn("encoded");
            when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
                SysUser user = invocation.getArgument(0);
                user.setId(21);
                return 1;
            });
            when(sysUserMapper.selectById(21)).thenReturn(created);
            when(sysUserOrgMapper.selectByUserId(21)).thenReturn(List.of());
            UserCreateRequest request = createRequest("new-user", "password123", "新用户", "USER");

            userService.createUser(request, "admin");

            verify(sysUserOrgMapper, never()).selectPrimaryOrg(eq(1));
            verify(sysUserOrgMapper, never()).insert(any(SysUserOrg.class));
        }

        @Test
        @DisplayName("MANAGER 首次显式添加部门权限时应替换创建时的默认公司授权")
        void managerFirstExplicitDepartmentGrantReplacesAutoDefaultCompanyGrant() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser target = user(20, "wangsi", "USER");
            SysUserOrg managerGrant = grant(10, 2, "ALL");
            SysUserOrg autoDefaultGrant = grant(20, 2, "SELF");
            autoDefaultGrant.setGrantedBy("manager");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(10)).thenReturn(manager);
            when(sysUserMapper.selectById(20)).thenReturn(target);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(managerGrant));
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            when(sysUserOrgMapper.selectOne(any())).thenReturn(null);
            when(sysUserOrgMapper.selectByUserId(20)).thenReturn(List.of(autoDefaultGrant));
            UserOrgGrantRequest request = grantRequest(20, 5, "SELF");

            userService.grantOrgAccess(request, "manager");

            verify(sysUserOrgMapper).deleteByUserAndOrg(20, 2);
            ArgumentCaptor<SysUserOrg> captor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).insert(captor.capture());
            assertEquals(20, captor.getValue().getUserId());
            assertEquals(5, captor.getValue().getOrgId());
            assertEquals("SELF", captor.getValue().getScope());
            assertEquals(true, captor.getValue().getIsPrimary());
            assertEquals("manager", captor.getValue().getGrantedBy());
        }

        @Test
        @DisplayName("部门授权已存在时再次保存应清理创建时的默认公司授权")
        void managerExistingDepartmentGrantReplacesAutoDefaultCompanyGrant() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser target = user(20, "wangsi", "USER");
            SysUserOrg managerGrant = grant(10, 2, "ALL");
            SysUserOrg autoDefaultGrant = grant(20, 2, "SELF");
            autoDefaultGrant.setGrantedBy("manager");
            SysUserOrg existingDeptGrant = grant(20, 5, "SELF");
            existingDeptGrant.setIsPrimary(false);
            existingDeptGrant.setGrantedBy("manager");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(10)).thenReturn(manager);
            when(sysUserMapper.selectById(20)).thenReturn(target);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(managerGrant));
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            when(sysUserOrgMapper.selectOne(any())).thenReturn(existingDeptGrant);
            when(sysUserOrgMapper.selectByUserId(20)).thenReturn(List.of(autoDefaultGrant, existingDeptGrant));
            UserOrgGrantRequest request = grantRequest(20, 5, "SELF");

            userService.grantOrgAccess(request, "manager");

            verify(sysUserOrgMapper).deleteByUserAndOrg(20, 2);
            verify(sysUserOrgMapper, never()).insert(any(SysUserOrg.class));
            ArgumentCaptor<SysUserOrg> captor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).updateById(captor.capture());
            assertEquals(20, captor.getValue().getUserId());
            assertEquals(5, captor.getValue().getOrgId());
            assertEquals("SELF", captor.getValue().getScope());
            assertEquals(true, captor.getValue().getIsPrimary());
            assertEquals("manager", captor.getValue().getGrantedBy());
        }

        @Test
        @DisplayName("显式设为默认组织时应取消其他默认授权")
        void explicitPrimaryGrantClearsOtherPrimaryGrants() {
            SysUser manager = user(10, "manager", "MANAGER");
            SysUser target = user(20, "wangsi", "USER");
            SysUserOrg managerGrant = grant(10, 2, "ALL");
            SysUserOrg previousPrimary = grant(20, 2, "SELF");
            previousPrimary.setGrantedBy("other-admin");
            when(sysUserMapper.selectByUsername("manager")).thenReturn(manager);
            when(sysUserMapper.selectById(10)).thenReturn(manager);
            when(sysUserMapper.selectById(20)).thenReturn(target);
            when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(managerGrant));
            when(dimOrgMapper.selectSubtreeIds(2)).thenReturn(List.of(2, 5));
            when(sysUserOrgMapper.selectOne(any())).thenReturn(null);
            when(sysUserOrgMapper.selectByUserId(20)).thenReturn(List.of(previousPrimary));
            UserOrgGrantRequest request = grantRequest(20, 5, "SELF");
            request.setIsPrimary(true);

            userService.grantOrgAccess(request, "manager");

            ArgumentCaptor<SysUserOrg> updateCaptor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).updateById(updateCaptor.capture());
            assertEquals(2, updateCaptor.getValue().getOrgId());
            assertEquals(false, updateCaptor.getValue().getIsPrimary());
            ArgumentCaptor<SysUserOrg> insertCaptor = ArgumentCaptor.forClass(SysUserOrg.class);
            verify(sysUserOrgMapper).insert(insertCaptor.capture());
            assertEquals(5, insertCaptor.getValue().getOrgId());
            assertEquals(true, insertCaptor.getValue().getIsPrimary());
        }
    }

    private void stubOperatorAndTarget(String operatorRole, String operatorScope) {
        SysUser operator = user(10, "manager", operatorRole);
        SysUser target = user(20, "user", "USER");
        SysUserOrg operatorGrant = new SysUserOrg();
        operatorGrant.setUserId(10);
        operatorGrant.setOrgId(2);
        operatorGrant.setScope(operatorScope);

        when(sysUserMapper.selectByUsername("manager")).thenReturn(operator);
        when(sysUserMapper.selectById(20)).thenReturn(target);
        when(sysUserMapper.selectById(10)).thenReturn(operator);
        when(sysUserOrgMapper.selectByUserId(10)).thenReturn(List.of(operatorGrant));
    }

    private UserOrgGrantRequest grantRequest(Integer userId, Integer orgId, String scope) {
        UserOrgGrantRequest request = new UserOrgGrantRequest();
        request.setUserId(userId);
        request.setOrgId(orgId);
        request.setScope(scope);
        request.setIsPrimary(false);
        return request;
    }

    private UserCreateRequest createRequest(String username, String password, String displayName, String role) {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setDisplayName(displayName);
        request.setRole(role);
        return request;
    }

    private SysUser user(Integer id, String username, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setIsActive(true);
        return user;
    }

    private DimOrg org(Integer id) {
        DimOrg org = new DimOrg();
        org.setId(id);
        org.setIsActive(true);
        return org;
    }

    private SysUserOrg grant(Integer userId, Integer orgId, String scope) {
        SysUserOrg grant = new SysUserOrg();
        grant.setUserId(userId);
        grant.setOrgId(orgId);
        grant.setScope(scope);
        grant.setIsPrimary(true);
        return grant;
    }
}
