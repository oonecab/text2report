# 多 LLM 接入与编排

## 问题一：主备切换的触发条件

### 问题
主备切换的触发条件是什么？只有超时吗？HTTP 5xx、Rate Limit 429、返回 JSON 格式错误，这些都会触发切换吗？

### 答案

**坦诚说：当前项目没有主备切换机制。**

---

## 当前代码的错误处理逻辑

```java
// LLMAsyncExecutor.callAsync()
try (okhttp3.Response response = client.newCall(request).execute()) {
    if (!response.isSuccessful()) {  // ← HTTP 4xx/5xx 都走这里
        throw new BusinessException(ErrorCode.LLM_ERROR,
                "LLM API error: HTTP " + response.code() + " - " + response.message());
    }
    // ...
} catch (BusinessException e) {
    return CompletableFuture.failedFuture(e);  // ← 异常被包装后返回
}
```

---

## 各种错误类型的实际处理

| 错误类型 | 代码处理 | 会触发切换吗？ |
|---------|---------|--------------|
| **HTTP 5xx** | 抛异常 → `LLM_ERROR` | ❌ 没有切换，只是抛异常 |
| **HTTP 429 Rate Limit** | `!response.isSuccessful()` → 抛异常 | ❌ 没有切换，只是抛异常 |
| **HTTP 4xx** | `!response.isSuccessful()` → 抛异常 | ❌ 没有切换，只是抛异常 |
| **超时** | `readTimeout` 触发 → IOException → 抛异常 | ❌ 没有切换，只是抛异常 |
| **JSON 格式错误** | `extractContent()` 尝试解析，失败则返回原始文本 | ❌ 没有切换，只是降级处理 |

**结论：当前代码只有"抛异常"一种处理，没有主备切换。**

---

## 为什么没有切换机制？

当前架构是**单 provider 配置**：

```yaml
llm:
  provider: openai    # ← 只配置一个
  openai:
    api-key: xxx
    base-url: https://api.deepseek.com/v1
    model: deepseek-chat
```

两个 LLM Service 用 `@ConditionalOnProperty` 互斥：

```java
// OpenAILLMService
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)

// LocalLLMService
@ConditionalOnProperty(name = "llm.provider", havingValue = "local")
```

**启动时决定用哪个，运行时不切换。**

---

## 如果要实现主备切换

| 方案 | 实现 | 说明 |
|------|------|------|
| 配置级主备 | `primary` + `fallback` 配置 | 运行时切换 |
| Router 层 | `LLMServiceRouter` 包装多个 provider | 异常时自动切换 |

触发条件可配置：
```java
if (e instanceof TimeoutException) → 切换
if (e instanceof RateLimitException) → 切换
if (e instanceof ServerErrorException) → 切换
// 不切换：参数错误(400)、认证失败(401/403)
```

---

## 问题二：LLM 返回 SQL 解析失败时的重试机制

### 问题
LLM 返回的 SQL 解析失败（不是合法 JSON），你是直接报错还是重试？重试几次？重试的 Prompt 和第一次一样吗？

### 答案

**需要区分两种情况：LLM 返回非 JSON vs SQL 执行失败。**

---

## 情况 1：LLM 返回非 JSON 格式

**直接报错，不重试。**

```java
// OpenAILLMService.generateSQL()
try {
    JsonNode root = objectMapper.readTree(content);  // ← JSON 解析失败
    JsonNode sqlNode = root.get("sql");
} catch (Exception e) {
    throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "Failed to parse LLM response");
}
```

---

## 情况 2：SQL 执行失败

**会触发重试。** 重试链路有两处：

### 重试 1：validateOrRepair（SQL Guard 拦截）

```java
private String validateOrRepair(QueryContext queryContext, String sql) {
    while (true) {
        try {
            return sqlGuardService.validate(sql);
        } catch (BusinessException guardException) {
            if (!isRetryable(guardException.getMessage())
                || queryContext.getRetryCount() >= queryContext.getMaxRetries()) {
                throw guardException;
            }
            queryContext.setRetryCount(queryContext.getRetryCount() + 1);
            currentSql = llmService.repairSQL(currentSql, guardException.getMessage(), queryContext.getMetadataJson());
        }
    }
}
```

### 重试 2：executeWithRetries（SQL 执行失败）

```java
while (true) {
    try {
        return queryExecutorService.execute(queryContext.getValidatedSql());
    } catch (BusinessException executionException) {
        if (!isRetryable(executionException.getMessage())
            || queryContext.getRetryCount() >= queryContext.getMaxRetries()) {
            throw executionException;
        }
        queryContext.setRetryCount(queryContext.getRetryCount() + 1);
        String repairedSql = llmService.repairSQL(...);
    }
}
```

