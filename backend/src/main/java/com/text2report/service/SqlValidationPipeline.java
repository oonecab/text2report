package com.text2report.service;

import com.text2report.model.dto.QueryPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Single SQL validation boundary before read-only execution.
 */
@Service
@RequiredArgsConstructor
public class SqlValidationPipeline {

    private final SqlGuardService sqlGuardService;
    private final SqlMetadataValidator sqlMetadataValidator;

    public String validate(String sql, QueryPlan plan) {
        String validatedSql = sqlGuardService.validate(sql);
        String scopedSql = sqlGuardService.enforceOrgScope(validatedSql, plan.getOrgScopeIds());
        String orgValidatedSql = sqlGuardService.validateOrgScope(scopedSql, plan.getOrgScopeIds());
        sqlMetadataValidator.validateTableAndColumnExistence(orgValidatedSql, plan.getDatasourceId());
        return orgValidatedSql;
    }
}
