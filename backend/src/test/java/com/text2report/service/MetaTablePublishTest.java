package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.config.RagConfig;
import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import com.text2report.domain.meta.mapper.MetaDatasourceMapper;
import com.text2report.domain.meta.mapper.MetaDdlUploadMapper;
import com.text2report.domain.meta.mapper.MetaGlossaryMapper;
import com.text2report.domain.meta.mapper.MetaJoinEdgeMapper;
import com.text2report.domain.meta.mapper.MetaSqlExampleMapper;
import com.text2report.domain.meta.mapper.MetaTableMapper;
import com.text2report.model.dto.DDLConfirmRequest;
import com.text2report.model.dto.MetadataContext;
import com.text2report.domain.vector.entity.MetaEmbedding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetaTablePublishTest {

    @Mock
    private MetaDatasourceMapper metaDatasourceMapper;
    @Mock
    private MetaTableMapper metaTableMapper;
    @Mock
    private MetaColumnMapper metaColumnMapper;
    @Mock
    private MetaGlossaryMapper metaGlossaryMapper;
    @Mock
    private MetaJoinEdgeMapper metaJoinEdgeMapper;
    @Mock
    private MetaSqlExampleMapper metaSqlExampleMapper;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private RagConfig ragConfig;
    @Mock
    private QueryCacheService queryCacheService;
    @Mock
    private MetaDdlUploadMapper metaDdlUploadMapper;
    @Mock
    private TableRelevanceScorer tableRelevanceScorer;

    @InjectMocks
    private MetadataService metadataService;
    @InjectMocks
    private DDLParserService ddlParserService;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        var metadataObjectMapper = MetadataService.class.getDeclaredField("objectMapper");
        metadataObjectMapper.setAccessible(true);
        metadataObjectMapper.set(metadataService, objectMapper);

        var ddlObjectMapper = DDLParserService.class.getDeclaredField("objectMapper");
        ddlObjectMapper.setAccessible(true);
        ddlObjectMapper.set(ddlParserService, objectMapper);

        lenient().when(tableRelevanceScorer.rankTables(any(List.class), any(List.class), any(List.class), anyString(), any(Map.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(tableRelevanceScorer.extractReferencedTableNamesFromGlossary(any(List.class), any(Collection.class)))
                .thenReturn(java.util.Set.of());
        lenient().when(tableRelevanceScorer.extractReferencedTableNamesFromExamples(any(List.class), any(Collection.class)))
                .thenReturn(java.util.Set.of());
    }

    @Test
    @DisplayName("DDL 新导入表 published 默认为 false")
    void newDdlImportedTableDefaultsToUnpublished() {
        DDLConfirmRequest.ParsedColumn parsedColumn = new DDLConfirmRequest.ParsedColumn();
        parsedColumn.setColumnName("id");
        parsedColumn.setDataType("integer");
        parsedColumn.setLabel("ID");
        parsedColumn.setCommentSource("ddl");

        DDLConfirmRequest.ParsedTable parsedTable = new DDLConfirmRequest.ParsedTable();
        parsedTable.setTableName("fact_test_import");
        parsedTable.setSuggestedLabel("测试导入表");
        parsedTable.setColumns(List.of(parsedColumn));

        DDLConfirmRequest request = new DDLConfirmRequest();
        request.setUploadId("upload-1");
        request.setDatasourceId(1);
        request.setTables(List.of(parsedTable));

        when(metaTableMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(metaColumnMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ddlParserService.confirmAndSave(request, "admin");

        ArgumentCaptor<MetaTable> tableCaptor = ArgumentCaptor.forClass(MetaTable.class);
        verify(metaTableMapper).insert(tableCaptor.capture());
        assertFalse(Boolean.TRUE.equals(tableCaptor.getValue().getPublished()));
    }

    @Test
    @DisplayName("selectQueryableTables 使用 active=true AND published=true 条件")
    void selectQueryableTablesAddsPublishedFilter() {
        MetaTableMapper mapper = mock(MetaTableMapper.class, CALLS_REAL_METHODS);
        doReturn(List.of()).when(mapper).selectList(any(LambdaQueryWrapper.class));

        mapper.selectQueryableTables(1);

        ArgumentCaptor<LambdaQueryWrapper<MetaTable>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().nonEmptyOfWhere());
    }

    @Test
    @DisplayName("selectActiveList 只使用 active=true，不要求 published=true")
    void selectActiveListDoesNotRequirePublished() {
        MetaTableMapper mapper = mock(MetaTableMapper.class, CALLS_REAL_METHODS);
        doReturn(List.of()).when(mapper).selectList(any(LambdaQueryWrapper.class));

        mapper.selectActiveList(1);

        ArgumentCaptor<LambdaQueryWrapper<MetaTable>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().nonEmptyOfWhere());
    }

    @Nested
    @DisplayName("发布状态修改")
    class PublishMutationTests {

        @Test
        @DisplayName("发布 API 服务方法设置 published=true")
        void publishTableSetsPublishedTrue() {
            MetaTable table = activeTable(1, "fact_gross_profit_detail", false);
            when(metaTableMapper.selectById(1)).thenReturn(table);

            MetaTable result = metadataService.publishTable(1);

            assertTrue(Boolean.TRUE.equals(result.getPublished()));
            verify(metaTableMapper).updateById(table);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("取消发布 API 服务方法设置 published=false")
        void unpublishTableSetsPublishedFalse() {
            MetaTable table = activeTable(1, "fact_gross_profit_detail", true);
            when(metaTableMapper.selectById(1)).thenReturn(table);

            MetaTable result = metadataService.unpublishTable(1);

            assertFalse(Boolean.TRUE.equals(result.getPublished()));
            verify(metaTableMapper).updateById(table);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("批量发布逐个设置 published=true")
        void batchPublishSetsPublishedTrue() {
            MetaTable first = activeTable(1, "fact_a", false);
            MetaTable second = activeTable(2, "fact_b", false);
            when(metaTableMapper.selectById(1)).thenReturn(first);
            when(metaTableMapper.selectById(2)).thenReturn(second);

            metadataService.publishTables(List.of(1, 2));

            assertTrue(Boolean.TRUE.equals(first.getPublished()));
            assertTrue(Boolean.TRUE.equals(second.getPublished()));
            verify(metaTableMapper).updateById(first);
            verify(metaTableMapper).updateById(second);
        }
    }

    @Test
    @DisplayName("未发布表不会进入 retrieveForPrompt 结果")
    void unpublishedTableIsExcludedFromRetrieveForPrompt() {
        MetaEmbedding publishedEmbedding = new MetaEmbedding();
        publishedEmbedding.setSourceId(1);
        publishedEmbedding.setSimilarity(0.95);
        MetaEmbedding unpublishedEmbedding = new MetaEmbedding();
        unpublishedEmbedding.setSourceId(2);
        unpublishedEmbedding.setSimilarity(0.94);

        MetaTable publishedTable = activeTable(1, "fact_published", true);
        MetaTable unpublishedTable = activeTable(2, "fact_unpublished", false);
        MetaColumn column = new MetaColumn();
        column.setTableId(1);
        column.setColumnName("revenue");
        column.setActive(true);

        when(ragConfig.getTopKExample()).thenReturn(5);
        when(ragConfig.getTopKGlossary()).thenReturn(5);
        when(ragConfig.getTopKTable()).thenReturn(5);
        when(ragConfig.getSimilarityThresholdTable()).thenReturn(0.55);
        when(ragConfig.isFallbackToKeyword()).thenReturn(false);
        when(embeddingService.searchSimilar(anyString(), eq("sql_example"), anyInt())).thenReturn(List.of());
        when(embeddingService.searchSimilar(anyString(), eq("glossary"), anyInt())).thenReturn(List.of());
        when(embeddingService.searchSimilar(anyString(), eq("table_description"), anyInt()))
                .thenReturn(List.of(publishedEmbedding, unpublishedEmbedding));
        when(metaGlossaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(metaTableMapper.selectQueryableTables(1)).thenReturn(List.of(publishedTable));
        doReturn(List.of(publishedTable))
                .when(metaTableMapper).selectQueryableBatchIds(any(Collection.class), eq(1));
        when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(column));
        when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        MetadataContext context = metadataService.retrieveForPrompt("gross profit", 1);

        assertEquals(1, context.getTables().size());
        assertEquals("fact_published", context.getTables().get(0).getTableName());
        assertFalse(context.getTables().contains(unpublishedTable));
    }

    private MetaTable activeTable(Integer id, String tableName, boolean published) {
        MetaTable table = new MetaTable();
        table.setId(id);
        table.setDatasourceId(1);
        table.setTableName(tableName);
        table.setActive(true);
        table.setPublished(published);
        return table;
    }
}
