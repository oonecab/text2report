# RAG 向量检索

## 问题一：Embedding 文本类型与索引策略

### 问题
Embedding 是对什么文本做的？表描述、字段注释、SQL 示例——这三类文本是分开建索引还是混在一起？召回时怎么区分来源？

### 答案

**三类文本分别建索引，通过 source_type 字段区分。**

---

## 三类文本的 embedding 内容

### 1. SQL 示例

```java
public String buildSqlExampleText(MetaSqlExample example) {
    return "问题: " + example.getQuestion() + "\nSQL: " + example.getSqlQuery();
}
```

格式：`问题: 子公司A的毛利率是多少？\nSQL: SELECT ...`

### 2. 术语表（Glossary）

```java
public String buildGlossaryText(MetaGlossary glossary) {
    StringBuilder builder = new StringBuilder()
            .append("术语: ").append(glossary.getTerm())
            .append("\n定义: ").append(glossary.getDefinition() == null ? "" : glossary.getDefinition());
    if (glossary.getSqlSnippet() != null) {
        builder.append("\nSQL: ").append(glossary.getSqlSnippet());
    }
    return builder.toString();
}
```

格式：`术语: 毛利率\n定义: (收入-成本)/收入\nSQL: ROUND((SUM(revenue)-SUM(cost))*100.0/NULLIF(SUM(revenue),0), 2)`

### 3. 表描述

```java
public String buildTableDescriptionText(MetaTable table, List<MetaColumn> columns) {
    builder.append("表: ").append(table.getTableName());
    builder.append("\n说明: ").append(table.getDescription());
    builder.append("\n字段: ");
    for (MetaColumn column : columns) {
        builder.append(column.getColumnName());
        if (column.getLabel() != null) {
            builder.append("(").append(column.getLabel()).append(")");
        }
    }
}
```

格式：`表: fact_gross_profit_detail\n说明: 月度营收明细表\n字段: period_month(期间), org_id(组织ID), revenue(收入), cost(成本)...`

---

## 索引策略：分开建索引

**三类文本是分开存储的，通过 `source_type` 字段区分。**

```java
@TableName("meta_embedding")
public class MetaEmbedding {
    private String sourceType;  // "sql_example" | "glossary" | "table_description"
    private Integer sourceId;
    private String contentText;
    private float[] embedding;
    private String modelVersion;
}
```

在 `meta_embedding` 表里，三条记录可能有相同的向量内容但不同的 source_type：

| id | source_type | source_id | content_text |
|----|-------------|-----------|--------------|
| 1 | sql_example | 5 | 问题: 毛利是多少？... |
| 2 | glossary | 3 | 术语: 毛利... |
| 3 | table_description | 7 | 表: fact_gross_profit... |

---

## 召回时怎么区分来源？

**召回时通过 `sourceType` 参数过滤，不同类型用不同的阈值和 topK。**

```java
// 示例召回
List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(
    keyword, "sql_example", ragConfig.getTopKExample());  // 5

// 术语召回
List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(
    keyword, "glossary", ragConfig.getTopKGlossary());  // 5

// 表描述召回
List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(
    keyword, "table_description", ragConfig.getTopKTable());  // 3
```

### 配置的相似度阈值

```yaml
similarityThresholdExample: 0.65    # 示例要求相似度 > 0.65
similarityThresholdGlossary: 0.60   # 术语要求 > 0.60
similarityThresholdTable: 0.55      # 表描述要求 > 0.55
```

---

## 为什么这样设计？

| 设计决策 | 原因 |
|---------|------|
| 文本格式化不同 | SQL 示例需要问题和 SQL 配对，术语需要术语+定义+SQL片段，表描述需要表名+字段列表 |
| 分开索引 | 不同类型文本长度、语义颗粒度不同，放一起会互相干扰 |
| 不同阈值 | 示例最严格（0.65），术语次之（0.60），表描述最宽松（0.55） |
| 不同 topK | 示例/术语召回 5 条，表描述召回 3 条（表描述信息量大，少召回一些） |

---

## 问题二：Query Embedding 与 Document Embedding

### 问题
用户问"今年华北区的毛利率"，你怎么把这个问题向量化后去匹配表结构？query embedding 和 document embedding 是同一个模型吗？

### 答案

**同一个模型（bge-m3），但时机和文本不同。**

---

## Document Embedding（入库时）

```java
// 用户添加术语时，异步向量化
embeddingService.embedAndStoreAsync("glossary", glossary.getId(),
    embeddingService.buildGlossaryText(glossary));  // "术语: 毛利率\n定义: ...\nSQL: ..."
```

