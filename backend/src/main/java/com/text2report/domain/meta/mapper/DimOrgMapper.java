package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.text2report.domain.meta.entity.DimOrg;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DimOrgMapper extends BaseMapper<DimOrg> {

    List<DimOrg> selectAll();

    List<Integer> selectSubtreeIds(@Param("orgId") Integer orgId);

    String selectAncestorPath(@Param("orgId") Integer orgId);

    int insertWithId(DimOrg org);

    int updateById(DimOrg org);

    int deactivate(@Param("id") Integer id);
}
