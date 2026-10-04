# M5-Hotfix — Codex 审查缺陷修复

> 修复日期：2026-04-11

## 修复背景
基于 Codex 对 M5 代码的静态审查报告，修复所有 P0/P1 问题及部分 P2 问题。

## P0 修复
- [x] Task 1: application.yml JWT 明文默认密钥清除 + 启动时强制校验

## P1 修复
- [x] Task 2: LLM 线程池隔离 — 抽取 LLMAsyncExecutor Bean 解决 @Async 自调用失效
- [x] Task 3: DDLParserService — sp_addextendedproperty 注释写回修复
- [x] Task 4: DDLParserService — 括号感知的列分割（修复 DECIMAL(10,2) 拆断问题）
- [x] Task 5: OrgTreeMapper.xml — 递归 CTE 深度限制 + OrgService 成环校验
- [x] Task 6: OrgService — 缓存失效范围扩展至祖先链 + Redis 降级保护
- [x] Task 7: SecurityConfig — Org 只读接口权限下放 + 角色大小写统一
- [x] Task 8: MetadataController — /ddl/confirm 接口契约修复（DDLConfirmRequest）
- [x] Task 9: OrchestratorService/QueryController — 回退为 M5 TODO/501 状态

## P2 修复
- [x] Task 10: Mapper XML — JOIN active 过滤修复 + ILIKE 统一（查询不存在，未修改）
- [x] Task 11: OpenAILLMService — 超时配置化 + URL 拼接防双斜杠（LLMAsyncExecutor 实现）
- [x] Task 12: 新增关键单元测试（HTTP 5xx、JSON 解析失败、Rule1 合规验证）

## 测试结果
```
[INFO] Tests run: 96, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 架构红线合规（修复后）
| 红线 | 状态 |
|------|------|
| Rule 1: LLM只看元数据 | ✅ |
| Rule 2: SqlGuard不可绕过 | ✅ |
| Rule 3: 业务库只读 | ✅ |
| Rule 4: 凭据不硬编码 | ✅ |

## M6-READY 判定：✅