入库的文本是**格式化后的业务描述**，不是原始数据库字段。

---

## Query Embedding（查询时）

```java
// 用户问"今年华北区的毛利率"
List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(
    "今年华北区的毛利率",  // ← 用户的原始问题
    "glossary",
    ragConfig.getTopKGlossary()
);
```

---

## 匹配过程

```
用户问题: "今年华北区的毛利率"
    ↓
生成向量 (bge-m3)
    ↓
向量相似度搜索
    ↓
匹配到相关文档
```

### 为什么会匹配成功？

| 匹配对象 | 存储的文档文本 | 匹配原因 |
|---------|--------------|----------|
| 术语表 | `术语: 毛利率\n定义: (收入-成本)/收入...` | 核心词"毛利率"完全匹配 |
| 表描述 | `表: fact_gross_profit_detail\n字段: revenue, cost, org_id...` | "毛利"相关，且有 org_id 可关联到"华北" |
| 示例 | `问题: 子公司A的毛利率是多少？\nSQL: ...` | 问的是"毛利率"，语义相近 |

---

## 关键词兜底机制

项目实现了**混合检索**——向量召回不足时，用关键词补充。

```java
private List<MetaSqlExample> retrieveExamples(String keyword) {
    // 1. 先向量搜索
    List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(keyword, "sql_example", topK);

    // 2. 过滤相似度阈值
    List<Integer> vectorIds = vectorMatches.stream()
            .filter(item -> item.getSimilarity() >= ragConfig.getSimilarityThresholdExample())
            .map(MetaEmbedding::getSourceId)
            .toList();

    // 3. 向量结果不足时，用关键词补充
    if (vectorIds.isEmpty() || ragConfig.isFallbackToKeyword()) {
        return mergeExamples(vectorExamples, searchExamplesByKeyword(keyword));
    }

    return vectorExamples;
}
```

---

## 问题三：向量相似度阈值与兜底机制

### 问题
向量相似度阈值怎么定的？如果阈值太高一条都召回不到，会发生什么？如果太低召回一堆噪声，Prompt 会不会超长？

### 答案

**阈值靠经验 + 调优；太高有兜底机制，太低有数量限制。**

---

## 当前阈值配置

```yaml
# RagConfig
similarityThresholdExample: 0.65
similarityThresholdGlossary: 0.60
similarityThresholdTable: 0.55
```

---

## 阈值怎么定的？

| 类型 | 阈值 | 原因 |
|------|------|------|
| 示例 (sql_example) | 0.65（最高） | SQL 示例最精确，匹配错了会把 LLM 带上歪路 |
| 术语 (glossary) | 0.60 | 术语定义相对结构化，匹配要求中等 |
| 表描述 (table_description) | 0.55（最低） | 表描述粒度粗，字段多，容易部分匹配 |

### 调优思路

```
冷启动：用保守阈值（偏高）
    ↓
看召回率：如果很多好问题召回为空 → 降低阈值
    ↓
看精度：如果召回结果相关性差 → 提高阈值
    ↓
上线后：根据用户反馈持续调整
```

---

## 阈值太高召回不到怎么办？

**代码里有兜底机制——关键词 fallback + 默认表兜底。**

```java
private List<MetaSqlExample> retrieveExamples(String keyword) {
    // 1. 向量搜索
    List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(keyword, "sql_example", topK);

    // 2. 按阈值过滤
    List<Integer> vectorIds = vectorMatches.stream()
            .filter(item -> item.getSimilarity() >= ragConfig.getSimilarityThresholdExample())
            .map(MetaEmbedding::getSourceId)
            .toList();

    List<MetaSqlExample> vectorExamples = selectExamplesByOrderedIds(vectorIds);

    // 3. 向量结果为空 or 开启了兜底 → 用关键词搜索补充
    if (vectorExamples.isEmpty() || ragConfig.isFallbackToKeyword()) {
        return mergeExamples(vectorExamples, searchExamplesByKeyword(keyword));
    }

    return vectorExamples;
}
```

### 兜底策略流程

```
向量搜索 "今年华北区的毛利率"
    ↓
找到 0 条（相似度都 < 0.65）
    ↓
触发关键词搜索 LIKE '%毛利率%' 或 LIKE '%华北%'
    ↓
找到 3 条术语
    ↓
合并去重返回
```

### 最终兜底：默认表

```java
if (tableIds.isEmpty()) {
    // 返回该数据源的默认表
    return metaTableMapper.selectList(
        new LambdaQueryWrapper<MetaTable>()
            .eq(MetaTable::getDatasourceId, datasourceId)
            .eq(MetaTable::getActive, true)
            .last("LIMIT " + ragConfig.getTopKTable())
    );
}
```

