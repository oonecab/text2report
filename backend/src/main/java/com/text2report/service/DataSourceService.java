package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.domain.meta.mapper.MetaDatasourceMapper;
import com.text2report.model.dto.DataSourceDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Service for managing data sources (meta_datasource table).
 * Handles CRUD operations with password encryption and sensitive data masking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSourceService {

    private final MetaDatasourceMapper datasourceMapper;

    @Value("${datasource.password.secret-key:default-secret}")
    private String secretKey;

    /**
     * List all data sources.
     */
    public List<MetaDatasource> listAll() {
        LambdaQueryWrapper<MetaDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(MetaDatasource::getId);
        List<MetaDatasource> datasources = datasourceMapper.selectList(wrapper);
        datasources.forEach(this::maskPassword);
        return datasources;
    }

    /**
     * List only active data sources.
     */
    public List<MetaDatasource> listActive() {
        LambdaQueryWrapper<MetaDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaDatasource::getActive, true)
                .orderByAsc(MetaDatasource::getName);
        List<MetaDatasource> datasources = datasourceMapper.selectList(wrapper);
        datasources.forEach(this::maskPassword);
        return datasources;
    }

    /**
     * Get data source by ID.
     */
    public MetaDatasource getById(Integer id) {
        MetaDatasource ds = datasourceMapper.selectById(id);
        if (ds == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Data source not found");
        }
        maskPassword(ds);
        return ds;
    }

    /**
     * Create a new data source.
     */
    @Transactional(transactionManager = "metaTransactionManager")
    public MetaDatasource create(DataSourceDTO dto) {
        // Validate duplicate name
        LambdaQueryWrapper<MetaDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaDatasource::getName, dto.getName());
        if (datasourceMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Data source name already exists");
        }

        MetaDatasource ds = new MetaDatasource();
        ds.setName(dto.getName());
        ds.setDbType(dto.getDbType());
        ds.setJdbcUrl(dto.getJdbcUrl());
        ds.setUsername(dto.getUsername());
        if (StringUtils.hasText(dto.getPassword())) {
            ds.setPassword(encryptPassword(dto.getPassword()));
        }
        ds.setDescription(dto.getDescription());
        ds.setReadOnly(dto.getReadOnly() != null ? dto.getReadOnly() : true);
        ds.setActive(dto.getActive() != null ? dto.getActive() : true);

        // Workaround for PostgreSQL sequence out of sync
        if (ds.getId() == null) {
            List<Object> maxIds = datasourceMapper.selectObjs(
                    new LambdaQueryWrapper<MetaDatasource>()
                            .select(MetaDatasource::getId)
                            .orderByDesc(MetaDatasource::getId)
                            .last("LIMIT 1")
            );
            if (!maxIds.isEmpty() && maxIds.get(0) != null) {
                ds.setId((Integer) maxIds.get(0) + 1);
            }
        }

        datasourceMapper.insert(ds);
        maskPassword(ds);
        return ds;
    }

    /**
     * Update an existing data source.
     */
    @Transactional(transactionManager = "metaTransactionManager")
    public MetaDatasource update(Integer id, DataSourceDTO dto) {
        MetaDatasource existing = datasourceMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Data source not found");
        }

        // Check name uniqueness (excluding self)
        LambdaQueryWrapper<MetaDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaDatasource::getName, dto.getName())
                .ne(MetaDatasource::getId, id);
        if (datasourceMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Data source name already exists");
        }

        existing.setName(dto.getName());
        existing.setDbType(dto.getDbType());
        existing.setJdbcUrl(dto.getJdbcUrl());
        existing.setUsername(dto.getUsername());
        // Only update password if provided
        if (StringUtils.hasText(dto.getPassword())) {
            existing.setPassword(encryptPassword(dto.getPassword()));
        }
        existing.setDescription(dto.getDescription());
        if (dto.getReadOnly() != null) {
            existing.setReadOnly(dto.getReadOnly());
        }
        if (dto.getActive() != null) {
            existing.setActive(dto.getActive());
        }

        datasourceMapper.updateById(existing);
        maskPassword(existing);
        return existing;
    }

    /**
     * Soft delete a data source (set active=false).
     */
    @Transactional(transactionManager = "metaTransactionManager")
    public void delete(Integer id) {
        MetaDatasource existing = datasourceMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Data source not found");
        }
        // Soft delete - set active=false
        existing.setActive(false);
        datasourceMapper.updateById(existing);
    }

    /**
     * Validate that a data source exists and is active.
     */
    public void validateActive(Integer id) {
        MetaDatasource ds = datasourceMapper.selectById(id);
        if (ds == null || !Boolean.TRUE.equals(ds.getActive())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Data source does not exist or is inactive");
        }
    }

    /**
     * Encrypt password using Base64 (simple encoding, not production-grade encryption).
     */
    private String encryptPassword(String password) {
        if (password == null || password.isBlank()) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Password encryption failed", e);
            return password;
        }
    }

    /**
     * Mask password before returning to client.
     */
    private void maskPassword(MetaDatasource ds) {
        ds.setPassword(null);
    }
}
