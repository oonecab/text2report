package com.text2report.service;

import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.model.dto.OrgUserVO;
import com.text2report.model.dto.UserCreateRequest;
import com.text2report.model.dto.UserDTO;
import com.text2report.model.dto.UserOrgGrantRequest;
import com.text2report.model.dto.UserUpdateRequest;

import java.util.List;

public interface UserService {
    UserDTO createUser(UserCreateRequest req, String operatorUsername);
    UserDTO updateUser(Integer userId, UserUpdateRequest req, String operatorUsername);
    void deactivateUser(Integer userId, String operatorUsername);
    void activateUser(Integer userId, String operatorUsername);
    UserDTO getUserById(Integer userId);
    UserDTO getUserById(Integer userId, String operatorUsername);
    PageResult<UserDTO> listUsers(String role, Boolean isActive, Integer orgId, String keyword, int page, int size, String operatorUsername);
    void grantOrgAccess(UserOrgGrantRequest req, String operatorUsername);
    void revokeOrgAccess(Integer userId, Integer orgId, String operatorUsername);
    List<SysUserOrg> getUserOrgGrants(Integer userId, String operatorUsername);
    List<OrgUserVO> getUsersByOrgId(Integer orgId);
    List<Integer> resolveAccessibleOrgIds(Integer userId);
    boolean hasAccessToOrg(Integer userId, Integer orgId);
    void changePassword(Integer userId, String newPassword, String operatorUsername);
}
