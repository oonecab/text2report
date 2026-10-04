# Text2Report API 契约文档
版本：v1.0 | 生成于 M3 | 供后端 M4-M8 实现参考

## 通用约定

### 请求头
```
Authorization: Bearer {jwt_token}
Content-Type: application/json
X-Request-Id: {uuid}  # 链路追踪
```

### 标准响应格式
```json
{ "code": "SUCCESS" | 业务错误码, "message": "ok" | 错误描述, "data": T | null }
```

### 分页响应格式
```json
{ "code": "SUCCESS", "data": { "list": T[], "total": number, "page": number, "pageSize": number } }
```

## 错误码清单

| code | HTTP Status | 含义 |
|------|-------------|------|
| INVALID_CREDENTIALS | 401 | 用户名或密码错误 |
| UNAUTHORIZED | 401 | Token 无效或过期 |
| FORBIDDEN | 403 | 无权限访问该资源 |
| SQL_GUARD_REJECTED | 422 | SQL 安全校验未通过 |
| LOW_CONFIDENCE | 200 | LLM 置信度过低 |
| LLM_TIMEOUT | 504 | LLM 调用超时 |
| DDL_PARSE_ERROR | 200 | DDL 语法解析失败 |
| HAS_CHILDREN | 400 | 删除节点存在子节点 |
| CANNOT_DELETE_ROOT | 403 | 不允许删除根节点 |
| ORG_NOT_FOUND | 404 | 组织节点不存在 |
| TABLE_NOT_FOUND | 404 | 元数据表不存在 |
| GLOSSARY_NOT_FOUND | 404 | 术语不存在 |
| JOIN_NOT_FOUND | 404 | JOIN关系不存在 |
| EXAMPLE_NOT_FOUND | 404 | 示例不存在 |
| NOT_FOUND | 404 | 资源不存在 |

## 认证域 API

### POST /api/v1/auth/login
登录

**请求体：**
```json
{
  "username": "admin",
  "password": "password"
}
```

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "token": "mock-jwt-admin-u001",
    "refreshToken": "mock-refresh-admin-u001",
    "expiresIn": 86400,
    "user": {
      "id": "u001",
      "username": "admin",
      "displayName": "系统管理员",
      "role": "admin",
      "orgId": "org-root",
      "orgName": "华夏集团"
    }
  }
}
```

### POST /api/v1/auth/logout
登出

**响应：**
```json
{ "code": "SUCCESS", "data": null }
```

### GET /api/v1/auth/me
获取当前用户

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "id": "u001",
    "username": "admin",
    "displayName": "系统管理员",
    "role": "admin",
    "orgId": "org-root",
    "orgName": "华夏集团"
  }
}
```

### POST /api/v1/auth/refresh
刷新 Token

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "token": "mock-jwt-refreshed",
    "refreshToken": "mock-refresh-refreshed",
    "expiresIn": 86400
  }
}
```

## 查询域 API

### POST /api/v1/query
提交查询

**请求体：**
```json
{
  "question": "本月各部门毛利率对比",
  "orgId": "org-sub-1"
}
```

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "requestId": "req-monthly-bar-001",
    "sql": "SELECT TOP 1000 o.name AS 部门, ROUND(SUM(d.revenue-d.cost)/NULLIF(SUM(d.revenue),0)*100, 2) AS 毛利率 FROM gross_profit_detail d LEFT JOIN org_tree o ON d.org_id=o.id WHERE o.parent_id=2 AND YEAR(d.period_date)=YEAR(GETDATE()) GROUP BY o.name",
    "table": {
      "columns": ["部门", "毛利率(%)"],
      "rows": [["销售一部", 32.5], ["销售二部", 28.1], ["市场部", 35.7]]
    },
    "resultProfile": { "recommendedChart": "bar" },
    "summary": "本月各部门毛利率：市场部最高(35.7%)，销售二部最低(28.1%)，整体毛利率处于健康水平。",
    "warnings": [],
    "audit": { "confidence": 0.92 }
  }
}
```

### GET /api/v1/query/history
获取查询历史

