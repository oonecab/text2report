package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.text2report.domain.meta.entity.SysUserOrg;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysUserOrgMapper extends BaseMapper<SysUserOrg> {

    List<SysUserOrg> selectByUserId(@Param("userId") Integer userId);

    List<SysUserOrg> selectByOrgId(@Param("orgId") Integer orgId);

    SysUserOrg selectPrimaryOrg(@Param("userId") Integer userId);

    int deleteByUserAndOrg(@Param("userId") Integer userId, @Param("orgId") Integer orgId);
}
