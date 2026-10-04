# 数据源管理

## 概述

数据源管理模块用于管理系统中可访问的数据库连接配置。DDL 上传和元数据管理都需要指定目标数据源。

## 功能特性

- **CRUD 操作**：创建、读取、更新、删除数据源
- **多数据库支持**：PostgreSQL、MySQL、SQL Server
- **密码脱敏**：API 不返回密码，前端显示 `***`
- **软删除**：删除操作设置 `active=false`，不物理删除
- **只读标记**：区分只读和读写数据源

## 数据库表结构

```sql
CREATE TABLE meta_datasource (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    db_type     VARCHAR(50) NOT NULL,  -- postgresql, mysql, sqlserver
    jdbc_url    TEXT NOT NULL,
    username    VARCHAR(255),
    password    VARCHAR(255),  -- Base64 encoded
    description TEXT,
    read_only   BOOLEAN DEFAULT true,
    active      BOOLEAN DEFAULT true,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## API 接口

| Method | Endpoint | 说明 |
|--------|----------|------|
| GET | `/api/v1/datasources` | 获取数据源列表（可选 `?activeOnly=true`） |
| GET | `/api/v1/datasources/{id}` | 获取单个数据源详情 |
| POST | `/api/v1/datasources` | 创建数据源 |
| PUT | `/api/v1/datasources/{id}` | 更新数据源 |
| DELETE | `/api/v1/datasources/{id}` | 软删除数据源 |

### API 示例

```bash
# 登录获取 token
TOKEN=$(curl -s -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.token')

# 获取数据源列表
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/datasources

# 获取启用的数据源
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/datasources?activeOnly=true"

# 创建数据源
curl -s -X POST "http://localhost:8080/api/v1/datasources" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "生产库",
    "dbType": "postgresql",
    "jdbcUrl": "jdbc:postgresql://localhost:5432/production",
    "username": "postgres",
    "password": "secret123",
    "description": "生产环境数据库",
    "readOnly": true,
    "active": true
  }'

# 更新数据源
curl -s -X PUT "http://localhost:8080/api/v1/datasources/1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "生产库（新）",
    "dbType": "postgresql",
    "jdbcUrl": "jdbc:postgresql://localhost:5432/production",
    "username": "postgres",
    "description": "更新后的描述"
  }'

# 删除数据源（软删除）
curl -s -X DELETE "http://localhost:8080/api/v1/datasources/1" \
  -H "Authorization: Bearer $TOKEN"
```

## 页面路径

- `/admin/datasources` - 数据源管理列表页

## 前端组件

| 组件 | 文件路径 | 说明 |
|------|----------|------|
| DataSourceListPage | `frontend/src/pages/admin/DataSourceListPage.tsx` | 数据源列表页面 |
| DataSourceForm | `frontend/src/components/admin/DataSourceForm.tsx` | 数据源表单组件 |

## 后端服务

| 类 | 文件路径 | 说明 |
|----|----------|------|
| DataSourceController | `backend/src/main/java/.../controller/DataSourceController.java` | REST API 控制器 |
| DataSourceService | `backend/src/main/java/.../service/DataSourceService.java` | 业务逻辑 |
| DataSourceDTO | `backend/src/main/java/.../model/dto/DataSourceDTO.java` | 传输对象 |

## DDL 上传与数据源关联

### 流程

1. 用户在 DDL 上传页面选择目标数据源
2. 解析 DDL 时，指定 `datasourceId`
3. 确认保存时，验证数据源存在且 `active=true`
4. 解析的表和字段元数据关联到选定的数据源

### 相关 API 变更

**解析 DDL**：
```json
POST /api/v1/metadata/ddl/parse
{
  "rawDdl": "CREATE TABLE ...",
  "datasourceId": 1
}
```

**确认保存**：
```json
POST /api/v1/metadata/ddl/confirm
{
  "datasourceId": 1,
  "tables": [...]
}
```

### 数据源验证

`MetadataController.confirmDDL` 方法会在保存前验证：

```java
if (request.getDatasourceId() != null) {
    MetaDatasource ds = metaDatasourceMapper.selectById(request.getDatasourceId());
    if (ds == null || !Boolean.TRUE.equals(ds.getActive())) {
        throw new BusinessException(ErrorCode.PARAM_INVALID, "选中的数据源不存在或已停用");
    }
}
```

## 安全考虑

1. **密码加密**：存储时使用 Base64 编码（简单加密，非生产级加密）
2. **密码不返回**：API 响应中密码字段始终为 `null`
3. **管理员权限**：所有数据源 CRUD 操作需要 `ADMIN` 角色
4. **软删除保护**：避免误删影响已有的表元数据关联

## 初始数据

系统初始化时，需要在 `meta_datasource` 表中插入默认数据源：

```sql
INSERT INTO meta_datasource (name, db_type, jdbc_url, username, description, read_only, active)
VALUES (
    'text2report',
    'postgresql',
    'jdbc:postgresql://db.example.invalid:5432/text2report',
    'postgres',
    '业务数据库',
    true,
    true
);
```

## 变更记录

| 日期 | 变更内容 |
|------|----------|
| 2026-04-12 | 新增数据源管理模块（CRUD + DDL 上传集成） |