---

## 可重试 vs 不可重试的错误

```java
private boolean isRetryable(String errorMessage) {
    return lower.contains("column")
            || lower.contains("table")
            || lower.contains("relation")
            || lower.contains("does not exist")
            || lower.contains("syntax error")
            || lower.contains("ambiguous");
}
```

| 错误类型 | 可重试？ |
|---------|---------|
| `column "xxx" does not exist` | ✅ |
| `syntax error at or near "..."` | ✅ |
| `SELECT *` 拦截 | ❌ |
| 权限不足 | ❌ |

---

## 重试次数和 Prompt

```java
@Value("${app.query.max-retries:2}")
private int maxRetries;
```

| 项目 | 第一次 | 第二次 |
|------|--------|--------|
| **Prompt** | `buildSqlGenerationPrompt()` | `buildSqlRepairPrompt()` |
| **内容差异** | 原始问题 + 元数据 | 错误 SQL + 错误信息 |

```java
// 第一次：generateSQL
promptBuilder.buildSqlGenerationPrompt(
    dialect, orgPath, orgIds, metadataJson, question  // ← 原始问题
);

// 第二次：repairSQL
promptBuilder.buildSqlRepairPrompt(
    dialect, metadataJson, "", previousSql, errorMessage  // ← 错误信息注入
);
```

---

## 总结

| 问题 | 答案 |
|------|------|
| LLM 返回非 JSON 怎么处理？ | **直接报错，不重试** |
| 什么时候触发重试？ | **SQL Guard 拦截** 或 **SQL 执行失败** 时 |
| 重试几次？ | 默认 **2 次** |
| 重试 Prompt 和第一次一样吗？ | **不一样**——第一次用 generation，第二次用 repair（含错误信息） |

---

## 问题三：Prompt 模板与模型的关系

### 问题
你说 LLMService 接口隔离，那 Prompt 模板是跟模型绑定的还是通用的？OpenAI 和 Qwen 的 Prompt 格式一样吗？切换模型时 Prompt 要不要改？

### 答案

**Prompt 模板是通用的，跟模型无关。**

---

## Prompt 模板是共享的

```java
public String buildSqlGenerationPrompt(...) {
    String template = loadTemplate("sql_generation_v1");  // ← 同一个模板
    return fill(template, Map.of(
            "dialect", dialect,
            "org_context", orgContext,
            "org_ids", orgIds,
            "metadata_json", metadataJson,
            "user_question", userQuestion
    ));
}
```

不管用 OpenAI、DeepSeek 还是 Qwen，Prompt 内容完全相同。

---

## OpenAI 和 Qwen 的差异

| 参数 | OpenAI | Qwen (本地预留) | 需要改？ |
|------|--------|----------------|---------|
| Prompt 模板 | `sql_generation_v1.txt` | `sql_generation_v1.txt` | ❌ 不需要 |
| `response_format: json_object` | ✅ 支持 | ❌ 不支持 | **可能需要** |
| `temperature` | ✅ 支持 | ✅ 支持 | ❌ 不需要 |
| System Prompt | ✅ 支持 | ✅ 支持 | ❌ 不需要 |

---

## 切换模型时需要改什么？

**Prompt 内容不需要改**，但 API 调用参数可能需要调整。

```java
// OpenAI 特有
"response_format", Map.of("type", "json_object")

// Qwen 不支持这个参数，需要去掉
```

---

## LocalLLMService 当前状态

```java
@ConditionalOnProperty(name = "llm.provider", havingValue = "local")
public class LocalLLMService implements LLMService {
    @Override
    public String generateSQL(...) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Local LLM not configured yet");
    }
}
```

**Qwen 实现是预留的，还没有真正接入。**

---

## 总结

| 问题 | 答案 |
|------|------|
| Prompt 跟模型绑定的吗？ | ❌ 通用，模板文件是共享的 |
| OpenAI 和 Qwen Prompt 一样吗？ | ✅ 完全一样 |
| 切换模型 Prompt 要改吗？ | **Prompt 内容不需要改**，API 参数（如 `response_format`）可能需要调整 |
| 当前有几套 Prompt？ | **只有一套**（3 个模板文件） |

---

## 问题四：元数据量与 Context Window 管理

### 问题
你的 Prompt 模板里会把多少元数据塞进去？有没有计算过 token 数量上限？如果元数据超出 context window 怎么截断？

### 答案

**当前没有显式 token 计算，依赖 topK 限制 + 大 context window 兜底。**

---

## 元数据召回数量限制

```yaml
# RagConfig
topKExample: 5      # 最多召回 5 条
topKGlossary: 5     # 最多召回 5 条
topKTable: 3        # 最多召回 3 张表
```

