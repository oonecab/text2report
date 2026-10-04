package com.text2report.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Immutable-ish query planning output shared by downstream workflows.
 */
@Data
@Builder
public class QueryPlan {

    private String intent;
    private Integer datasourceId;
    private List<Integer> orgScopeIds;
    private String orgPath;
    private MetadataContext metadataContext;
    private String metadataJson;
}
