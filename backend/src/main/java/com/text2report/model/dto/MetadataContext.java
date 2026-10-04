package com.text2report.model.dto;

import com.text2report.domain.meta.entity.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Metadata context DTO for LLM prompt assembly.
 * Contains only schema metadata, never actual business data rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetadataContext {

    private List<MetaTable> tables;

    private List<MetaColumn> columns;

    private List<MetaGlossary> glossary;

    private List<MetaJoinEdge> joinEdges;

    private List<MetaSqlExample> examples;

    /**
     * tableName -> primary / secondary / normal.
     */
    private Map<String, String> tableRecommendations;

    /**
     * tableName -> recommendation sources, e.g. glossary:毛利, example:Q1毛利分析.
     */
    private Map<String, List<String>> tableRecommendedBy;
}