---

## 阈值太低召回一堆噪声怎么办？

**代码做了多重限制，不是召回多少就塞给 LLM 多少。**

### 限制 1：topK 限制召回数量

```yaml
topKExample: 5      # 最多召回 5 条
topKGlossary: 5     # 最多召回 5 条
topKTable: 3       # 最多召回 3 张表
```

### 限制 2：Merge 时再次 limit

```java
private List<MetaSqlExample> mergeExamples(List<MetaSqlExample> primary, List<MetaSqlExample> secondary) {
    LinkedHashMap<Integer, MetaSqlExample> merged = new LinkedHashMap<>();
    primary.forEach(item -> merged.put(item.getId(), item));
    secondary.forEach(item -> merged.putIfAbsent(item.getId(), item));
    return merged.values().stream()
            .limit(ragConfig.getTopKExample())  // ← 合并后再 limit 一次
            .toList();
}
```

### 限制 3：Prompt 构建时的截断

Prompt 模板有长度限制，LLM API 也有 `max-tokens: 2000` 的配置。

---

## Prompt 会不会超长？

**当前设计下不会。**

| 防护层 | 机制 |
|--------|------|
| topK 限制 | 最多召回 5+5+3 = 13 条元数据 |
| 向量阈值 | 低于 0.55-0.65 的不召回 |
| 关键词兜底 | 精确匹配，不会引入大量噪声 |
| Prompt 模板 | 固定格式，内容可控 |

### 粗略估算

```
5 条示例 × (50字问题 + 80字SQL) ≈ 650 字
5 条术语 × 100字 ≈ 500 字
3 张表 × (表名30 + 字段200) ≈ 690 字
总计 ≈ 1840 字 + 模板固定内容 ≈ 3000 字
```

离 LLM 的 2000 token 上限还有余量，但已经偏多。

---

## 问题四：元数据变更后的向量同步

### 问题
元数据变更后异步向量化，变更完成到向量更新之间有个窗口期，这段时间用户查询会命中旧向量，你怎么处理？

### 答案

**项目选择"最终一致性"策略，不保证"立即一致性"，通过关键词兜底缓解问题。**

---

## 当前设计：接受窗口期

```java
@Transactional(transactionManager = "metaTransactionManager")
public MetaGlossary createGlossary(MetaGlossary glossary) {
    metaGlossaryMapper.insert(glossary);  // ← 主事务提交
    embeddingService.embedAndStoreAsync("glossary", glossary.getId(), ...);  // ← 异步
    queryCacheService.invalidateAllQueryCache();  // ← 只清查询缓存，不清向量
    return glossary;
}
```

**时间线：**
```
T0: 用户创建术语"毛利率"
T0: 主事务提交，接口返回成功
T1: 异步任务开始，调用 bge-m3 生成向量
T2: 向量写入 meta_embedding 表
T3: 用户查询"什么是毛利率" → 可能命中不到新术语
```

---

## 如何缓解窗口期问题？

### 1. 关键词兜底保底

新术语入库后，虽然向量还没生成，但**关键词搜索是实时的**：

```java
private List<MetaGlossary> retrieveGlossary(String keyword) {
    // 向量搜索
    List<MetaEmbedding> vectorMatches = embeddingService.searchSimilar(keyword, "glossary", topK);

    // 过滤阈值
    List<Integer> vectorIds = ...;
    List<MetaGlossary> vectorGlossary = selectGlossaryByOrderedIds(vectorIds);

    if (vectorGlossary.isEmpty() || ragConfig.isFallbackToKeyword()) {
        // 关键词兜底是直接查数据库，不依赖向量
        return mergeGlossary(vectorGlossary, searchGlossary(keyword));
    }
    return vectorGlossary;
}
```

### 2. 向量 + 关键词 Merge

```java
private List<MetaGlossary> mergeGlossary(List<MetaGlossary> primary, List<MetaGlossary> secondary) {
    LinkedHashMap<Integer, MetaGlossary> merged = new LinkedHashMap<>();
    primary.forEach(item -> merged.put(item.getId(), item));
    secondary.forEach(item -> merged.putIfAbsent(item.getId(), item));  // ← 关键词结果补充
    return merged.values().stream().limit(ragConfig.getTopKGlossary()).toList();
}
```

### 3. 全量同步接口

管理员可以手动触发全量同步：

