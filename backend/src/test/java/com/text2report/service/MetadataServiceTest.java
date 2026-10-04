package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.text2report.domain.meta.entity.*;
import com.text2report.domain.meta.mapper.*;
import com.text2report.domain.vector.entity.MetaEmbedding;
import com.text2report.model.dto.MetadataContext;
import com.text2report.config.RagConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetadataService
 */
@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

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
    private TableRelevanceScorer tableRelevanceScorer;

    @InjectMocks
    private MetadataService metadataService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Inject ObjectMapper via reflection since it's required but not mocked
        var field = MetadataService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(metadataService, objectMapper);
        lenient().when(tableRelevanceScorer.rankTables(any(List.class), any(List.class), any(List.class), anyString(), any(Map.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(tableRelevanceScorer.extractReferencedTableNamesFromGlossary(any(List.class), any(Collection.class)))
                .thenReturn(java.util.Set.of());
        lenient().when(tableRelevanceScorer.extractReferencedTableNamesFromExamples(any(List.class), any(Collection.class)))
                .thenReturn(java.util.Set.of());
    }

    @Nested
    @DisplayName("listActiveDatasources")
    class ListActiveDatasourcesTests {

        @Test
        @DisplayName("Should return active datasources")
        void shouldReturnActiveDatasources() {
            MetaDatasource ds = new MetaDatasource();
            ds.setId(1);
            ds.setName("test");
            when(metaDatasourceMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(ds));

            List<MetaDatasource> result = metadataService.listActiveDatasources();

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals(1, result.get(0).getId());
        }
    }

    @Nested
    @DisplayName("listTablesByDatasource")
    class ListTablesByDatasourceTests {

        @Test
        @DisplayName("Should return tables for datasource")
        void shouldReturnTables() {
            MetaTable table = new MetaTable();
            table.setId(1);
            table.setTableName("fact_gross_profit");
            when(metaTableMapper.selectListByDatasource(1))
                    .thenReturn(List.of(table));

            List<MetaTable> result = metadataService.listTablesByDatasource(1);

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("fact_gross_profit", result.get(0).getTableName());
        }
    }

    @Nested
    @DisplayName("listColumnsByTable")
    class ListColumnsByTableTests {

        @Test
        @DisplayName("Should return columns for table")
        void shouldReturnColumns() {
            MetaColumn col = new MetaColumn();
            col.setId(1);
            col.setColumnName("revenue");
            when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(col));

            List<MetaColumn> result = metadataService.listColumnsByTable(1);

            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("revenue", result.get(0).getColumnName());
        }
    }

    @Nested
    @DisplayName("updateTable")
    class UpdateTableTests {

        @Test
        @DisplayName("Should update table label and description")
        void shouldUpdateTable() {
            MetaTable existing = new MetaTable();
            existing.setId(1);
            existing.setActive(true);
            existing.setTableName("fact_gross_profit");
            existing.setLabel("旧中文名");
            existing.setDescription("旧说明");

            MetaTable update = new MetaTable();
            update.setLabel("新中文名");
            update.setDescription("新说明");

            when(metaTableMapper.selectById(1)).thenReturn(existing);

            MetaTable result = metadataService.updateTable(1, update);

            assertEquals("新中文名", result.getLabel());
            assertEquals("新说明", result.getDescription());
            verify(metaTableMapper).updateById(existing);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should update table active and published status")
        void shouldUpdateTableStatus() {
            MetaTable existing = new MetaTable();
            existing.setId(1);
            existing.setActive(true);
            existing.setPublished(false);
            existing.setTableName("fact_gross_profit");

            MetaTable update = new MetaTable();
            update.setLabel("毛利表");
            update.setDescription("说明");
            update.setActive(true);
            update.setPublished(true);

            when(metaTableMapper.selectById(1)).thenReturn(existing);

            MetaTable result = metadataService.updateTable(1, update);

            assertTrue(result.getActive());
            assertTrue(result.getPublished());
            verify(metaTableMapper).updateById(existing);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should unpublish table when deactivated")
        void shouldUnpublishWhenDeactivated() {
            MetaTable existing = new MetaTable();
            existing.setId(1);
            existing.setActive(true);
            existing.setPublished(true);
            existing.setTableName("fact_gross_profit");

            MetaTable update = new MetaTable();
            update.setActive(false);
            update.setPublished(true);

            when(metaTableMapper.selectById(1)).thenReturn(existing);

            MetaTable result = metadataService.updateTable(1, update);

            assertFalse(result.getActive());
            assertFalse(result.getPublished());
            verify(metaTableMapper).updateById(existing);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should throw when table not found")
        void shouldThrowWhenTableNotFound() {
            when(metaTableMapper.selectById(1)).thenReturn(null);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> metadataService.updateTable(1, new MetaTable())
            );

            assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
            verify(metaTableMapper, never()).updateById(any(MetaTable.class));
        }
    }

    @Nested
    @DisplayName("column management")
    class ColumnManagementTests {

        @Test
        @DisplayName("Should create column")
        void shouldCreateColumn() {
            MetaTable table = new MetaTable();
            table.setId(1);
            table.setActive(true);

            MetaColumn column = new MetaColumn();
            column.setColumnName("gross_margin_rate");
            column.setDataType("numeric(18,2)");
            column.setLabel("毛利率");
            column.setDescription("毛利率字段");

            when(metaTableMapper.selectById(1)).thenReturn(table);
            when(metaColumnMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            MetaColumn result = metadataService.createColumn(1, column);

            assertEquals(1, result.getTableId());
            assertTrue(result.getActive());
            assertEquals("manual", result.getCommentSource());
            verify(metaColumnMapper).insert(column);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should reject duplicate active column")
        void shouldRejectDuplicateActiveColumn() {
            MetaTable table = new MetaTable();
            table.setId(1);
            table.setActive(true);

            MetaColumn existing = new MetaColumn();
            existing.setId(2);
            existing.setActive(true);
            existing.setColumnName("revenue");

            MetaColumn column = new MetaColumn();
            column.setColumnName("revenue");

            when(metaTableMapper.selectById(1)).thenReturn(table);
            when(metaColumnMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> metadataService.createColumn(1, column)
            );

            assertEquals(ErrorCode.PARAM_INVALID, exception.getErrorCode());
            verify(metaColumnMapper, never()).insert(any(MetaColumn.class));
        }

        @Test
        @DisplayName("Should reactivate inactive column")
        void shouldReactivateInactiveColumn() {
            MetaTable table = new MetaTable();
            table.setId(1);
            table.setActive(true);

            MetaColumn existing = new MetaColumn();
            existing.setId(2);
            existing.setActive(false);
            existing.setColumnName("revenue");

            MetaColumn column = new MetaColumn();
            column.setColumnName("revenue");
            column.setDataType("numeric(18,2)");
            column.setLabel("收入");

            when(metaTableMapper.selectById(1)).thenReturn(table);
            when(metaColumnMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

            MetaColumn result = metadataService.createColumn(1, column);

            assertTrue(result.getActive());
            assertEquals("收入", result.getLabel());
            verify(metaColumnMapper).updateById(existing);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should soft delete column")
        void shouldSoftDeleteColumn() {
            MetaColumn existing = new MetaColumn();
            existing.setId(2);
            existing.setActive(true);
            existing.setColumnName("revenue");

            when(metaColumnMapper.selectById(2)).thenReturn(existing);

            metadataService.deleteColumn(2);

            assertFalse(existing.getActive());
            verify(metaColumnMapper).updateById(existing);
            verify(queryCacheService).invalidateAllQueryCache();
        }
    }

    @Nested
    @DisplayName("join management")
    class JoinManagementTests {

        @Test
        @DisplayName("Should create join edge when relation does not exist")
        void shouldCreateJoinEdge() {
            MetaJoinEdge edge = new MetaJoinEdge();
            edge.setFromTableId(1);
            edge.setFromColumn(" org_id ");
            edge.setToTableId(2);
            edge.setToColumn("id");
            edge.setJoinType("left");

            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            MetaJoinEdge result = metadataService.createJoinEdge(edge);

            assertNull(result.getId());
            assertTrue(result.getActive());
            assertEquals("org_id", result.getFromColumn());
            assertEquals("LEFT", result.getJoinType());
            verify(metaJoinEdgeMapper).insert(edge);
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should reject duplicate active join edge")
        void shouldRejectDuplicateActiveJoinEdge() {
            MetaJoinEdge existing = new MetaJoinEdge();
            existing.setId(10);
            existing.setFromTableId(1);
            existing.setFromColumn("org_id");
            existing.setToTableId(2);
            existing.setToColumn("id");
            existing.setActive(true);

            MetaJoinEdge edge = new MetaJoinEdge();
            edge.setFromTableId(1);
            edge.setFromColumn("ORG_ID");
            edge.setToTableId(2);
            edge.setToColumn("ID");
            edge.setJoinType("LEFT");

            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> metadataService.createJoinEdge(edge)
            );

            assertEquals(ErrorCode.PARAM_INVALID, exception.getErrorCode());
            assertEquals("JOIN 关系已存在", exception.getMessage());
            verify(metaJoinEdgeMapper, never()).insert(any(MetaJoinEdge.class));
            verify(metaJoinEdgeMapper, never()).updateById(any(MetaJoinEdge.class));
            verify(queryCacheService, never()).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should reject reverse duplicate active join edge")
        void shouldRejectReverseDuplicateActiveJoinEdge() {
            MetaJoinEdge existing = new MetaJoinEdge();
            existing.setId(10);
            existing.setFromTableId(2);
            existing.setFromColumn("id");
            existing.setToTableId(1);
            existing.setToColumn("org_id");
            existing.setActive(true);

            MetaJoinEdge edge = new MetaJoinEdge();
            edge.setFromTableId(1);
            edge.setFromColumn("org_id");
            edge.setToTableId(2);
            edge.setToColumn("id");
            edge.setJoinType("LEFT");

            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> metadataService.createJoinEdge(edge)
            );

            assertEquals(ErrorCode.PARAM_INVALID, exception.getErrorCode());
            verify(metaJoinEdgeMapper, never()).insert(any(MetaJoinEdge.class));
        }

        @Test
        @DisplayName("Should reactivate inactive duplicate join edge")
        void shouldReactivateInactiveDuplicateJoinEdge() {
            MetaJoinEdge existing = new MetaJoinEdge();
            existing.setId(10);
            existing.setFromTableId(1);
            existing.setFromColumn("org_id");
            existing.setToTableId(2);
            existing.setToColumn("id");
            existing.setJoinType("INNER");
            existing.setActive(false);

            MetaJoinEdge edge = new MetaJoinEdge();
            edge.setFromTableId(1);
            edge.setFromColumn("org_id");
            edge.setToTableId(2);
            edge.setToColumn("id");
            edge.setJoinType("left");
            edge.setDescription("毛利明细关联组织");

            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

            MetaJoinEdge result = metadataService.createJoinEdge(edge);

            assertSame(existing, result);
            assertTrue(result.getActive());
            assertEquals("LEFT", result.getJoinType());
            assertEquals("毛利明细关联组织", result.getDescription());
            verify(metaJoinEdgeMapper).updateById(existing);
            verify(metaJoinEdgeMapper, never()).insert(any(MetaJoinEdge.class));
            verify(queryCacheService).invalidateAllQueryCache();
        }

        @Test
        @DisplayName("Should reject join edge with missing endpoint")
        void shouldRejectJoinEdgeWithMissingEndpoint() {
            MetaJoinEdge edge = new MetaJoinEdge();
            edge.setFromTableId(1);
            edge.setFromColumn("org_id");
            edge.setToColumn("id");

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> metadataService.createJoinEdge(edge)
            );

            assertEquals(ErrorCode.PARAM_INVALID, exception.getErrorCode());
            verify(metaJoinEdgeMapper, never()).selectList(any(LambdaQueryWrapper.class));
            verify(metaJoinEdgeMapper, never()).insert(any(MetaJoinEdge.class));
        }
    }

    @Nested
    @DisplayName("searchGlossary")
    class SearchGlossaryTests {

        @Test
        @DisplayName("Should return global glossary when keyword is null")
        void shouldReturnGlobalGlossary() {
            MetaGlossary g = new MetaGlossary();
            g.setTerm("毛利");
            when(metaGlossaryMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(g));

            List<MetaGlossary> result = metadataService.searchGlossary(null);

            assertNotNull(result);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Should search by keyword")
        void shouldSearchByKeyword() {
            MetaGlossary g = new MetaGlossary();
            g.setTerm("毛利");
            when(metaGlossaryMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(g));

            List<MetaGlossary> result = metadataService.searchGlossary("毛利");

            assertNotNull(result);
            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("getExamples")
    class GetExamplesTests {

        @Test
        @DisplayName("Should return examples with limit")
        void shouldReturnExamplesWithLimit() {
            MetaSqlExample ex = new MetaSqlExample();
            ex.setQuestion("毛利是多少");
            when(metaSqlExampleMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(ex));

            List<MetaSqlExample> result = metadataService.getExamples("finance", 5);

            assertNotNull(result);
            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("buildMetadataJson")
    class BuildMetadataJsonTests {

        @Test
        @DisplayName("Should build valid JSON with dialect")
        void shouldBuildValidJsonWithDialect() {
            MetadataContext ctx = MetadataContext.builder()
                    .tables(List.of())
                    .columns(List.of())
                    .glossary(List.of())
                    .joinEdges(List.of())
                    .examples(List.of())
                    .build();

            String json = metadataService.buildMetadataJson(ctx);

            assertNotNull(json);
            assertTrue(json.contains("\"dialect\""));
            assertTrue(json.contains("postgresql"));
        }

        @Test
        @DisplayName("Should include tables in JSON")
        void shouldIncludeTablesInJson() {
            MetaTable table = new MetaTable();
            table.setId(1);
            table.setTableName("fact_gross_profit");
            table.setLabel("毛利表");
            table.setSchemaName("public");
            table.setDescription("用于毛利经营分析的事实表");

            MetaColumn col = new MetaColumn();
            col.setId(1);
            col.setTableId(1);
            col.setColumnName("revenue");
            col.setLabel("收入");
            col.setDataType("DECIMAL");

            MetadataContext ctx = MetadataContext.builder()
                    .tables(List.of(table))
                    .columns(List.of(col))
                    .glossary(List.of())
                    .joinEdges(List.of())
                    .examples(List.of())
                    .tableRecommendations(Map.of("fact_gross_profit", "primary"))
                    .tableRecommendedBy(Map.of("fact_gross_profit", List.of("glossary:毛利", "example:Q1毛利分析")))
                    .build();

            String json = metadataService.buildMetadataJson(ctx);

            assertNotNull(json);
            assertTrue(json.contains("fact_gross_profit"));
            assertTrue(json.contains("\"table_name\""));
            assertTrue(json.contains("\"recommendation\""));
            assertTrue(json.contains("\"primary\""));
            assertTrue(json.contains("\"recommended_by\""));
            assertTrue(json.contains("glossary:毛利"));
            assertTrue(json.contains("revenue"));
        }

        @Test
        @DisplayName("Should keep primary recommended table first in metadata JSON")
        void shouldKeepPrimaryRecommendedTableFirstInMetadataJson() {
            MetaTable primary = new MetaTable();
            primary.setId(1);
            primary.setTableName("fact_gross_profit_detail");
            primary.setLabel("毛利事实表");

            MetaTable secondary = new MetaTable();
            secondary.setId(2);
            secondary.setTableName("dim_org");
            secondary.setLabel("组织维表");

            MetadataContext ctx = MetadataContext.builder()
                    .tables(List.of(primary, secondary))
                    .columns(List.of())
                    .glossary(List.of())
                    .joinEdges(List.of())
                    .examples(List.of())
                    .tableRecommendations(Map.of(
                            "fact_gross_profit_detail", "primary",
                            "dim_org", "secondary"
                    ))
                    .tableRecommendedBy(Map.of())
                    .build();

            String json = metadataService.buildMetadataJson(ctx);

            assertTrue(json.indexOf("fact_gross_profit_detail") < json.indexOf("dim_org"));
            assertTrue(json.contains("\"recommendation\" : \"primary\""));
            assertTrue(json.contains("\"recommendation\" : \"secondary\""));
        }

        @Test
        @DisplayName("Should include glossary in JSON")
        void shouldIncludeGlossaryInJson() {
            MetaGlossary g = new MetaGlossary();
            g.setTerm("毛利");
            g.setDefinition("收入减成本");
            g.setSqlSnippet("revenue - cost");

            MetadataContext ctx = MetadataContext.builder()
                    .tables(List.of())
                    .columns(List.of())
                    .glossary(List.of(g))
                    .joinEdges(List.of())
                    .examples(List.of())
                    .build();

            String json = metadataService.buildMetadataJson(ctx);

            assertNotNull(json);
            assertTrue(json.contains("毛利"));
            assertTrue(json.contains("revenue - cost"));
        }

        @Test
        @DisplayName("Should verify buildMetadataJson contains no business data (Rule 1 compliance)")
        void shouldVerifyNoBusinessDataInJson() {
            // Verify that buildMetadataJson only outputs metadata structure, not actual values
            MetaTable table = new MetaTable();
            table.setId(1);
            table.setTableName("fact_gross_profit_detail");
            table.setLabel("毛利明细表");
            table.setSchemaName("public");

            MetaColumn col = new MetaColumn();
            col.setId(1);
            col.setTableId(1);
            col.setColumnName("revenue");
            col.setLabel("收入");
            col.setDataType("DECIMAL(18,2)");

            MetadataContext ctx = MetadataContext.builder()
                    .tables(List.of(table))
                    .columns(List.of(col))
                    .glossary(List.of())
                    .joinEdges(List.of())
                    .examples(List.of())
                    .build();

            String json = metadataService.buildMetadataJson(ctx);

            // Should contain table metadata
            assertTrue(json.contains("fact_gross_profit_detail"));
            assertTrue(json.contains("revenue"));
            assertTrue(json.contains("DECIMAL"));
            // Should NOT contain actual business values (Rule 1 compliance)
            assertFalse(json.contains("12345.67"));
            assertFalse(json.contains("100000"));
        }
    }

    @Nested
    @DisplayName("retrieveForPrompt")
    class RetrieveForPromptTests {

        @Test
        @DisplayName("Should prioritize tables referenced by retrieved examples")
        void shouldPrioritizeTablesReferencedByRetrievedExamples() {
            MetaEmbedding exampleEmbedding = new MetaEmbedding();
            exampleEmbedding.setSourceId(10);
            exampleEmbedding.setSimilarity(0.95);

            MetaSqlExample example = new MetaSqlExample();
            example.setId(10);
            example.setActive(true);
            example.setQuestion("子公司A各部门毛利");
            example.setSqlQuery("SELECT * FROM fact_gross_profit_detail f JOIN dim_org d ON f.org_id = d.id");

            MetaEmbedding oldTableEmbedding = new MetaEmbedding();
            oldTableEmbedding.setSourceId(3);
            oldTableEmbedding.setSimilarity(0.91);
            MetaEmbedding newTableEmbedding = new MetaEmbedding();
            newTableEmbedding.setSourceId(7);
            newTableEmbedding.setSimilarity(0.89);

            MetaTable oldTable = new MetaTable();
            oldTable.setId(3);
            oldTable.setDatasourceId(1);
            oldTable.setTableName("gross_profit_detail");
            oldTable.setActive(true);

            MetaTable newTable = new MetaTable();
            newTable.setId(7);
            newTable.setDatasourceId(1);
            newTable.setTableName("fact_gross_profit_detail");
            newTable.setActive(true);

            when(ragConfig.getTopKExample()).thenReturn(5);
            when(ragConfig.getTopKGlossary()).thenReturn(5);
            when(ragConfig.getTopKTable()).thenReturn(3);
            when(ragConfig.getSimilarityThresholdExample()).thenReturn(0.65);
            when(ragConfig.getSimilarityThresholdTable()).thenReturn(0.55);
            when(ragConfig.isFallbackToKeyword()).thenReturn(false);

            when(embeddingService.searchSimilar("子公司a各部门毛利", "sql_example", 5))
                    .thenReturn(List.of(exampleEmbedding));
            when(metaSqlExampleMapper.selectBatchIds(List.of(10))).thenReturn(List.of(example));
            when(embeddingService.searchSimilar("子公司a各部门毛利", "glossary", 5))
                    .thenReturn(List.of());
            when(metaGlossaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(embeddingService.searchSimilar("子公司a各部门毛利", "table_description", 3))
                    .thenReturn(List.of(oldTableEmbedding, newTableEmbedding));
            when(metaTableMapper.selectQueryableTables(1)).thenReturn(List.of());
            when(metaTableMapper.selectQueryableBatchIds(any(Collection.class), eq(1))).thenReturn(List.of(oldTable, newTable));
            when(tableRelevanceScorer.rankTables(any(List.class), any(List.class), any(List.class), eq("子公司A各部门毛利"), any(Map.class)))
                    .thenReturn(List.of(newTable, oldTable));
            when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            MetadataContext context = metadataService.retrieveForPrompt("子公司A各部门毛利", 1);

            assertEquals(2, context.getTables().size());
            assertEquals("fact_gross_profit_detail", context.getTables().get(0).getTableName());
            assertEquals("gross_profit_detail", context.getTables().get(1).getTableName());
        }

        @Test
        @DisplayName("Should include fact table when question matches fact columns")
        void shouldIncludeFactTableWhenQuestionMatchesFactColumns() {
            MetaTable dimProduct = new MetaTable();
            dimProduct.setId(2);
            dimProduct.setDatasourceId(1);
            dimProduct.setTableName("dim_product");
            dimProduct.setActive(true);

            MetaTable factTable = new MetaTable();
            factTable.setId(5);
            factTable.setDatasourceId(1);
            factTable.setTableName("fact_gross_profit_detail");
            factTable.setActive(true);

            MetaColumn productNameOnDim = new MetaColumn();
            productNameOnDim.setTableId(2);
            productNameOnDim.setColumnName("product_name");
            productNameOnDim.setLabel("产品名称");
            productNameOnDim.setDescription("产品名称");
            productNameOnDim.setActive(true);

            MetaColumn productNameOnFact = new MetaColumn();
            productNameOnFact.setTableId(5);
            productNameOnFact.setColumnName("product_name");
            productNameOnFact.setLabel("产品名称");
            productNameOnFact.setDescription("事实表中的产品名称");
            productNameOnFact.setActive(true);

            MetaColumn revenue = new MetaColumn();
            revenue.setTableId(5);
            revenue.setColumnName("revenue");
            revenue.setLabel("销售收入");
            revenue.setDescription("销售收入");
            revenue.setActive(true);

            MetaColumn cost = new MetaColumn();
            cost.setTableId(5);
            cost.setColumnName("cost");
            cost.setLabel("销售成本");
            cost.setDescription("销售成本");
            cost.setActive(true);

            MetaGlossary glossary = new MetaGlossary();
            glossary.setId(8);
            glossary.setTerm("毛利");
            glossary.setActive(true);

            when(ragConfig.getTopKExample()).thenReturn(5);
            when(ragConfig.getTopKGlossary()).thenReturn(5);
            when(ragConfig.getTopKTable()).thenReturn(3);
            when(ragConfig.isFallbackToKeyword()).thenReturn(true);

            when(embeddingService.searchSimilar(anyString(), eq("sql_example"), anyInt())).thenReturn(List.of());
            when(embeddingService.searchSimilar(anyString(), eq("glossary"), anyInt())).thenReturn(List.of());
            when(embeddingService.searchSimilar(anyString(), eq("table_description"), anyInt())).thenReturn(List.of());

            when(metaSqlExampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaGlossaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(glossary));
            when(metaTableMapper.searchByKeyword(anyString(), eq(1), eq(3))).thenReturn(List.of());
            when(metaTableMapper.selectQueryableTables(1))
                    .thenReturn(List.of(dimProduct, factTable));
            when(metaTableMapper.selectQueryableBatchIds(any(Collection.class), eq(1)))
                    .thenReturn(List.of(dimProduct, factTable));
            when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(productNameOnDim, productNameOnFact, revenue, cost))
                    .thenReturn(List.of(productNameOnDim, productNameOnFact, revenue, cost));
            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            MetadataContext context = metadataService.retrieveForPrompt(
                    "列出销售收入排名前5的具体产品名称及其对应的总毛利。",
                    1
            );

            assertEquals(2, context.getTables().size());
            assertTrue(context.getTables().stream()
                    .anyMatch(table -> "fact_gross_profit_detail".equals(table.getTableName())));
            assertTrue(context.getTables().stream()
                    .anyMatch(table -> "dim_product".equals(table.getTableName())));
            assertTrue(context.getGlossary().stream().anyMatch(item -> "毛利".equals(item.getTerm())));
        }

        @Test
        @DisplayName("Should supplement glossary referenced published table into retrieveForPrompt candidates")
        void shouldSupplementGlossaryReferencedPublishedTable() {
            MetaEmbedding glossaryEmbedding = new MetaEmbedding();
            glossaryEmbedding.setSourceId(8);
            glossaryEmbedding.setSimilarity(0.95);
            MetaEmbedding dimEmbedding = new MetaEmbedding();
            dimEmbedding.setSourceId(2);
            dimEmbedding.setSimilarity(0.80);

            MetaGlossary glossary = new MetaGlossary();
            glossary.setId(8);
            glossary.setTerm("毛利");
            glossary.setSqlSnippet("SELECT SUM(gross_profit) FROM fact_gross_profit_detail");
            glossary.setActive(true);

            MetaTable dimOrg = new MetaTable();
            dimOrg.setId(2);
            dimOrg.setDatasourceId(1);
            dimOrg.setTableName("dim_org");
            dimOrg.setActive(true);
            dimOrg.setPublished(true);

            MetaTable factDetail = new MetaTable();
            factDetail.setId(5);
            factDetail.setDatasourceId(1);
            factDetail.setTableName("fact_gross_profit_detail");
            factDetail.setActive(true);
            factDetail.setPublished(true);

            when(ragConfig.getTopKExample()).thenReturn(5);
            when(ragConfig.getTopKGlossary()).thenReturn(5);
            when(ragConfig.getTopKTable()).thenReturn(5);
            when(ragConfig.getSimilarityThresholdGlossary()).thenReturn(0.60);
            when(ragConfig.getSimilarityThresholdTable()).thenReturn(0.55);
            when(ragConfig.isFallbackToKeyword()).thenReturn(false);
            when(embeddingService.searchSimilar(anyString(), eq("sql_example"), anyInt())).thenReturn(List.of());
            when(embeddingService.searchSimilar(anyString(), eq("glossary"), anyInt())).thenReturn(List.of(glossaryEmbedding));
            when(metaSqlExampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaGlossaryMapper.selectBatchIds(List.of(8))).thenReturn(List.of(glossary));
            when(embeddingService.searchSimilar(anyString(), eq("table_description"), anyInt())).thenReturn(List.of(dimEmbedding));
            when(metaTableMapper.selectQueryableBatchIds(any(Collection.class), eq(1))).thenReturn(List.of(dimOrg));
            when(metaTableMapper.selectQueryableTables(1)).thenReturn(List.of(dimOrg, factDetail));
            when(tableRelevanceScorer.extractReferencedTableNamesFromGlossary(any(List.class), any(Collection.class)))
                    .thenReturn(new java.util.LinkedHashSet<>(List.of("fact_gross_profit_detail")));
            when(tableRelevanceScorer.extractReferencedTableNamesFromExamples(any(List.class), any(Collection.class)))
                    .thenReturn(java.util.Set.of());
            when(tableRelevanceScorer.rankTables(any(List.class), any(List.class), any(List.class), eq("毛利"), any(Map.class)))
                    .thenReturn(List.of(factDetail, dimOrg));
            when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            MetadataContext context = metadataService.retrieveForPrompt("毛利", 1);

            assertEquals(2, context.getTables().size());
            assertEquals("fact_gross_profit_detail", context.getTables().get(0).getTableName());
            assertTrue(context.getTables().stream().anyMatch(table -> "dim_org".equals(table.getTableName())));
        }

        @Test
        @DisplayName("Should not include unpublished tables in retrieveForPrompt results")
        void shouldNotIncludeUnpublishedTablesInRetrieveForPrompt() {
            MetaEmbedding fakeEmbedding = new MetaEmbedding();
            fakeEmbedding.setSourceId(9);
            fakeEmbedding.setSimilarity(0.90);
            MetaEmbedding detailEmbedding = new MetaEmbedding();
            detailEmbedding.setSourceId(5);
            detailEmbedding.setSimilarity(0.88);

            MetaTable factDetail = new MetaTable();
            factDetail.setId(5);
            factDetail.setDatasourceId(1);
            factDetail.setTableName("fact_gross_profit_detail");
            factDetail.setActive(true);
            factDetail.setPublished(true);

            MetaTable fakeTable = new MetaTable();
            fakeTable.setId(9);
            fakeTable.setDatasourceId(1);
            fakeTable.setTableName("fact_gross_profit");
            fakeTable.setActive(true);
            fakeTable.setPublished(false);

            when(ragConfig.getTopKExample()).thenReturn(5);
            when(ragConfig.getTopKGlossary()).thenReturn(5);
            when(ragConfig.getTopKTable()).thenReturn(5);
            when(ragConfig.getSimilarityThresholdTable()).thenReturn(0.55);
            when(ragConfig.isFallbackToKeyword()).thenReturn(false);
            when(embeddingService.searchSimilar(anyString(), eq("sql_example"), anyInt())).thenReturn(List.of());
            when(embeddingService.searchSimilar(anyString(), eq("glossary"), anyInt())).thenReturn(List.of());
            when(embeddingService.searchSimilar(anyString(), eq("table_description"), anyInt()))
                    .thenReturn(List.of(fakeEmbedding, detailEmbedding));
            when(metaSqlExampleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaGlossaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaTableMapper.selectQueryableBatchIds(any(Collection.class), eq(1))).thenReturn(List.of(factDetail));
            when(metaTableMapper.selectQueryableTables(1)).thenReturn(List.of(factDetail));
            when(tableRelevanceScorer.rankTables(any(List.class), any(List.class), any(List.class), eq("毛利"), any(Map.class)))
                    .thenReturn(List.of(factDetail));
            when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            MetadataContext context = metadataService.retrieveForPrompt("毛利", 1);

            assertEquals(List.of("fact_gross_profit_detail"),
                    context.getTables().stream().map(MetaTable::getTableName).toList());
            assertFalse(context.getTables().contains(fakeTable));
        }

        @Test
        @DisplayName("Should populate table recommendation sources from glossary and examples")
        void shouldPopulateTableRecommendationSources() {
            MetaEmbedding exampleEmbedding = new MetaEmbedding();
            exampleEmbedding.setSourceId(10);
            exampleEmbedding.setSimilarity(0.95);
            MetaEmbedding glossaryEmbedding = new MetaEmbedding();
            glossaryEmbedding.setSourceId(8);
            glossaryEmbedding.setSimilarity(0.92);
            MetaEmbedding factEmbedding = new MetaEmbedding();
            factEmbedding.setSourceId(5);
            factEmbedding.setSimilarity(0.90);
            MetaEmbedding dimEmbedding = new MetaEmbedding();
            dimEmbedding.setSourceId(2);
            dimEmbedding.setSimilarity(0.88);

            MetaSqlExample example = new MetaSqlExample();
            example.setId(10);
            example.setQuestion("Q1毛利分析报告");
            example.setSqlQuery("SELECT f.revenue, d.org_name FROM fact_gross_profit_detail f JOIN dim_org d ON f.org_id = d.id");
            example.setActive(true);

            MetaGlossary glossary = new MetaGlossary();
            glossary.setId(8);
            glossary.setTerm("毛利");
            glossary.setSqlSnippet("SELECT SUM(gross_profit) FROM fact_gross_profit_detail");
            glossary.setDefinition("毛利来自 fact_gross_profit_detail 的 gross_profit 字段");
            glossary.setActive(true);

            MetaTable factDetail = new MetaTable();
            factDetail.setId(5);
            factDetail.setDatasourceId(1);
            factDetail.setTableName("fact_gross_profit_detail");
            factDetail.setTableType("fact");
            factDetail.setPublished(true);
            factDetail.setActive(true);

            MetaTable dimOrg = new MetaTable();
            dimOrg.setId(2);
            dimOrg.setDatasourceId(1);
            dimOrg.setTableName("dim_org");
            dimOrg.setTableType("dim");
            dimOrg.setPublished(true);
            dimOrg.setActive(true);

            when(ragConfig.getTopKExample()).thenReturn(5);
            when(ragConfig.getTopKGlossary()).thenReturn(5);
            when(ragConfig.getTopKTable()).thenReturn(5);
            when(ragConfig.getSimilarityThresholdExample()).thenReturn(0.65);
            when(ragConfig.getSimilarityThresholdGlossary()).thenReturn(0.60);
            when(ragConfig.getSimilarityThresholdTable()).thenReturn(0.55);
            when(ragConfig.isFallbackToKeyword()).thenReturn(false);
            when(embeddingService.searchSimilar(anyString(), eq("sql_example"), anyInt())).thenReturn(List.of(exampleEmbedding));
            when(embeddingService.searchSimilar(anyString(), eq("glossary"), anyInt())).thenReturn(List.of(glossaryEmbedding));
            when(embeddingService.searchSimilar(anyString(), eq("table_description"), anyInt()))
                    .thenReturn(List.of(factEmbedding, dimEmbedding));
            when(metaSqlExampleMapper.selectBatchIds(List.of(10))).thenReturn(List.of(example));
            when(metaGlossaryMapper.selectBatchIds(List.of(8))).thenReturn(List.of(glossary));
            when(metaTableMapper.selectQueryableBatchIds(any(Collection.class), eq(1))).thenReturn(List.of(factDetail, dimOrg));
            when(metaTableMapper.selectQueryableTables(1)).thenReturn(List.of(factDetail, dimOrg));
            when(tableRelevanceScorer.rankTables(any(List.class), any(List.class), any(List.class), eq("毛利"), any(Map.class)))
                    .thenReturn(List.of(factDetail, dimOrg));
            when(metaColumnMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
            when(metaJoinEdgeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            MetadataContext context = metadataService.retrieveForPrompt("毛利", 1);

            assertEquals("primary", context.getTableRecommendations().get("fact_gross_profit_detail"));
            assertEquals("secondary", context.getTableRecommendations().get("dim_org"));
            assertEquals(List.of("glossary:毛利", "example:Q1毛利分析报告"),
                    context.getTableRecommendedBy().get("fact_gross_profit_detail"));
            assertEquals(List.of("example:Q1毛利分析报告"), context.getTableRecommendedBy().get("dim_org"));
        }
    }
}
