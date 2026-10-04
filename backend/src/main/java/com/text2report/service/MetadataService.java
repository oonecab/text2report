package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.config.RagConfig;
import com.text2report.domain.meta.entity.*;
import com.text2report.domain.meta.mapper.*;
import com.text2report.model.dto.MetadataContext;
import com.text2report.domain.vector.entity.MetaEmbedding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata Service - retrieves and assembles metadata from PostgreSQL for LLM prompts.
 * Implements Rule 1: LLM only receives metadata JSON, never business data rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataService {

    private final MetaDatasourceMapper metaDatasourceMapper;
    private final MetaTableMapper metaTableMapper;
    private final MetaColumnMapper metaColumnMapper;
    private final MetaGlossaryMapper metaGlossaryMapper;
    private final MetaJoinEdgeMapper metaJoinEdgeMapper;
    private final MetaSqlExampleMapper metaSqlExampleMapper;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final RagConfig ragConfig;
    private final QueryCacheService queryCacheService;
    private final TableRelevanceScorer tableRelevanceScorer;

    private static final Pattern REFERENCED_TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)?)"
    );

    /**
     * Retrieve relevant metadata for a given question and datasource.
     * Searches tables/columns by keyword and assembles full context.
     *
     * @param naturalLanguageQuery the user's question
     * @param datasourceId datasource to search within
     * @return MetadataContext with relevant tables, columns, glossary, joins, examples
     */
    public MetadataContext retrieveForPrompt(String naturalLanguageQuery, Integer datasourceId) {
        if (!StringUtils.hasText(naturalLanguageQuery)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Question cannot be empty");
        }
        if (datasourceId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Datasource ID cannot be null");
        }

        log.info("Retrieving metadata context for question: {}, datasourceId: {}", naturalLanguageQuery, datasourceId);

        String keyword = naturalLanguageQuery.toLowerCase();
        List<String> searchTerms = extractSearchTerms(keyword);
        List<MetaSqlExample> examples = retrieveExamples(keyword, searchTerms);
        List<MetaGlossary> glossary = retrieveGlossary(keyword, searchTerms);
        List<MetaTable> tables = retrieveTables(keyword, searchTerms, datasourceId, glossary, examples, naturalLanguageQuery);
        List<Integer> tableIds = tables.stream().map(MetaTable::getId).toList();

        List<MetaColumn> allColumns = new ArrayList<>();
        for (Integer tableId : tableIds) {
            LambdaQueryWrapper<MetaColumn> colWrapper = new LambdaQueryWrapper<>();
            colWrapper.eq(MetaColumn::getTableId, tableId)
                    .eq(MetaColumn::getActive, true)
                    .orderByAsc(MetaColumn::getSortOrder, MetaColumn::getColumnName);
            allColumns.addAll(metaColumnMapper.selectList(colWrapper));
        }

        List<MetaJoinEdge> joinEdges = tableIds.isEmpty() ? List.of() : metaJoinEdgeMapper.selectList(
                new LambdaQueryWrapper<MetaJoinEdge>()
                        .and(w -> w.in(MetaJoinEdge::getFromTableId, tableIds)
                                .or()
                                .in(MetaJoinEdge::getToTableId, tableIds))
                        .eq(MetaJoinEdge::getActive, true)
        );
        TableRecommendationSignals recommendationSignals = buildTableRecommendationSignals(tables, glossary, examples);

        return MetadataContext.builder()
                .tables(tables)
                .columns(allColumns)
                .glossary(glossary)
                .joinEdges(joinEdges)
                .examples(examples)
                .tableRecommendations(recommendationSignals.recommendations())
                .tableRecommendedBy(recommendationSignals.recommendedBy())
                .build();
    }

    /**
     * Get all active datasources
     */
    public List<MetaDatasource> listActiveDatasources() {
        LambdaQueryWrapper<MetaDatasource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaDatasource::getActive, true);
        return metaDatasourceMapper.selectList(wrapper);
    }

    /**
     * Get all tables for a datasource, including inactive ones so admins can reactivate them.
     */
    public List<MetaTable> listTablesByDatasource(Integer datasourceId) {
        return metaTableMapper.selectListByDatasource(datasourceId);
    }

    /**
     * Get all active columns for a table
     */
    public List<MetaColumn> listColumnsByTable(Integer tableId) {
        LambdaQueryWrapper<MetaColumn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaColumn::getTableId, tableId)
                .eq(MetaColumn::getActive, true)
                .orderByAsc(MetaColumn::getSortOrder, MetaColumn::getColumnName);
        return metaColumnMapper.selectList(wrapper);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaTable updateTable(Integer id, MetaTable table) {
        MetaTable existing = metaTableMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据表不存在");
        }

        existing.setLabel(table.getLabel());
        existing.setDescription(table.getDescription());
        if (table.getActive() != null) {
            existing.setActive(table.getActive());
            if (Boolean.FALSE.equals(table.getActive())) {
                existing.setPublished(false);
            }
        }
        if (table.getPublished() != null) {
            existing.setPublished(Boolean.TRUE.equals(existing.getActive()) && Boolean.TRUE.equals(table.getPublished()));
        }
        metaTableMapper.updateById(existing);
        queryCacheService.invalidateAllQueryCache();
        return existing;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaTable publishTable(Integer id) {
        return setTablePublished(id, true);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaTable unpublishTable(Integer id) {
        return setTablePublished(id, false);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void publishTables(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "表 ID 列表不能为空");
        }
        ids.forEach(this::publishTable);
    }

    private MetaTable setTablePublished(Integer id, boolean published) {
        MetaTable existing = metaTableMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据表不存在");
        }
        existing.setPublished(published);
        metaTableMapper.updateById(existing);
        queryCacheService.invalidateAllQueryCache();
        return existing;
    }

    /**
     * Resolve column labels for a datasource by column name.
     */
    public Map<String, String> getColumnLabels(Integer datasourceId, Collection<String> columnNames) {
        if (datasourceId == null || columnNames == null || columnNames.isEmpty()) {
            return Map.of();
        }

        List<MetaTable> tables = metaTableMapper.selectQueryableTables(datasourceId);
        if (tables.isEmpty()) {
            return Map.of();
        }

        List<Integer> tableIds = tables.stream().map(MetaTable::getId).toList();
        List<String> normalizedColumnNames = columnNames.stream()
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .distinct()
                .toList();
        if (normalizedColumnNames.isEmpty()) {
            return Map.of();
        }

        LambdaQueryWrapper<MetaColumn> columnWrapper = new LambdaQueryWrapper<>();
        columnWrapper.in(MetaColumn::getTableId, tableIds)
                .eq(MetaColumn::getActive, true)
                .in(MetaColumn::getColumnName, normalizedColumnNames);

        return metaColumnMapper.selectList(columnWrapper).stream()
                .filter(column -> StringUtils.hasText(column.getLabel()))
                .collect(Collectors.toMap(
                        column -> column.getColumnName().toLowerCase(),
                        MetaColumn::getLabel,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }

    /**
     * Search glossary by keyword (term or aliases)
     */
    public List<MetaGlossary> searchGlossary(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            LambdaQueryWrapper<MetaGlossary> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MetaGlossary::getActive, true)
                    .isNull(MetaGlossary::getOrgScope)
                    .orderByAsc(MetaGlossary::getTerm);
            return metaGlossaryMapper.selectList(wrapper);
        }
        LambdaQueryWrapper<MetaGlossary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaGlossary::getActive, true)
                .and(w -> w.like(MetaGlossary::getTerm, keyword)
                        .or()
                        .like(MetaGlossary::getAliases, keyword))
                .orderByAsc(MetaGlossary::getTerm)
                .last("LIMIT 20");
        return metaGlossaryMapper.selectList(wrapper);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaColumn updateColumn(Integer id, MetaColumn column) {
        MetaColumn existing = metaColumnMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "字段不存在");
        }

        existing.setLabel(column.getLabel());
        existing.setDescription(column.getDescription());
        if (StringUtils.hasText(column.getCommentSource())) {
            existing.setCommentSource(column.getCommentSource());
        }
        metaColumnMapper.updateById(existing);
        queryCacheService.invalidateAllQueryCache();
        return existing;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaColumn createColumn(Integer tableId, MetaColumn column) {
        MetaTable table = metaTableMapper.selectById(tableId);
        if (table == null || !Boolean.TRUE.equals(table.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据表不存在");
        }
        if (!StringUtils.hasText(column.getColumnName())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字段名不能为空");
        }

        MetaColumn existing = metaColumnMapper.selectOne(new LambdaQueryWrapper<MetaColumn>()
                .eq(MetaColumn::getTableId, tableId)
                .eq(MetaColumn::getColumnName, column.getColumnName()));
        if (existing != null && Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字段已存在: " + column.getColumnName());
        }

        if (existing != null) {
            existing.setDataType(column.getDataType());
            existing.setLabel(column.getLabel());
            existing.setDescription(column.getDescription());
            existing.setCommentSource(StringUtils.hasText(column.getCommentSource()) ? column.getCommentSource() : "manual");
            existing.setActive(true);
            metaColumnMapper.updateById(existing);
            queryCacheService.invalidateAllQueryCache();
            return existing;
        }

        column.setId(null);
        column.setTableId(tableId);
        column.setActive(true);
        column.setCommentSource(StringUtils.hasText(column.getCommentSource()) ? column.getCommentSource() : "manual");
        if (column.getSensitivity() == null) {
            column.setSensitivity("normal");
        }
        if (column.getIsMetric() == null) {
            column.setIsMetric(false);
        }
        metaColumnMapper.insert(column);
        queryCacheService.invalidateAllQueryCache();
        return column;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void deleteColumn(Integer id) {
        MetaColumn existing = metaColumnMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "字段不存在");
        }
        existing.setActive(false);
        metaColumnMapper.updateById(existing);
        queryCacheService.invalidateAllQueryCache();
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaGlossary createGlossary(MetaGlossary glossary) {
        glossary.setId(null);
        if (glossary.getActive() == null) {
            glossary.setActive(true);
        }
        metaGlossaryMapper.insert(glossary);
        embeddingService.embedAndStoreAsync("glossary", glossary.getId(), embeddingService.buildGlossaryText(glossary));
        queryCacheService.invalidateAllQueryCache();
        return glossary;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaGlossary updateGlossary(Integer id, MetaGlossary glossary) {
        MetaGlossary existing = metaGlossaryMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "术语不存在");
        }

        existing.setTerm(glossary.getTerm());
        existing.setDefinition(glossary.getDefinition());
        existing.setSqlSnippet(glossary.getSqlSnippet());
        existing.setAliases(glossary.getAliases());
        existing.setOrgScope(glossary.getOrgScope());
        if (glossary.getActive() != null) {
            existing.setActive(glossary.getActive());
        }
        metaGlossaryMapper.updateById(existing);
        embeddingService.embedAndStoreAsync("glossary", existing.getId(), embeddingService.buildGlossaryText(existing));
        queryCacheService.invalidateAllQueryCache();
        return existing;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void deleteGlossary(Integer id) {
        MetaGlossary existing = metaGlossaryMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "术语不存在");
        }
        existing.setActive(false);
        metaGlossaryMapper.updateById(existing);
        embeddingService.deleteBySource("glossary", existing.getId());
        queryCacheService.invalidateAllQueryCache();
    }

    /**
     * Get JOIN edges for given table names
     */
    public List<MetaJoinEdge> getJoinEdges(List<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) {
            return List.of();
        }

        // First find table IDs
        LambdaQueryWrapper<MetaTable> tableWrapper = new LambdaQueryWrapper<>();
        tableWrapper.in(MetaTable::getTableName, tableNames)
                .eq(MetaTable::getActive, true);
        List<MetaTable> tables = metaTableMapper.selectList(tableWrapper);
        List<Integer> tableIds = tables.stream().map(MetaTable::getId).collect(Collectors.toList());

        if (tableIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<MetaJoinEdge> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(MetaJoinEdge::getFromTableId, tableIds)
                .or()
                .in(MetaJoinEdge::getToTableId, tableIds)
                .eq(MetaJoinEdge::getActive, true);
        return metaJoinEdgeMapper.selectList(wrapper);
    }

    public List<MetaJoinEdge> listJoinEdges() {
        LambdaQueryWrapper<MetaJoinEdge> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaJoinEdge::getActive, true)
                .orderByAsc(MetaJoinEdge::getFromTableId, MetaJoinEdge::getToTableId, MetaJoinEdge::getFromColumn);
        return metaJoinEdgeMapper.selectList(wrapper);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaJoinEdge createJoinEdge(MetaJoinEdge edge) {
        validateJoinEdge(edge);
        edge.setFromColumn(edge.getFromColumn().trim());
        edge.setToColumn(edge.getToColumn().trim());
        edge.setJoinType(StringUtils.hasText(edge.getJoinType()) ? edge.getJoinType().trim().toUpperCase(Locale.ROOT) : "INNER");

        List<MetaJoinEdge> existingEdges = metaJoinEdgeMapper.selectList(new LambdaQueryWrapper<MetaJoinEdge>()
                .and(w -> w.in(MetaJoinEdge::getFromTableId, List.of(edge.getFromTableId(), edge.getToTableId()))
                        .or()
                        .in(MetaJoinEdge::getToTableId, List.of(edge.getFromTableId(), edge.getToTableId()))));
        Optional<MetaJoinEdge> duplicate = existingEdges.stream()
                .filter(existing -> isSameJoinRelation(existing, edge))
                .findFirst();
        if (duplicate.isPresent()) {
            MetaJoinEdge existing = duplicate.get();
            if (Boolean.TRUE.equals(existing.getActive())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "JOIN 关系已存在");
            }
            existing.setJoinType(edge.getJoinType());
            existing.setDescription(edge.getDescription());
            existing.setActive(true);
            metaJoinEdgeMapper.updateById(existing);
            queryCacheService.invalidateAllQueryCache();
            return existing;
        }

        edge.setId(null);
        edge.setActive(true);
        metaJoinEdgeMapper.insert(edge);
        queryCacheService.invalidateAllQueryCache();
        return edge;
    }

    private void validateJoinEdge(MetaJoinEdge edge) {
        if (edge == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "JOIN 关系不能为空");
        }
        if (edge.getFromTableId() == null || edge.getToTableId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "来源表和目标表不能为空");
        }
        if (!StringUtils.hasText(edge.getFromColumn()) || !StringUtils.hasText(edge.getToColumn())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "来源字段和目标字段不能为空");
        }
    }

    private boolean isSameJoinRelation(MetaJoinEdge existing, MetaJoinEdge candidate) {
        if (existing == null || candidate == null) {
            return false;
        }
        boolean sameDirection = Objects.equals(existing.getFromTableId(), candidate.getFromTableId())
                && Objects.equals(existing.getToTableId(), candidate.getToTableId())
                && equalsColumn(existing.getFromColumn(), candidate.getFromColumn())
                && equalsColumn(existing.getToColumn(), candidate.getToColumn());
        boolean reverseDirection = Objects.equals(existing.getFromTableId(), candidate.getToTableId())
                && Objects.equals(existing.getToTableId(), candidate.getFromTableId())
                && equalsColumn(existing.getFromColumn(), candidate.getToColumn())
                && equalsColumn(existing.getToColumn(), candidate.getFromColumn());
        return sameDirection || reverseDirection;
    }

    private boolean equalsColumn(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void deleteJoinEdge(Integer id) {
        MetaJoinEdge existing = metaJoinEdgeMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getActive())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "JOIN 关系不存在");
        }
        existing.setActive(false);
        metaJoinEdgeMapper.updateById(existing);
        queryCacheService.invalidateAllQueryCache();
    }

    /**
     * Get SQL examples by domain
     */
    public List<MetaSqlExample> getExamples(String domain, int limit) {
        LambdaQueryWrapper<MetaSqlExample> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaSqlExample::getActive, true);
        if (StringUtils.hasText(domain)) {
            wrapper.eq(MetaSqlExample::getDomain, domain);
        }
        wrapper.orderByDesc(MetaSqlExample::getUsageCount)
                .last("LIMIT " + Math.min(limit, 50));
        return metaSqlExampleMapper.selectList(wrapper);
    }

    public List<MetaSqlExample> listExamples(Boolean enabledOnly) {
        LambdaQueryWrapper<MetaSqlExample> wrapper = new LambdaQueryWrapper<>();
        if (Boolean.TRUE.equals(enabledOnly)) {
            wrapper.eq(MetaSqlExample::getActive, true);
        }
        wrapper.orderByDesc(MetaSqlExample::getCreatedAt);
        return metaSqlExampleMapper.selectList(wrapper);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaSqlExample createExample(MetaSqlExample example) {
        example.setId(null);
        if (example.getActive() == null) {
            example.setActive(true);
        }
        if (example.getUsageCount() == null) {
            example.setUsageCount(0);
        }
        metaSqlExampleMapper.insert(example);
        embeddingService.embedAndStoreAsync("sql_example", example.getId(), embeddingService.buildSqlExampleText(example));
        queryCacheService.invalidateAllQueryCache();
        return example;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public MetaSqlExample updateExample(Integer id, MetaSqlExample example) {
        MetaSqlExample existing = metaSqlExampleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "示例不存在");
        }

        if (StringUtils.hasText(example.getQuestion())) {
            existing.setQuestion(example.getQuestion());
        }
        if (StringUtils.hasText(example.getSqlQuery())) {
            existing.setSqlQuery(example.getSqlQuery());
        }
        if (example.getDomain() != null) {
            existing.setDomain(example.getDomain());
        }
        if (example.getTimeGrain() != null) {
            existing.setTimeGrain(example.getTimeGrain());
        }
        if (example.getOrgScope() != null) {
            existing.setOrgScope(example.getOrgScope());
        }
        if (example.getUsageCount() != null) {
            existing.setUsageCount(example.getUsageCount());
        }
        if (example.getActive() != null) {
            existing.setActive(example.getActive());
        }
        metaSqlExampleMapper.updateById(existing);
        embeddingService.embedAndStoreAsync("sql_example", existing.getId(), embeddingService.buildSqlExampleText(existing));
        queryCacheService.invalidateAllQueryCache();
        return existing;
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public void deleteExample(Integer id) {
        if (metaSqlExampleMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "示例不存在");
        }
        metaSqlExampleMapper.deleteById(id);
        embeddingService.deleteBySource("sql_example", id);
        queryCacheService.invalidateAllQueryCache();
    }

    private List<MetaSqlExample> retrieveExamples(String keyword, List<String> searchTerms) {
        List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(keyword, "sql_example", ragConfig.getTopKExample());
        List<Integer> vectorIds = vectorMatches.stream()
                .filter(item -> item.getSimilarity() != null && item.getSimilarity() >= ragConfig.getSimilarityThresholdExample())
                .map(MetaEmbedding::getSourceId)
                .toList();
        List<MetaSqlExample> vectorExamples = selectExamplesByOrderedIds(vectorIds);
        if (!vectorExamples.isEmpty()) {
            if (!ragConfig.isFallbackToKeyword()) {
                return vectorExamples;
            }
            return mergeExamples(vectorExamples, searchExamplesByTerms(keyword, searchTerms));
        }
        return searchExamplesByTerms(keyword, searchTerms);
    }

    private List<MetaGlossary> retrieveGlossary(String keyword, List<String> searchTerms) {
        List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(keyword, "glossary", ragConfig.getTopKGlossary());
        List<Integer> vectorIds = vectorMatches.stream()
                .filter(item -> item.getSimilarity() != null && item.getSimilarity() >= ragConfig.getSimilarityThresholdGlossary())
                .map(MetaEmbedding::getSourceId)
                .toList();
        List<MetaGlossary> vectorGlossary = selectGlossaryByOrderedIds(vectorIds);
        if (!vectorGlossary.isEmpty()) {
            if (!ragConfig.isFallbackToKeyword()) {
                return vectorGlossary;
            }
            return mergeGlossary(vectorGlossary, searchGlossaryByTerms(keyword, searchTerms));
        }
        return searchGlossaryByTerms(keyword, searchTerms);
    }

    private List<MetaTable> retrieveTables(String keyword,
                                           List<String> searchTerms,
                                           Integer datasourceId,
                                           List<MetaGlossary> glossary,
                                           List<MetaSqlExample> examples,
                                           String question) {
        LinkedHashSet<Integer> tableIds = new LinkedHashSet<>();
        Map<Integer, Double> vectorSimilarityByTableId = new HashMap<>();
        List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(keyword, "table_description", ragConfig.getTopKTable());
        vectorMatches.stream()
                .filter(item -> item.getSimilarity() != null && item.getSimilarity() >= ragConfig.getSimilarityThresholdTable())
                .forEach(item -> {
                    tableIds.add(item.getSourceId());
                    vectorSimilarityByTableId.put(item.getSourceId(), item.getSimilarity());
                });

        if (ragConfig.isFallbackToKeyword()) {
            searchTablesByTerms(keyword, searchTerms, datasourceId).stream()
                    .map(MetaTable::getId)
                    .forEach(tableIds::add);
        }

        searchTablesByColumnTerms(searchTerms, datasourceId).stream()
                .map(MetaTable::getId)
                .forEach(tableIds::add);

        List<MetaTable> candidates;
        if (tableIds.isEmpty()) {
            LambdaQueryWrapper<MetaTable> fallback = new LambdaQueryWrapper<>();
            fallback.eq(MetaTable::getDatasourceId, datasourceId)
                    .eq(MetaTable::getActive, true)
                    .eq(MetaTable::getPublished, true)
                    .last("LIMIT " + ragConfig.getTopKTable());
            candidates = metaTableMapper.selectList(fallback);
        } else {
            candidates = selectTablesByOrderedIds(tableIds, datasourceId);
        }
        candidates = supplementReferencedTables(candidates, glossary, examples, datasourceId);
        return tableRelevanceScorer.rankTables(candidates, glossary, examples, question, vectorSimilarityByTableId)
                .stream()
                .limit(ragConfig.getTopKTable())
                .toList();
    }

    private List<MetaSqlExample> searchExamplesByKeyword(String keyword) {
        LambdaQueryWrapper<MetaSqlExample> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaSqlExample::getActive, true)
                .and(w -> w.like(MetaSqlExample::getQuestion, keyword)
                        .or()
                        .like(MetaSqlExample::getSqlQuery, keyword))
                .last("LIMIT " + ragConfig.getTopKExample());
        return metaSqlExampleMapper.selectList(wrapper);
    }

    private List<MetaSqlExample> searchExamplesByTerms(String keyword, List<String> searchTerms) {
        LinkedHashMap<Integer, MetaSqlExample> merged = new LinkedHashMap<>();
        for (String term : buildOrderedSearchTerms(keyword, searchTerms)) {
            searchExamplesByKeyword(term).forEach(example -> merged.putIfAbsent(example.getId(), example));
            if (merged.size() >= ragConfig.getTopKExample()) {
                break;
            }
        }
        return merged.values().stream().limit(ragConfig.getTopKExample()).toList();
    }

    private List<MetaTable> searchTablesByKeyword(String keyword, Integer datasourceId) {
        return metaTableMapper.searchByKeyword(keyword, datasourceId, ragConfig.getTopKTable());
    }

    private List<MetaTable> searchTablesByTerms(String keyword, List<String> searchTerms, Integer datasourceId) {
        LinkedHashMap<Integer, MetaTable> merged = new LinkedHashMap<>();
        for (String term : buildOrderedSearchTerms(keyword, searchTerms)) {
            searchTablesByKeyword(term, datasourceId).forEach(table -> merged.putIfAbsent(table.getId(), table));
            if (merged.size() >= ragConfig.getTopKTable()) {
                break;
            }
        }
        return merged.values().stream().limit(ragConfig.getTopKTable()).toList();
    }

    private List<MetaGlossary> searchGlossaryByTerms(String keyword, List<String> searchTerms) {
        LinkedHashMap<Integer, MetaGlossary> merged = new LinkedHashMap<>();
        for (String term : buildOrderedSearchTerms(keyword, searchTerms)) {
            searchGlossary(term).forEach(item -> merged.putIfAbsent(item.getId(), item));
            if (merged.size() >= ragConfig.getTopKGlossary()) {
                break;
            }
        }
        return merged.values().stream().limit(ragConfig.getTopKGlossary()).toList();
    }

    private List<MetaTable> searchTablesByColumnTerms(List<String> searchTerms, Integer datasourceId) {
        if (searchTerms == null || searchTerms.isEmpty()) {
            return List.of();
        }

        List<MetaTable> datasourceTables = metaTableMapper.selectQueryableTables(datasourceId);
        if (datasourceTables.isEmpty()) {
            return List.of();
        }

        List<Integer> tableIds = datasourceTables.stream().map(MetaTable::getId).toList();
        List<MetaColumn> datasourceColumns = metaColumnMapper.selectList(
                new LambdaQueryWrapper<MetaColumn>()
                        .in(MetaColumn::getTableId, tableIds)
                        .eq(MetaColumn::getActive, true)
        );
        if (datasourceColumns.isEmpty()) {
            return List.of();
        }

        Map<Integer, Integer> scores = new HashMap<>();
        for (MetaColumn column : datasourceColumns) {
            int columnScore = scoreColumnAgainstTerms(column, searchTerms);
            if (columnScore > 0) {
                scores.merge(column.getTableId(), columnScore, Integer::sum);
            }
        }

        if (scores.isEmpty()) {
            return List.of();
        }

        Map<Integer, MetaTable> tableById = datasourceTables.stream()
                .collect(Collectors.toMap(MetaTable::getId, table -> table));

        return scores.entrySet().stream()
                .sorted((left, right) -> {
                    int leftScore = left.getValue() + tablePriority(tableById.get(left.getKey())) * 10;
                    int rightScore = right.getValue() + tablePriority(tableById.get(right.getKey())) * 10;
                    int scoreCompare = Integer.compare(rightScore, leftScore);
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    MetaTable rightTable = tableById.get(right.getKey());
                    MetaTable leftTable = tableById.get(left.getKey());
                    int factCompare = Integer.compare(tablePriority(rightTable), tablePriority(leftTable));
                    if (factCompare != 0) {
                        return factCompare;
                    }
                    return Integer.compare(left.getKey(), right.getKey());
                })
                .map(entry -> tableById.get(entry.getKey()))
                .filter(Objects::nonNull)
                .limit(Math.max(ragConfig.getTopKTable(), 5))
                .toList();
    }

    private List<MetaSqlExample> selectExamplesByOrderedIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, MetaSqlExample> byId = metaSqlExampleMapper.selectBatchIds(ids).stream()
                .filter(example -> Boolean.TRUE.equals(example.getActive()))
                .collect(Collectors.toMap(MetaSqlExample::getId, example -> example));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<MetaGlossary> selectGlossaryByOrderedIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, MetaGlossary> byId = metaGlossaryMapper.selectBatchIds(ids).stream()
                .filter(item -> Boolean.TRUE.equals(item.getActive()))
                .collect(Collectors.toMap(MetaGlossary::getId, item -> item));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<MetaTable> selectTablesByOrderedIds(Collection<Integer> ids, Integer datasourceId) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<Integer, MetaTable> byId = metaTableMapper.selectQueryableBatchIds(ids, datasourceId).stream()
                .collect(Collectors.toMap(MetaTable::getId, table -> table));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<MetaSqlExample> mergeExamples(List<MetaSqlExample> primary, List<MetaSqlExample> secondary) {
        LinkedHashMap<Integer, MetaSqlExample> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.getId(), item));
        secondary.forEach(item -> merged.putIfAbsent(item.getId(), item));
        return merged.values().stream().limit(ragConfig.getTopKExample()).toList();
    }

    private List<MetaGlossary> mergeGlossary(List<MetaGlossary> primary, List<MetaGlossary> secondary) {
        LinkedHashMap<Integer, MetaGlossary> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.getId(), item));
        secondary.forEach(item -> merged.putIfAbsent(item.getId(), item));
        return merged.values().stream().limit(ragConfig.getTopKGlossary()).toList();
    }

    private List<MetaTable> supplementReferencedTables(List<MetaTable> candidateTables,
                                                       List<MetaGlossary> glossary,
                                                       List<MetaSqlExample> examples,
                                                       Integer datasourceId) {
        List<MetaTable> queryableTables = metaTableMapper.selectQueryableTables(datasourceId);
        if (queryableTables.isEmpty()) {
            return candidateTables == null ? List.of() : candidateTables;
        }

        Set<String> knownTableNames = queryableTables.stream()
                .map(MetaTable::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> referencedTableNames = new LinkedHashSet<>();
        referencedTableNames.addAll(tableRelevanceScorer.extractReferencedTableNamesFromGlossary(glossary, knownTableNames));
        referencedTableNames.addAll(tableRelevanceScorer.extractReferencedTableNamesFromExamples(examples, knownTableNames));
        if (referencedTableNames.isEmpty()) {
            return candidateTables == null ? List.of() : candidateTables;
        }

        LinkedHashMap<Integer, MetaTable> merged = new LinkedHashMap<>();
        if (candidateTables != null) {
            candidateTables.stream()
                    .filter(table -> table != null && Boolean.TRUE.equals(table.getPublished()))
                    .forEach(table -> merged.putIfAbsent(table.getId(), table));
        }
        Map<String, MetaTable> queryableByName = queryableTables.stream()
                .filter(table -> StringUtils.hasText(table.getTableName()))
                .collect(Collectors.toMap(
                        table -> table.getTableName().toLowerCase(Locale.ROOT),
                        table -> table,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
        referencedTableNames.stream()
                .map(queryableByName::get)
                .filter(Objects::nonNull)
                .forEach(table -> merged.putIfAbsent(table.getId(), table));
        return merged.values().stream().toList();
    }

    private List<MetaTable> augmentAndPrioritizeTablesReferencedByExamples(List<MetaTable> tables,
                                                                           List<MetaSqlExample> examples,
                                                                           Integer datasourceId) {
        if (examples == null || examples.isEmpty()) {
            return tables;
        }

        Set<String> referencedTableNames = extractTableNamesFromExamples(examples);
        if (referencedTableNames.isEmpty()) {
            return tables;
        }

        LinkedHashMap<String, MetaTable> tableByName = new LinkedHashMap<>();
        if (tables != null) {
            tables.forEach(table -> tableByName.put(table.getTableName().toLowerCase(Locale.ROOT), table));
        }

        Set<String> missingTableNames = referencedTableNames.stream()
                .filter(name -> !tableByName.containsKey(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!missingTableNames.isEmpty()) {
            metaTableMapper.selectList(
                    new LambdaQueryWrapper<MetaTable>()
                            .eq(MetaTable::getDatasourceId, datasourceId)
                            .eq(MetaTable::getActive, true)
                            .eq(MetaTable::getPublished, true)
                            .in(MetaTable::getTableName, missingTableNames)
            ).forEach(table -> tableByName.putIfAbsent(table.getTableName().toLowerCase(Locale.ROOT), table));
        }

        List<MetaTable> referencedTables = referencedTableNames.stream()
                .map(tableByName::get)
                .filter(Objects::nonNull)
                .toList();

        if (referencedTables.isEmpty()) {
            return tables;
        }

        List<MetaTable> orderedSource = tableByName.values().stream().toList();
        List<MetaTable> remainingTables = orderedSource.stream()
                .filter(table -> !referencedTableNames.contains(table.getTableName().toLowerCase(Locale.ROOT)))
                .toList();

        List<MetaTable> ordered = new ArrayList<>(referencedTables.size() + remainingTables.size());
        ordered.addAll(referencedTables);
        ordered.addAll(remainingTables);
        return ordered;
    }

    private List<String> buildOrderedSearchTerms(String keyword, List<String> searchTerms) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (StringUtils.hasText(keyword)) {
            ordered.add(keyword.trim());
        }
        if (searchTerms != null) {
            searchTerms.stream().filter(StringUtils::hasText).map(String::trim).forEach(ordered::add);
        }
        return ordered.stream().toList();
    }

    private List<String> extractSearchTerms(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }

        String normalized = keyword.toLowerCase(Locale.ROOT)
                .replace('。', ' ')
                .replace('，', ' ')
                .replace('、', ' ')
                .replace(',', ' ')
                .replace('；', ' ')
                .replace(';', ' ')
                .trim();

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        List<String> curatedTerms = List.of(
                "毛利率", "净毛利", "毛利额", "毛利", "gross_profit", "gross_margin",
                "销售收入", "收入", "revenue", "销售成本", "成本", "cost",
                "产品名称", "产品", "product_name", "product_id", "product",
                "渠道", "channel", "组织", "部门", "子公司", "母公司", "org_id",
                "月份", "月度", "季度", "年度", "日期", "data_date", "year", "month", "quarter",
                "排名", "top", "前5", "前10"
        );
        for (String term : curatedTerms) {
            if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
                terms.add(term);
            }
        }

        Matcher englishMatcher = Pattern.compile("\\b[a-z][a-z0-9_]{1,}\\b").matcher(normalized);
        while (englishMatcher.find()) {
            terms.add(englishMatcher.group());
        }

        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2 && part.length() <= 12) {
                terms.add(part);
            }
        }

        return terms.stream().toList();
    }

    private int scoreColumnAgainstTerms(MetaColumn column, List<String> searchTerms) {
        int score = 0;
        String columnName = safeLower(column.getColumnName());
        String label = safeLower(column.getLabel());
        String description = safeLower(column.getDescription());

        for (String term : searchTerms) {
            String normalizedTerm = safeLower(term);
            if (!StringUtils.hasText(normalizedTerm)) {
                continue;
            }
            if (columnName.contains(normalizedTerm) || normalizedTerm.contains(columnName)) {
                score += 5;
            }
            if (StringUtils.hasText(label) && (label.contains(normalizedTerm) || normalizedTerm.contains(label))) {
                score += 3;
            }
            if (StringUtils.hasText(description) && description.contains(normalizedTerm)) {
                score += 2;
            }
        }
        return score;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private int tablePriority(MetaTable table) {
        if (table == null) {
            return 0;
        }
        if ("fact".equalsIgnoreCase(table.getTableType()) || safeLower(table.getTableName()).startsWith("fact_")) {
            return 2;
        }
        if ("dimension".equalsIgnoreCase(table.getTableType()) || "dim".equalsIgnoreCase(table.getTableType())
                || safeLower(table.getTableName()).startsWith("dim_")) {
            return 1;
        }
        return 0;
    }

    private Set<String> extractTableNamesFromExamples(List<MetaSqlExample> examples) {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        for (MetaSqlExample example : examples) {
            if (!StringUtils.hasText(example.getSqlQuery())) {
                continue;
            }
            Matcher matcher = REFERENCED_TABLE_PATTERN.matcher(example.getSqlQuery());
            while (matcher.find()) {
                tableNames.add(simpleTableName(matcher.group(1)));
            }
        }
        return tableNames;
    }

    private TableRecommendationSignals buildTableRecommendationSignals(List<MetaTable> tables,
                                                                       List<MetaGlossary> glossary,
                                                                       List<MetaSqlExample> examples) {
        if (tables == null || tables.isEmpty()) {
            return new TableRecommendationSignals(Map.of(), Map.of());
        }

        Set<String> knownTableNames = tables.stream()
                .map(MetaTable::getTableName)
                .filter(StringUtils::hasText)
                .map(this::simpleTableName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> glossaryStrongRefs = new LinkedHashSet<>();
        Set<String> exampleRefs = new LinkedHashSet<>();
        Map<String, List<String>> recommendedBy = new LinkedHashMap<>();

        for (MetaGlossary item : glossary == null ? List.<MetaGlossary>of() : glossary) {
            String glossaryText = String.join("\n",
                    nullToEmpty(item.getSqlSnippet()),
                    nullToEmpty(item.getDefinition()),
                    nullToEmpty(item.getAliases()));
            glossaryStrongRefs.addAll(extractKnownTableNames(glossaryText, knownTableNames));

            String source = "glossary:" + (StringUtils.hasText(item.getTerm()) ? item.getTerm().trim() : "未命名术语");
            for (String tableName : extractKnownTableNames(item.getSqlSnippet(), knownTableNames)) {
                recommendedBy.computeIfAbsent(tableName, key -> new ArrayList<>()).add(source);
            }
        }

        for (MetaSqlExample example : examples == null ? List.<MetaSqlExample>of() : examples) {
            Set<String> refs = extractKnownTableNames(example.getSqlQuery(), knownTableNames);
            exampleRefs.addAll(refs);
            String source = "example:" + shortenForRecommendationSource(example.getQuestion());
            for (String tableName : refs) {
                recommendedBy.computeIfAbsent(tableName, key -> new ArrayList<>()).add(source);
            }
        }

        Map<String, String> recommendations = new LinkedHashMap<>();
        for (int index = 0; index < tables.size(); index++) {
            MetaTable table = tables.get(index);
            if (table == null || !StringUtils.hasText(table.getTableName())) {
                continue;
            }
            String normalizedName = simpleTableName(table.getTableName());
            boolean glossaryReferenced = glossaryStrongRefs.contains(normalizedName);
            boolean exampleReferenced = exampleRefs.contains(normalizedName);
            boolean factTable = isFactTable(table);
            boolean dimTable = isDimTable(table);

            String recommendation = "normal";
            if (glossaryReferenced || (exampleReferenced && factTable)) {
                recommendation = "primary";
            } else if ((exampleReferenced && dimTable) || (index < 3 && !glossaryReferenced && !exampleReferenced)) {
                recommendation = "secondary";
            }

            recommendations.put(table.getTableName(), recommendation);
        }

        Map<String, List<String>> recommendedByByActualName = new LinkedHashMap<>();
        for (MetaTable table : tables) {
            if (table == null || !StringUtils.hasText(table.getTableName())) {
                continue;
            }
            List<String> sources = recommendedBy.getOrDefault(simpleTableName(table.getTableName()), List.of());
            recommendedByByActualName.put(table.getTableName(), sources.stream().distinct().toList());
        }

        return new TableRecommendationSignals(recommendations, recommendedByByActualName);
    }

    private Set<String> extractKnownTableNames(String text, Collection<String> knownTableNames) {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        if (!StringUtils.hasText(text) || knownTableNames == null || knownTableNames.isEmpty()) {
            return tableNames;
        }

        String normalizedText = text.toLowerCase(Locale.ROOT);
        Matcher matcher = REFERENCED_TABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            String tableName = simpleTableName(matcher.group(1));
            if (knownTableNames.contains(tableName)) {
                tableNames.add(tableName);
            }
        }
        for (String knownTableName : knownTableNames) {
            if (Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(knownTableName) + "(?![a-zA-Z0-9_])")
                    .matcher(normalizedText)
                    .find()) {
                tableNames.add(knownTableName);
            }
        }
        return tableNames;
    }

    private String simpleTableName(String tableName) {
        String normalized = tableName == null ? "" : tableName.toLowerCase(Locale.ROOT).trim();
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex >= 0 ? normalized.substring(dotIndex + 1) : normalized;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String shortenForRecommendationSource(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "未命名示例";
        return normalized.length() <= 15 ? normalized : normalized.substring(0, 15);
    }

    private boolean isFactTable(MetaTable table) {
        return "fact".equalsIgnoreCase(table.getTableType())
                || simpleTableName(table.getTableName()).startsWith("fact_");
    }

    private boolean isDimTable(MetaTable table) {
        String type = table.getTableType();
        String name = simpleTableName(table.getTableName());
        return "dimension".equalsIgnoreCase(type)
                || "dim".equalsIgnoreCase(type)
                || name.startsWith("dim_");
    }

    private record TableRecommendationSignals(Map<String, String> recommendations,
                                              Map<String, List<String>> recommendedBy) {
    }

    private String lookupTableRecommendation(Map<String, String> recommendations, String tableName) {
        if (recommendations == null || !StringUtils.hasText(tableName)) {
            return "normal";
        }
        String exact = recommendations.get(tableName);
        if (StringUtils.hasText(exact)) {
            return exact;
        }
        String normalized = simpleTableName(tableName);
        return recommendations.entrySet().stream()
                .filter(entry -> simpleTableName(entry.getKey()).equals(normalized))
                .map(Map.Entry::getValue)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("normal");
    }

    private List<String> lookupTableRecommendedBy(Map<String, List<String>> recommendedBy, String tableName) {
        if (recommendedBy == null || !StringUtils.hasText(tableName)) {
            return List.of();
        }
        List<String> exact = recommendedBy.get(tableName);
        if (exact != null) {
            return exact;
        }
        String normalized = simpleTableName(tableName);
        return recommendedBy.entrySet().stream()
                .filter(entry -> simpleTableName(entry.getKey()).equals(normalized))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(List.of());
    }

    /**
     * Build metadata JSON string for LLM consumption.
     * Uses Jackson serialization with exact structure defined in spec.
     */
    public String buildMetadataJson(MetadataContext context) {
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("dialect", "postgresql");

            // Build tables structure
            List<Map<String, Object>> tablesList = new ArrayList<>();
            if (context.getTables() != null) {
                // Group columns by table
                Map<Integer, List<MetaColumn>> columnsByTable = new HashMap<>();
                if (context.getColumns() != null) {
                    for (MetaColumn col : context.getColumns()) {
                        columnsByTable.computeIfAbsent(col.getTableId(), k -> new ArrayList<>()).add(col);
                    }
                }
                Map<String, String> tableRecommendations = context.getTableRecommendations() == null
                        ? Map.of()
                        : context.getTableRecommendations();
                Map<String, List<String>> tableRecommendedBy = context.getTableRecommendedBy() == null
                        ? Map.of()
                        : context.getTableRecommendedBy();

                for (MetaTable table : context.getTables()) {
                    String tableName = table.getTableName();
                    Map<String, Object> tableMap = new LinkedHashMap<>();
                    tableMap.put("name", tableName);
                    tableMap.put("table_name", tableName);
                    tableMap.put("label", table.getLabel() != null ? table.getLabel() : tableName);
                    tableMap.put("schema", table.getSchemaName() != null ? table.getSchemaName() : "public");
                    if (table.getDescription() != null) {
                        tableMap.put("description", table.getDescription());
                    }
                    tableMap.put("recommendation", lookupTableRecommendation(tableRecommendations, tableName));
                    tableMap.put("recommended_by", lookupTableRecommendedBy(tableRecommendedBy, tableName));

                    List<Map<String, Object>> columnsList = new ArrayList<>();
                    List<MetaColumn> cols = columnsByTable.getOrDefault(table.getId(), List.of());
                    for (MetaColumn col : cols) {
                        Map<String, Object> colMap = new LinkedHashMap<>();
                        colMap.put("name", col.getColumnName());
                        colMap.put("label", col.getLabel() != null ? col.getLabel() : col.getColumnName());
                        colMap.put("type", col.getDataType() != null ? col.getDataType() : "unknown");
                        if (col.getDescription() != null) {
                            colMap.put("description", col.getDescription());
                        }
                        columnsList.add(colMap);
                    }
                    tableMap.put("columns", columnsList);
                    tablesList.add(tableMap);
                }
            }
            output.put("tables", tablesList);

            // Build glossary
            List<Map<String, Object>> glossaryList = new ArrayList<>();
            if (context.getGlossary() != null) {
                for (MetaGlossary g : context.getGlossary()) {
                    Map<String, Object> gMap = new LinkedHashMap<>();
                    gMap.put("term", g.getTerm());
                    if (g.getDefinition() != null) {
                        gMap.put("definition", g.getDefinition());
                    }
                    if (g.getSqlSnippet() != null) {
                        gMap.put("sql_snippet", g.getSqlSnippet());
                    }
                    glossaryList.add(gMap);
                }
            }
            output.put("glossary", glossaryList);

            // Build joins
            List<Map<String, Object>> joinsList = new ArrayList<>();
            if (context.getJoinEdges() != null && context.getTables() != null) {
                Map<Integer, MetaTable> tableMapById = new HashMap<>();
                for (MetaTable t : context.getTables()) {
                    tableMapById.put(t.getId(), t);
                }
                for (MetaJoinEdge edge : context.getJoinEdges()) {
                    MetaTable fromTable = tableMapById.get(edge.getFromTableId());
                    MetaTable toTable = tableMapById.get(edge.getToTableId());
                    if (fromTable != null && toTable != null) {
                        Map<String, Object> joinMap = new LinkedHashMap<>();
                        joinMap.put("from", fromTable.getTableName() + "." + edge.getFromColumn());
                        joinMap.put("to", toTable.getTableName() + "." + edge.getToColumn());
                        joinMap.put("type", edge.getJoinType() != null ? edge.getJoinType().toUpperCase() : "INNER");
                        if (edge.getDescription() != null) {
                            joinMap.put("description", edge.getDescription());
                        }
                        joinsList.add(joinMap);
                    }
                }
            }
            output.put("joins", joinsList);

            // Build examples
            List<Map<String, Object>> examplesList = new ArrayList<>();
            if (context.getExamples() != null) {
                for (MetaSqlExample ex : context.getExamples()) {
                    Map<String, Object> exMap = new LinkedHashMap<>();
                    exMap.put("question", ex.getQuestion());
                    exMap.put("sql", ex.getSqlQuery());
                    examplesList.add(exMap);
                }
            }
            output.put("examples", examplesList);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            log.error("Failed to build metadata JSON: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.METADATA_ERROR, "Failed to build metadata JSON: " + e.getMessage());
        }
    }

    // ============ Backward-compatible wrapper methods for existing callers ============

    /**
     * Backward-compatible: get metadata context as JSON string for a question.
     * Uses datasourceId=1 as default since orgId-based lookup requires additional logic.
     */
    public String getMetadataContext(String question, Integer orgId) {
        // Use default datasource (first active one) or orgId to find datasource
        List<MetaDatasource> datasources = listActiveDatasources();
        if (datasources.isEmpty()) {
            return buildMetadataJson(MetadataContext.builder().build());
        }
        Integer datasourceId = datasources.get(0).getId();
        MetadataContext ctx = retrieveForPrompt(question, datasourceId);
        return buildMetadataJson(ctx);
    }

    /**
     * Backward-compatible: get tables metadata for a datasource
     */
    public Object getTablesMetadata(Integer datasourceId) {
        if (datasourceId == null) {
            List<MetaDatasource> datasources = listActiveDatasources();
            if (datasources.isEmpty()) {
                return List.of();
            }
            datasourceId = datasources.get(0).getId();
        }
        return listTablesByDatasource(datasourceId);
    }

    /**
     * Backward-compatible: get columns metadata for a table
     */
    public Object getColumnsMetadata(Integer tableId) {
        return listColumnsByTable(tableId);
    }

    /**
     * Backward-compatible: get glossary (ignores orgId, returns all)
     */
    public Object getGlossary(Integer orgId) {
        return searchGlossary(null);
    }

    /**
     * Backward-compatible: get SQL examples (ignores timeGrain and orgScope)
     */
    public Object getSqlExamples(String domain, String timeGrain, Integer orgScope) {
        return getExamples(domain, 10);
    }
}
