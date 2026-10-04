package com.text2report.controller;

import com.text2report.common.result.ApiResult;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.model.dto.DataSourceDTO;
import com.text2report.service.DataSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DataSource Controller - handles CRUD operations for data sources.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/datasources")
@RequiredArgsConstructor
public class DataSourceController {

    private final DataSourceService datasourceService;

    /**
     * List all data sources.
     * @param activeOnly if true, return only active data sources
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResult<List<MetaDatasource>> list(@RequestParam(required = false) Boolean activeOnly) {
        List<MetaDatasource> datasources;
        if (Boolean.TRUE.equals(activeOnly)) {
            datasources = datasourceService.listActive();
        } else {
            datasources = datasourceService.listAll();
        }
        return ApiResult.success(datasources);
    }

    /**
     * Get data source by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResult<MetaDatasource> getById(@PathVariable Integer id) {
        return ApiResult.success(datasourceService.getById(id));
    }

    /**
     * Create a new data source.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaDatasource> create(@Valid @RequestBody DataSourceDTO dto) {
        log.info("Creating data source: {}", dto.getName());
        return ApiResult.success(datasourceService.create(dto));
    }

    /**
     * Update an existing data source.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaDatasource> update(@PathVariable Integer id, @Valid @RequestBody DataSourceDTO dto) {
        log.info("Updating data source: {}", id);
        return ApiResult.success(datasourceService.update(id, dto));
    }

    /**
     * Soft delete a data source.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> delete(@PathVariable Integer id) {
        log.info("Deleting data source: {}", id);
        datasourceService.delete(id);
        return ApiResult.success(null);
    }
}