合并时再次 limit：
```java
return merged.values().stream()
        .limit(ragConfig.getTopKExample())  // ← 再截断一次
        .toList();
```

---

## Token 数量估算

| 内容 | 数量 | 估算 token |
|------|------|------------|
| 5 条示例 | 650 字 | ~162 |
| 5 条术语 | 500 字 | ~125 |
| 3 张表 + 60 字段 | ~4500 字 | ~1125 |
| Prompt 模板 | ~600 字 | ~150 |
| **总计** | ~6200 字 | **~1562** |

---

## Context Window 情况

| 模型 | Context Window | 当前估算 | 状态 |
|------|----------------|---------|------|
| GPT-4o | 128K | ~2000 | ✅ 宽裕 |
| DeepSeek-chat | 64K | ~2000 | ✅ 宽裕 |
| Qwen2.5-32B | 32K | ~2000 | ✅ 宽裕 |

---

## 截断机制

**当前没有实现显式截断。**

```java
// 代码里没有：
// 1. 没有 token 计算
// 2. 没有基于 token 数量的截断
// 3. 没有基于优先级的裁剪策略
```

---

## 如果要做得完善

```java
// 优先级：示例 > 术语 > 表信息 > 字段描述
if (totalTokens > MAX_CONTEXT_TOKENS) {
    // 先删字段 description
    // 再删整张表
    // 最后删术语
}
```

---

## 总结

| 问题 | 答案 |
|------|------|
| 塞多少元数据？ | 最多 5 示例 + 5 术语 + 3 表（每表若干字段） |
| 计算过 token 吗？ | **没有显式计算**，只靠 topK 限制粗略控制 |
| 当前估算多少 token？ | 约 2000 token（远低于 32K-128K context window） |
| 超限了怎么截断？ | **当前没有实现截断** |
| 最大风险点？ | 字段多的表（如 50+ 字段）可能超过估算 |

---

## 问题五：结果摘要的调用方式与失败处理

### 问题
结果摘要也调 LLM，那这个调用是在 SQL 执行完之后同步等待吗？如果摘要生成失败，整个请求是失败还是只返回表格数据？

### 答案

**同步等待 + 失败即整个请求失败。**

---

## 调用链路

```java
// OrchestratorService.query()
QueryResultDTO queryResult = buildResult(queryContext, executionResult, datasourceId);
// buildResult 内部：
String summary = llmService.summarizeResults(...);  // ← 同步等待
return QueryResultDTO.builder()
        .summary(summary)
        // ...
```

---

## 失败处理

```java
// OpenAILLMService.summarizeResults()
try {
    String rawResponse = callLLMSafely(buildRequestBody(prompt));
    String content = extractContent(rawResponse);
    JsonNode root = objectMapper.readTree(content);
    JsonNode summaryNode = root.get("summary");
    if (summaryNode != null && !summaryNode.isNull()) {
        return summaryNode.asText();
    }
    return "无法生成摘要";
} catch (Exception e) {
    log.error("Failed to summarize results: {}", e.getMessage());
    throw new BusinessException(ErrorCode.LLM_PARSE_FAILED, "Failed to summarize: " + e.getMessage());
}
```

异常向上传播，整个请求失败。

---

## 失败场景分析

| 阶段 | 状态 | 结果 |
|------|------|------|
| SQL 生成 | ✅ 成功 | — |
| SQL 验证 | ✅ 成功 | — |
| SQL 执行 | ✅ 成功 | 返回了表格数据 |
| **摘要生成** | ❌ **失败** | **整个请求失败，表格数据丢失** |

---

## 当前设计的问题

**摘要生成失败时，用户看不到表格数据，只能看到错误。**

场景：
- 网络抖动导致摘要 LLM 调用超时
- LLM 服务临时不可用
- 返回 JSON 格式不符合预期

---

## 改进方案

```java
try {
    String summary = llmService.summarizeResults(question, sql, resultProfile);
} catch (Exception e) {
    log.warn("Summary generation failed, returning without summary", e);
    summary = null;  // 降级，不抛异常
}

return QueryResultDTO.builder()
        .summary(summary)  // 可能为 null
        .warnings(summary == null ? List.of("摘要生成失败，仅返回数据") : List.of())
        .build();
```

---

## 总结

| 问题 | 答案 |
|------|------|
| 摘要调用是同步还是异步？ | **同步**——阻塞等待 |
| 摘要失败整个请求失败吗？ | **是的**——抛异常向上传播，表格数据不会返回 |
| 有降级机制吗？ | **没有**——当前是"要么全有，要么全无" |
| 可以改进吗？ | 可以——让摘要失败时降级返回表格数据 + warning |

---

*文档生成时间：2026-04-21*