```java
public EmbeddingSyncResult syncAll() {
    EmbeddingSyncResult result = new EmbeddingSyncResult(
        syncAllExamples(),
        syncAllGlossary(),
        syncAllTableDescriptions()
    );
    queryCacheService.invalidateAllQueryCache();
    return result;
}
```

---

## 如果要做得更严格？

| 方案 | 实现 | 代价 |
|------|------|------|
| 同步向量化 | `embedAndStore()` 同步等待 | 接口响应变慢 1-3 秒 |
| 双写 + 版本号 | 查询时检查 `updatedAt > vector.updatedAt` 则重新生成 | 实现复杂度增加 |
| 消息队列 | 变更事件 → 队列 → worker → 向量写入 | 引入额外组件 |

---

## 问题五：BGE-M3 推理服务降级

### 问题
BGE-M3 是本地部署，如果推理服务挂了，整个查询链路怎么降级？降级后 SQL 生成质量会有多大影响？

### 答案

**降级策略：向量搜索抛异常 → 返回空列表 → 触发关键词兜底 → 流程继续。SQL 质量逐步下降但业务能跑。**

---

## 当前降级策略

### Embedding 服务挂了时

```java
public List<MetaEmbedding> searchSimilar(String query, String sourceType, int topK) {
    try {
        float[] queryVector = llmService.generateEmbedding(query);  // ← 可能抛异常
        return embeddingMapper.searchSimilar(queryVector, topK, sourceType);
    } catch (Exception e) {
        log.warn("Vector search failed for sourceType={}", sourceType, e);
        return Collections.emptyList();  // ← 返回空列表，触发关键词兜底
    }
}
```

### 向量化入库失败时

```java
public void embedAndStore(String sourceType, Integer sourceId, String contentText) {
    try {
        float[] vector = llmService.generateEmbedding(contentText);
        embeddingMapper.upsertEmbedding(sourceType, sourceId, contentText, vector, ...);
    } catch (Exception e) {
        log.error("Embedding failed for {}:{}", sourceType, sourceId, e);
        // 异常被吞掉，主流程不受影响
    }
}
```

---

## 降级后的 SQL 生成质量影响

| 降级场景 | 召回内容 | SQL 生成质量 |
|---------|---------|-------------|
| **向量搜索正常** | 语义相关的表/术语/示例，5-3 条 | 最好 |
| **向量服务挂了** | 仅关键词匹配的内容 | 略差，但能工作 |
| **关键词也匹配不到** | 默认表（该数据源的活跃表前3张） | 较差，可能选错表 |

### 质量损失的具体表现

**向量搜索时：**
```
用户问: "今年华北区的毛利率"
→ 召回: 术语"毛利率"、表fact_gross_profit_detail（含org_id维度）、示例"子公司A毛利率"
→ LLM 知道要查 revenue/cost 按 org 和 时间维度
```

**降级到关键词时：**
```
用户问: "今年华北区的毛利率"
→ 召回: 所有包含"毛利率"的术语（可能没有）
→ 可能召回: 表名/字段名含"毛利"的表（如果命名不规范，可能没有）
→ LLM 需要从有限的元数据里猜
```

**最坏情况（默认表）：**
```
→ 返回 fact_gross_profit_detail（通用事实表）
→ LLM 可能蒙对，也可能选错
```

---

## 实际降级链路

```
用户问 "今年华北区的毛利率"
    ↓
Embedding 服务调用
    ↓
[正常] → 向量搜索 → 召回术语+表+示例 → 拼 Prompt → LLM 生成 SQL
[异常] → 返回空列表 → 关键词搜索 → 合并结果 → 拼 Prompt → LLM 生成 SQL
[极端] → 关键词也找不到 → 默认表 → 拼 Prompt → LLM 生成 SQL
```

---

## 总结

| 问题 | 结论 |
|------|------|
| Embedding 文本类型 | SQL 示例、术语、表描述分开格式化、分开索引 |
| 来源区分 | 通过 `source_type` 字段 + 不同阈值/topK |
| Query 和 Doc 用同一模型？ | 是，都是 bge-m3 |
| 阈值怎么定 | 经验值 + 调优，示例最严(0.65)，表描述最松(0.55) |
| 太高召回不到？ | 关键词兜底 + 默认表兜底，不会断流 |
| 太低召回国噪声？ | topK 限制 + merge 去重 + Prompt 模板长度控制 |
| 窗口期问题 | 接受最终一致性，关键词兜底缓解 |
| BGE-M3 挂了？ | 降级到纯关键词搜索，业务能跑但质量下降 |

---

*文档生成时间：2026-04-21*
