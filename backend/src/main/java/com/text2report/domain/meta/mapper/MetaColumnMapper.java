package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.text2report.domain.meta.entity.MetaColumn;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Column metadata mapper
 */
@Mapper
public interface MetaColumnMapper extends BaseMapper<MetaColumn> {

    default List<MetaColumn> selectByTableId(Integer tableId) {
        return selectList(new LambdaQueryWrapper<MetaColumn>()
                .eq(MetaColumn::getTableId, tableId)
                .eq(MetaColumn::getActive, true)
                .orderByAsc(MetaColumn::getSortOrder, MetaColumn::getColumnName));
    }
}
