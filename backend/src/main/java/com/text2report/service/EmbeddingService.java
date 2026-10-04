package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.text2report.config.LLMConfig;
import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaGlossary;
import com.text2report.domain.meta.entity.MetaSqlExample;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import com.text2report.domain.meta.mapper.MetaGlossaryMapper;
import com.text2report.domain.meta.mapper.MetaSqlExampleMapper;
import com.text2report.domain.meta.mapper.MetaTableMapper;
import com.text2report.domain.vector.entity.MetaEmbedding;
import com.text2report.domain.vector.mapper.MetaEmbeddingMapper;
import com.text2report.model.dto.EmbeddingSyncResult;
import com.text2report.service.llm.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG embedding service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final LLMService llmService;
    private final MetaEmbeddingMapper embeddingMapper;
    private final MetaSqlExampleMapper sqlExampleMapper;
    private final MetaGlossaryMapper glossaryMapper;
    private final MetaTableMapper metaTableMapper;
    private final MetaColumnMapper metaColumnMapper;
    private final LLMConfig llmConfig;
    private final QueryCacheService queryCacheService;

    public void embedAndStore(String sourceType, Integer sourceId, String contentText) {
        try {
            float[] vector = llmService.generateEmbedding(contentText);
            embeddingMapper.upsertEmbedding(sourceType, sourceId, contentText, vector, llmConfig.getEmbeddingModel());
        } catch (Exception e) {
            log.error("Embedding failed for {}:{}", sourceType, sourceId, e);
        }
    }

    @Async("llmExecutor")
    public void embedAndStoreAsync(String sourceType, Integer sourceId, String contentText) {
        embedAndStore(sourceType, sourceId, contentText);
    }

    public List<MetaEmbedding> searchSimilar(String query, String sourceType, int topK) {
        try {
            float[] queryVector = llmService.generateEmbedding(query);
            return embeddingMapper.searchSimilar(queryVector, topK, sourceType);
        } catch (Exception e) {
            log.warn("Vector search failed for sourceType={}", sourceType, e);
            return Collections.emptyList();
        }
    }

    public EmbeddingSyncResult syncAll() {
        EmbeddingSyncResult result = new EmbeddingSyncResult(syncAllExamples(), syncAllGlossary(), syncAllTableDescriptions());
        queryCacheService.invalidateAllQueryCache();
        return result;
    }

    public void deleteBySource(String sourceType, Integer sourceId) {
        embeddingMapper.deleteBySource(sourceType, sourceId);
    }

    public Map<String, Long> getStats() {
        List<Map<String, Object>> rows = embeddingMapper.countByType();
        Map<String, Long> result = new HashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            String sourceType = (String) row.get("source_type");
            Long count = ((Number) row.get("cnt")).longValue();
            result.put(sourceType, count);
            total += count;
        }
        result.put("total", total);
        return result;
    }

    public String buildSqlExampleText(MetaSqlExample example) {
        return "问题: " + example.getQuestion() + "\nSQL: " + example.getSqlQuery();
    }

    public String buildGlossaryText(MetaGlossary glossary) {
        StringBuilder builder = new StringBuilder()
                .append("术语: ").append(glossary.getTerm())
                .append("\n定义: ").append(glossary.getDefinition() == null ? "" : glossary.getDefinition());
        if (glossary.getSqlSnippet() != null) {
            builder.append("\nSQL: ").append(glossary.getSqlSnippet());
        }
        return builder.toString();
    }

    public String buildTableDescriptionText(MetaTable table, List<MetaColumn> columns) {
        StringBuilder builder = new StringBuilder()
                .append("表: ").append(table.getTableName());
        if (table.getLabel() != null) {
            builder.append(" (").append(table.getLabel()).append(")");
        }
        builder.append("\n说明: ").append(table.getDescription() == null ? "无" : table.getDescription());
        builder.append("\n字段: ");
        for (int i = 0; i < columns.size(); i++) {
            MetaColumn column = columns.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(column.getColumnName());
            if (column.getLabel() != null) {
                builder.append("(").append(column.getLabel()).append(")");
            }
        }
        return builder.toString();
    }

    private int syncAllExamples() {
        LambdaQueryWrapper<MetaSqlExample> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaSqlExample::getActive, true);
        List<MetaSqlExample> examples = sqlExampleMapper.selectList(wrapper);
        for (MetaSqlExample example : examples) {
            embedAndStore("sql_example", example.getId(), buildSqlExampleText(example));
        }
        return examples.size();
    }

    private int syncAllGlossary() {
        LambdaQueryWrapper<MetaGlossary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaGlossary::getActive, true);
        List<MetaGlossary> glossaryList = glossaryMapper.selectList(wrapper);
        for (MetaGlossary glossary : glossaryList) {
            embedAndStore("glossary", glossary.getId(), buildGlossaryText(glossary));
        }
        return glossaryList.size();
    }

    private int syncAllTableDescriptions() {
        List<MetaTable> tables = metaTableMapper.selectActiveList(null);
        for (MetaTable table : tables) {
            LambdaQueryWrapper<MetaColumn> columnWrapper = new LambdaQueryWrapper<>();
            columnWrapper.eq(MetaColumn::getTableId, table.getId())
                    .eq(MetaColumn::getActive, true)
                    .orderByAsc(MetaColumn::getSortOrder, MetaColumn::getColumnName);
            List<MetaColumn> columns = metaColumnMapper.selectList(columnWrapper);
            embedAndStore("table_description", table.getId(), buildTableDescriptionText(table, columns));
        }
        return tables.size();
    }
}
