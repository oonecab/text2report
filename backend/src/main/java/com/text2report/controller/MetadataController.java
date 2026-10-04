package com.text2report.controller;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.common.result.ApiResult;
import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.domain.meta.entity.MetaGlossary;
import com.text2report.domain.meta.entity.MetaJoinEdge;
import com.text2report.domain.meta.entity.MetaSqlExample;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaDatasourceMapper;
import com.text2report.model.dto.DDLConfirmRequest;
import com.text2report.model.dto.DDLParseRequest;
import com.text2report.model.dto.MetadataContext;
import com.text2report.service.DDLParserService;
import com.text2report.service.MetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

/**
 * Metadata Controller - handles metadata management (DDL upload, glossary, org tree)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataService metadataService;
    private final DDLParserService ddlParserService;
    private final MetaDatasourceMapper metaDatasourceMapper;

    /**
     * Parse DDL and preview metadata (no DB write)
     */
    @PostMapping("/ddl/parse")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<DDLParserService.DDLParseResult> parseDDL(@Valid @RequestBody DDLParseRequest request,
                                                               Authentication authentication) {
        log.info("Parsing DDL, datasourceId: {}, length: {}",
                request.getDatasourceId(),
                request.getRawDdl() != null ? request.getRawDdl().length() : 0);
        DDLParserService.DDLParseResult result = ddlParserService.parseDDL(
                request.getRawDdl(),
                request.getDatasourceId(),
                authentication.getName()
        );
        return ApiResult.success(result);
    }

    /**
     * Confirm and save parsed DDL to metadata tables
     */
    @PostMapping("/ddl/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> confirmDDL(@Valid @RequestBody DDLConfirmRequest request, Authentication authentication) {
        log.info("Confirming DDL commit, uploadId: {}, datasourceId: {}",
                request.getUploadId(), request.getDatasourceId());

        // Validate datasource exists and is active
        if (request.getDatasourceId() != null) {
            MetaDatasource ds = metaDatasourceMapper.selectById(request.getDatasourceId());
            if (ds == null || !Boolean.TRUE.equals(ds.getActive())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "选中的数据源不存在或已停用");
            }
        }

        ddlParserService.confirmAndSave(request, authentication.getName());
        return ApiResult.success(null);
    }

    /**
     * Get metadata context for a question
     */
    @GetMapping("/context")
    public ApiResult<String> getContext(
            @RequestParam String question,
            @RequestParam(required = false) Integer orgId) {
        log.info("Getting metadata context for: {}", question);
        String context = metadataService.getMetadataContext(question, orgId);
        return ApiResult.success(context);
    }

    /**
     * Get all tables for a datasource
     */
    @GetMapping("/tables")
    public ApiResult<List<MetaTable>> getTables(@RequestParam(required = false) Integer datasourceId) {
        List<MetaTable> tables = metadataService.listTablesByDatasource(
                datasourceId != null ? datasourceId : 1);
        return ApiResult.success(tables);
    }

    /**
     * Get columns for a table
     */
    @GetMapping("/tables/{tableId}/columns")
    public ApiResult<List<MetaColumn>> getColumns(@PathVariable Integer tableId) {
        List<MetaColumn> columns = metadataService.listColumnsByTable(tableId);
        return ApiResult.success(columns);
    }

    /**
     * Update a table
     */
    @PutMapping("/tables/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaTable> updateTable(@PathVariable Integer id, @RequestBody MetaTable table) {
        log.info("Updating table: {}", id);
        return ApiResult.success(metadataService.updateTable(id, table));
    }

    @PutMapping("/tables/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaTable> publishTable(@PathVariable Integer id) {
        log.info("Publishing table: {}", id);
        return ApiResult.success(metadataService.publishTable(id));
    }

    @PutMapping("/tables/{id}/unpublish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaTable> unpublishTable(@PathVariable Integer id) {
        log.info("Unpublishing table: {}", id);
        return ApiResult.success(metadataService.unpublishTable(id));
    }

    @PutMapping("/tables/batch-publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> batchPublishTables(@RequestBody Collection<Integer> ids) {
        log.info("Batch publishing tables: {}", ids);
        metadataService.publishTables(ids);
        return ApiResult.success(null);
    }

    /**
     * Update a column
     */
    @PutMapping("/columns/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaColumn> updateColumn(@PathVariable Integer id, @RequestBody MetaColumn column) {
        log.info("Updating column: {}", id);
        return ApiResult.success(metadataService.updateColumn(id, column));
    }

    @PostMapping("/tables/{tableId}/columns")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaColumn> createColumn(@PathVariable Integer tableId, @RequestBody MetaColumn column) {
        log.info("Creating column for table: {}", tableId);
        return ApiResult.success(metadataService.createColumn(tableId, column));
    }

    @DeleteMapping("/columns/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> deleteColumn(@PathVariable Integer id) {
        log.info("Deleting column: {}", id);
        metadataService.deleteColumn(id);
        return ApiResult.success(null);
    }

    /**
     * Search glossary by keyword
     */
    @GetMapping("/glossary")
    public ApiResult<List<MetaGlossary>> getGlossary(@RequestParam(required = false) String keyword) {
        List<MetaGlossary> glossary = metadataService.searchGlossary(keyword);
        return ApiResult.success(glossary);
    }

    /**
     * Create or update glossary term
     */
    @PostMapping("/glossary")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaGlossary> createGlossary(@RequestBody MetaGlossary glossary) {
        log.info("Creating glossary term: {}", glossary.getTerm());
        return ApiResult.success(metadataService.createGlossary(glossary));
    }

    @PutMapping("/glossary/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaGlossary> updateGlossary(@PathVariable Integer id, @RequestBody MetaGlossary glossary) {
        log.info("Updating glossary term: {}", id);
        return ApiResult.success(metadataService.updateGlossary(id, glossary));
    }

    /**
     * Delete glossary term
     */
    @DeleteMapping("/glossary/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> deleteGlossary(@PathVariable Integer id) {
        log.info("Deleting glossary term: {}", id);
        metadataService.deleteGlossary(id);
        return ApiResult.success(null);
    }

    @GetMapping("/joins")
    public ApiResult<List<MetaJoinEdge>> getJoins() {
        return ApiResult.success(metadataService.listJoinEdges());
    }

    @PostMapping("/joins")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaJoinEdge> createJoin(@RequestBody MetaJoinEdge edge) {
        return ApiResult.success(metadataService.createJoinEdge(edge));
    }

    @DeleteMapping("/joins/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> deleteJoin(@PathVariable Integer id) {
        metadataService.deleteJoinEdge(id);
        return ApiResult.success(null);
    }

    @GetMapping("/examples")
    public ApiResult<List<MetaSqlExample>> getExamples(@RequestParam(required = false) Boolean enabled) {
        return ApiResult.success(metadataService.listExamples(enabled));
    }

    @PostMapping("/examples")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaSqlExample> createExample(@RequestBody MetaSqlExample example) {
        return ApiResult.success(metadataService.createExample(example));
    }

    @PutMapping("/examples/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<MetaSqlExample> updateExample(@PathVariable Integer id, @RequestBody MetaSqlExample example) {
        return ApiResult.success(metadataService.updateExample(id, example));
    }

    @DeleteMapping("/examples/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResult<Void> deleteExample(@PathVariable Integer id) {
        metadataService.deleteExample(id);
        return ApiResult.success(null);
    }
}
