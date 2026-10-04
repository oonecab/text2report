package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.text2report.domain.meta.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    SysUser selectByUsername(@Param("username") String username);

    List<SysUser> list(@Param("role") String role,
                       @Param("isActive") Boolean isActive,
                       @Param("orgId") Integer orgId,
                       @Param("keyword") String keyword,
                       @Param("allowedOrgIds") List<Integer> allowedOrgIds,
                       @Param("excludeUserId") Integer excludeUserId,
                       @Param("limit") int limit,
                       @Param("offset") int offset);

    long count(@Param("role") String role,
               @Param("isActive") Boolean isActive,
               @Param("orgId") Integer orgId,
               @Param("keyword") String keyword,
               @Param("allowedOrgIds") List<Integer> allowedOrgIds,
               @Param("excludeUserId") Integer excludeUserId);

    int updateLastLoginAt(@Param("id") Integer id, @Param("lastLoginAt") LocalDateTime lastLoginAt);
}