**查询参数：** `current`, `pageSize`

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "list": [
      {
        "requestId": "req-history-001",
        "question": "本月各部门毛利对比",
        "orgId": "org-root",
        "orgName": "华夏集团",
        "status": "success",
        "recommendedChart": "bar",
        "rowCount": 12,
        "confidence": 0.92,
        "latencyMs": 1850,
        "createdAt": "2026-04-09T10:30:00Z",
        "isFavorited": false,
        "hasFeedback": false
      }
    ],
    "total": 15,
    "page": 1,
    "pageSize": 10
  }
}
```

### POST /api/v1/query/:requestId/feedback
提交反馈

**请求体：**
```json
{
  "rating": 2,
  "comment": "SQL 正确但摘要有误",
  "correctSql": ""
}
```

**响应：** `{ "code": "SUCCESS", "data": null }`

### GET /api/v1/query/favorites
获取收藏列表

**响应：**
```json
{
  "code": "SUCCESS",
  "data": [
    {
      "requestId": "req-fav-001",
      "question": "本月各部门毛利率对比",
      "orgId": "org-sub-1",
      "orgName": "华夏贸易有限公司",
      "recommendedChart": "bar",
      "rowCount": 12,
      "favoritedAt": "2026-04-01T10:30:00Z",
      "lastRunAt": "2026-04-08T14:22:00Z"
    }
  ]
}
```

### POST /api/v1/query/:requestId/favorite
添加收藏

**响应：** `{ "code": "SUCCESS", "data": null }`

### DELETE /api/v1/query/favorites/:requestId
取消收藏

**响应：** `{ "code": "SUCCESS", "data": null }`

## 组织域 API

### GET /api/v1/org/tree
获取组织树

**响应：**
```json
{
  "code": "SUCCESS",
  "data": [
    {
      "id": "org-root",
      "name": "华夏集团",
      "level": 1,
      "parentId": null,
      "isActive": true,
      "children": [
        {
          "id": "org-sub-1",
          "name": "华夏贸易有限公司",
          "level": 2,
          "parentId": "org-root",
          "isActive": true,
          "children": []
        }
      ]
    }
  ]
}
```

### GET /api/v1/org/:id/children
获取子节点

**响应：** `{ "code": "SUCCESS", "data": [...] }`

### POST /api/v1/org
创建组织节点

**请求体：**
```json
{
  "name": "新部门",
  "parentId": "org-sub-1",
  "level": 3
}
```

**响应：** `{ "code": "SUCCESS", "data": { "id": "org-new-100", ... } }`

### PUT /api/v1/org/:id
更新组织节点

**请求体：**
```json
{
  "name": "更新后的名称"
}
```

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### DELETE /api/v1/org/:id
删除组织节点

**响应：** `{ "code": "SUCCESS", "data": null }`

## 元数据域 API

### GET /api/v1/metadata/tables
获取表列表

**响应：**
```json
{
  "code": "SUCCESS",
  "data": [
    {
      "id": 1,
      "tableName": "gross_profit_detail",
      "label": "毛利明细表",
      "description": "按月记录各组织各产品的收入、成本、毛利数据",
      "schemaName": "dbo",
      "isActive": true
    }
  ]
}
```

### GET /api/v1/metadata/tables/:id/columns
获取字段列表

**响应：** `{ "code": "SUCCESS", "data": [...] }`

### PUT /api/v1/metadata/columns/:id
更新字段注释

**请求体：**
```json
{
  "label": "账期月份",
  "description": "格式YYYYMM"
}
```

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### POST /api/v1/metadata/ddl/parse
解析 DDL

**请求体：**
```json
{
  "ddl": "CREATE TABLE ..."
}
```

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "tableName": "gross_profit_detail",
    "schemaName": "dbo",
    "columns": [
      { "columnName": "id", "dataType": "INT", "description": "主键ID", "commentSource": "ddl" }
    ]
  }
}
```

### POST /api/v1/metadata/ddl/confirm
确认 DDL 写入

**请求体：**
```json
{
  "parsedTables": [...]
}
```

**响应：** `{ "code": "SUCCESS", "data": null }`

### GET /api/v1/metadata/glossary
获取术语列表（支持 keyword 搜索和分页）

**查询参数：** `keyword`, `page`, `pageSize`

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "list": [...],
    "total": 8,
    "page": 1,
    "pageSize": 10
  }
}
```

### POST /api/v1/metadata/glossary
创建术语

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### PUT /api/v1/metadata/glossary/:id
更新术语

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### DELETE /api/v1/metadata/glossary/:id
删除术语

**响应：** `{ "code": "SUCCESS", "data": null }`

### GET /api/v1/metadata/joins
获取 JOIN 关系列表

**响应：** `{ "code": "SUCCESS", "data": [...] }`

### POST /api/v1/metadata/joins
创建 JOIN 关系

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### PUT /api/v1/metadata/joins/:id
更新 JOIN 关系

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### DELETE /api/v1/metadata/joins/:id
删除 JOIN 关系

**响应：** `{ "code": "SUCCESS", "data": null }`

### GET /api/v1/metadata/examples
获取问答示例列表

**查询参数：** `enabled=true`

**响应：** `{ "code": "SUCCESS", "data": [...] }`

### POST /api/v1/metadata/examples
创建示例

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### PUT /api/v1/metadata/examples/:id
更新示例

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

### DELETE /api/v1/metadata/examples/:id
删除示例

**响应：** `{ "code": "SUCCESS", "data": null }`

### PATCH /api/v1/metadata/examples/:id/toggle
启用/禁用示例

**响应：** `{ "code": "SUCCESS", "data": { ... } }`

## 审计域 API

### GET /api/v1/audit/logs
获取审计日志（分页，支持多维度过滤）

**查询参数：**
- `page`, `pageSize`
- `startDate`, `endDate`
- `userId`, `orgId`
- `status`: success | failed | low_confidence
- `minConfidence`, `maxConfidence`

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "list": [
      {
        "requestId": "audit-0001",
        "userId": "u001",
        "username": "admin",
        "displayName": "系统管理员",
        "orgId": "org-root",
        "orgName": "华夏集团",
        "question": "本月各部门毛利率对比",
        "sqlSnippet": "SELECT TOP 1000 o.name AS 部门...",
        "status": "success",
        "confidence": 0.92,
        "promptTokens": 1500,
        "completionTokens": 250,
        "latencyMs": 1850,
        "rowCount": 12,
        "createdAt": "2026-04-09T10:30:00Z"
      }
    ],
    "total": 30,
    "page": 1,
    "pageSize": 10
  }
}
```

### GET /api/v1/audit/logs/:requestId
获取审计日志详情（仅管理员）

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    ...audit log fields,
    "fullSql": "SELECT TOP 1000 ...",
    "promptSummary": "本月各部门毛利率对比",
    "warnings": [],
    "retryCount": 0,
    "errorMessage": null
  }
}
```

### GET /api/v1/audit/stats
获取审计统计

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "totalQueries": 30,
    "successRate": 73,
    "avgLatencyMs": 1580,
    "avgConfidence": 0.84
  }
}
```
