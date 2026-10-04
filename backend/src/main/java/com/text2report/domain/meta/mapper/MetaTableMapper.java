package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.text2report.domain.meta.entity.MetaTable;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * Table metadata mapper
 */
@Mapper
public interface MetaTableMapper extends BaseMapper<MetaTable> {

    default LambdaQueryWrapper<MetaTable> queryableWrapper(Integer datasourceId) {
        LambdaQueryWrapper<MetaTable> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(MetaTable::getDatasourceId, datasourceId);
        }
        wrapper.eq(MetaTable::getActive, true)
                .eq(MetaTable::getPublished, true);
        return wrapper;
    }

    default List<MetaTable> selectQueryableTables(Integer datasourceId) {
        return selectList(queryableWrapper(datasourceId)
                .orderByAsc(MetaTable::getSchemaName, MetaTable::getTableName));
    }

    default List<MetaTable> selectActiveList(Integer datasourceId) {
        LambdaQueryWrapper<MetaTable> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(MetaTable::getDatasourceId, datasourceId);
        }
        wrapper.eq(MetaTable::getActive, true)
                .orderByAsc(MetaTable::getSchemaName, MetaTable::getTableName);
        return selectList(wrapper);
    }

    default List<MetaTable> selectListByDatasource(Integer datasourceId) {
        LambdaQueryWrapper<MetaTable> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(MetaTable::getDatasourceId, datasourceId);
        }
        wrapper.orderByDesc(MetaTable::getActive)
                .orderByAsc(MetaTable::getSchemaName, MetaTable::getTableName);
        return selectList(wrapper);
    }

    default List<MetaTable> selectQueryableBatchIds(Collection<Integer> ids, Integer datasourceId) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(queryableWrapper(datasourceId)
                .in(MetaTable::getId, ids));
    }

    default List<MetaTable> searchByKeyword(String keyword, Integer datasourceId, int limit) {
        LambdaQueryWrapper<MetaTable> wrapper = queryableWrapper(datasourceId)
                .and(w -> w.like(MetaTable::getLabel, keyword)
                        .or()
                        .like(MetaTable::getDescription, keyword)
                        .or()
                        .like(MetaTable::getTableName, keyword));
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return selectList(wrapper);
    }
}
