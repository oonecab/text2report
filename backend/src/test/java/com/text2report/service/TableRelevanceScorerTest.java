package com.text2report.service;

import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaGlossary;
import com.text2report.domain.meta.entity.MetaSqlExample;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TableRelevanceScorerTest {

    @Mock
    private MetaColumnMapper metaColumnMapper;

    private TableRelevanceScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new TableRelevanceScorer(metaColumnMapper);
    }

    @Test
    @DisplayName("场景1: glossary 强引用 fact_gross_profit_detail，该表排序靠前")
    void glossaryReferenceRanksTableFirst() {
        MetaTable detail = table(1, "fact_gross_profit_detail", true, "毛利明细事实表", "用于正式查询的毛利明细事实表");
        MetaTable org = table(2, "dim_org", true, "组织维表", "企业组织维度表");
        mockColumnCount(detail, 8);
        mockColumnCount(org, 6);

        MetaGlossary glossary = glossary("毛利", "毛利来自 fact_gross_profit_detail", "FROM fact_gross_profit_detail");

        List<MetaTable> ranked = scorer.rankTables(List.of(org, detail), List.of(glossary), List.of(), "毛利");

        assertEquals("fact_gross_profit_detail", ranked.get(0).getTableName());
    }

    @Test
    @DisplayName("场景2: example SQL 中 FROM fact_gross_profit_detail，该表排序靠前")
    void exampleReferenceRanksTableFirst() {
        MetaTable detail = table(1, "fact_gross_profit_detail", true, "毛利明细事实表", "用于正式查询的毛利明细事实表");
        MetaTable product = table(3, "dim_product", true, "产品维表", "产品维度描述信息");
        mockColumnCount(detail, 8);
        mockColumnCount(product, 5);

        MetaSqlExample example = example("SELECT SUM(revenue) FROM fact_gross_profit_detail");

        List<MetaTable> ranked = scorer.rankTables(List.of(product, detail), List.of(), List.of(example), "收入");

        assertEquals("fact_gross_profit_detail", ranked.get(0).getTableName());
    }

    @Test
    @DisplayName("场景3: 低质量未发布假表被移除，正式高质量表保留并靠前")
    void unpublishedLowQualityTableIsRemoved() {
        MetaTable fake = table(9, "fact_gross_profit", false, null, null);
        MetaTable detail = table(1, "fact_gross_profit_detail", true, "毛利明细事实表", "用于正式查询的毛利明细事实表");
        mockColumnCount(fake, 1);
        mockColumnCount(detail, 8);

        List<MetaTable> ranked = scorer.rankTables(List.of(fake, detail), List.of(), List.of(), "毛利");

        assertEquals(1, ranked.size());
        assertEquals("fact_gross_profit_detail", ranked.get(0).getTableName());
        assertFalse(ranked.stream().anyMatch(table -> "fact_gross_profit".equals(table.getTableName())));
    }

    @Test
    @DisplayName("场景4: 无 glossary/example 引用时，fact 表优先于 dim 表")
    void factTableRanksBeforeDimWithoutReferences() {
        MetaTable fact = table(1, "fact_sales", true, "销售事实表", "销售事实表描述信息");
        MetaTable dim = table(2, "dim_org", true, "组织维表", "组织维度表描述信息");
        mockColumnCount(fact, 5);
        mockColumnCount(dim, 5);

        List<MetaTable> ranked = scorer.rankTables(List.of(dim, fact), List.of(), List.of(), "销售");

        assertEquals("fact_sales", ranked.get(0).getTableName());
    }

    @Test
    @DisplayName("场景5: 表名包含 _test/_tmp 的候选表排序靠后")
    void lowTrustNameRanksLater() {
        MetaTable tmp = table(1, "fact_sales_tmp", true, "临时表", "临时销售事实表描述信息");
        MetaTable fact = table(2, "fact_sales_detail", true, "销售明细事实表", "正式销售明细事实表描述信息");
        mockColumnCount(tmp, 8);
        mockColumnCount(fact, 8);

        List<MetaTable> ranked = scorer.rankTables(List.of(tmp, fact), List.of(), List.of(), "销售");

        assertEquals("fact_sales_detail", ranked.get(0).getTableName());
    }

    @Test
    @DisplayName("场景6: description 为空、字段极少的表排序靠后")
    void sparseMetadataRanksLater() {
        MetaTable sparse = table(1, "fact_sparse", true, null, null);
        MetaTable quality = table(2, "fact_quality", true, "质量事实表", "高质量事实表描述信息");
        mockColumnCount(sparse, 2);
        mockColumnCount(quality, 6);

        List<MetaTable> ranked = scorer.rankTables(List.of(sparse, quality), List.of(), List.of(), "质量");

        assertEquals("fact_quality", ranked.get(0).getTableName());
    }

    @Test
    @DisplayName("场景7: glossary 引用的已发布表在候选集中时被排序靠前")
    void supplementedGlossaryTableRanksFirst() {
        MetaTable initial = table(2, "dim_org", true, "组织维表", "组织维度表描述信息");
        MetaTable supplemented = table(1, "fact_gross_profit_detail", true, "毛利明细事实表", "用于正式查询的毛利明细事实表");
        mockColumnCount(initial, 6);
        mockColumnCount(supplemented, 8);

        MetaGlossary glossary = glossary("毛利", "查询 fact_gross_profit_detail", "JOIN fact_gross_profit_detail");

        List<MetaTable> ranked = scorer.rankTables(List.of(initial, supplemented), List.of(glossary), List.of(), "毛利");

        assertEquals("fact_gross_profit_detail", ranked.get(0).getTableName());
    }

    @Test
    @DisplayName("场景8: glossary引用 + fact表 + 高质量metadata 综合得分最高")
    void comprehensiveSignalsRankBestTableFirst() {
        MetaTable best = table(1, "fact_gross_profit_detail", true, "毛利明细事实表", "用于正式查询的毛利明细事实表");
        MetaTable vectorOnly = table(2, "dim_org", true, "组织维表", "组织维度表描述信息");
        MetaTable weak = table(3, "fact_gross_profit_tmp", true, null, null);
        mockColumnCount(best, 8);
        mockColumnCount(vectorOnly, 6);
        mockColumnCount(weak, 1);

        MetaGlossary glossary = glossary("毛利", "来自 fact_gross_profit_detail", "FROM fact_gross_profit_detail");
        MetaSqlExample example = example("SELECT SUM(gross_profit) FROM fact_gross_profit_detail");

        List<MetaTable> ranked = scorer.rankTables(
                List.of(vectorOnly, weak, best),
                List.of(glossary),
                List.of(example),
                "毛利",
                Map.of(2, 0.95d, 3, 0.90d, 1, 0.70d)
        );

        assertEquals("fact_gross_profit_detail", ranked.get(0).getTableName());
    }

    private void mockColumnCount(MetaTable table, int count) {
        List<MetaColumn> columns = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> column(table.getId(), "col_" + index))
                .toList();
        lenient().when(metaColumnMapper.selectByTableId(table.getId())).thenReturn(columns);
    }

    private MetaTable table(Integer id, String name, boolean published, String label, String description) {
        MetaTable table = new MetaTable();
        table.setId(id);
        table.setTableName(name);
        table.setPublished(published);
        table.setActive(true);
        table.setLabel(label);
        table.setDescription(description);
        return table;
    }

    private MetaColumn column(Integer tableId, String name) {
        MetaColumn column = new MetaColumn();
        column.setTableId(tableId);
        column.setColumnName(name);
        column.setActive(true);
        return column;
    }

    private MetaGlossary glossary(String term, String definition, String sqlSnippet) {
        MetaGlossary glossary = new MetaGlossary();
        glossary.setTerm(term);
        glossary.setDefinition(definition);
        glossary.setSqlSnippet(sqlSnippet);
        glossary.setActive(true);
        return glossary;
    }

    private MetaSqlExample example(String sql) {
        MetaSqlExample example = new MetaSqlExample();
        example.setQuestion("查询毛利");
        example.setSqlQuery(sql);
        example.setActive(true);
        return example;
    }
}
