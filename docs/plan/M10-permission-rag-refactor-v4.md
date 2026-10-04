# M10 — 权限架构重构 + RAG 向量检索 完整开发清单 (v4)

> 本文件供 Codex 直接消费，按顺序执行即可。
> 三大核心变更：
> 1. 废弃 `org_tree`，在元数据库创建与业务库结构一致的 `dim_org`，通过同步接口保持一致
> 2. OrgTreePage 重构为「组织人员管理」：选中组织节点 → 右侧展示/配置该组织下的系统账号
> 3. 新增 RAG 向量检索子系统：pgvector + EmbeddingService，元数据保存时自动向量化
>
> **v4 变更说明（相对 v3）**：
> - 向量化模型由 OpenAI `text-embedding-3-small`（1536 维）改为**本地 BGE-M3 模型**（1024 维）
> - LLM 推理（SQL 生成、摘要等）保持 DeepSeek，不变
> - `meta_embedding` 表 `embedding` 列维度从 1536 改为 **1024**
> - `OpenAILLMService.generateEmbedding()` 改为调用本地 BGE-M3 HTTP 服务（`BgeEmbeddingService`）
> - `application.yml` 相关配置更新
> - 风险表格中维度描述同步更新

---

## 〇、架构决策记录（ADR）

### ADR-1：废弃 org_tree，在元数据库创建 dim_org

**背景**：当前元数据库 `text2report-meta` 中的 `org_tree` 与业务库 `text2report` 中的 `dim_org` 结构不同、数据各自维护，存在不一致风险。

**决策**：
- 删除 `org_tree` 表
- 在 `text2report-meta` 中创建结构与业务库完全一致的 `dim_org` 表（字段名、类型、约束一致，额外增加 `synced_at` 字段记录同步时间）
- 新增同步接口 `POST /api/v1/orgs/sync`，由 admin 手动触发，从业务库拉取全量 `dim_org` 数据写入元数据库
- OrgService 仍然查元数据库的 `dim_org`（`metaDataSource`），**不跨库查询**
- `sys_user_org.org_id`、`audit_query_log.org_id`、`meta_table.org_scope` 等均引用元数据库 `dim_org.id`，可保留物理 FK
- 唯一的跨库操作仅在 `syncFromBusiness()` 中发生，使用 `businessJdbcTemplate` 只读拉取

**好处**：
- 元数据库自治，OrgMapper 保持使用 `metaSqlSessionFactory`，无跨数据源切换
- `sys_user_org.org_id` 可以做物理 FK 约束（同库）
- 查询热路径上不涉及跨库，性能无影响

**同步策略**：
- 手动触发（admin 在管理后台点击「同步组织」按钮）
- DDL 上传确认时可选触发（若检测到 dim_org 相关 DDL）
- 同步逻辑：全量拉取业务库 `dim_org` → UPSERT 到元数据库 `dim_org` → 业务库有但元数据库没有的新增，已有的更新，业务库删除的标记 `is_active=false`

### ADR-2：OrgTreePage 变为「组织人员管理」

**决策**：左侧只读展示元数据库 `dim_org` 树；右侧展示选中组织节点关联的系统账号（通过 `sys_user_org`），支持添加/移除/修改 scope。

### ADR-3：引入 pgvector 实现 RAG 向量检索（本地 BGE-M3）

**背景**：当前 `MetadataService.retrieveForPrompt()` 使用关键词 ILIKE 匹配来检索相关元数据。当元数据量增长或用户问题表述多样时，关键词匹配召回率不足。

**决策**：
- 在 `text2report-meta` 库安装 `pgvector` 扩展
- 新增 `meta_embedding` 表存储向量（**1024 维，对应 BAAI/bge-m3 模型输出**）
- 新增 `EmbeddingService` 调用本地 BGE-M3 HTTP 推理服务生成向量
- 本地环境使用 `BAAI/bge-m3`（1024 维）做向量化；LLM 推理（SQL 生成、摘要）继续使用 DeepSeek，两者职责分离
- 问答示例（`meta_sql_example`）和术语词典（`meta_glossary`）在保存/更新时自动触发向量化
- `MetadataService.retrieveForPrompt()` 改为「向量检索 + 关键词兜底」的混合策略
- 向量化调用通过 `LLMService.generateEmbedding()` 接口隔离；未来切换其他本地模型（如 `bge-large-zh`）或 OpenAI，**只需修改实现类和维度配置**，上层零改动

---

## 一、阶段 A — 数据库变更（全部在 `text2report-meta` 上执行）

### A-1. 废弃 org_tree，创建 dim_org

```sql
-- ============================================
-- 步骤 1：移除 org_tree 的所有 FK 引用
-- ============================================
ALTER TABLE meta_datasource DROP CONSTRAINT IF EXISTS meta_datasource_org_id_fkey;
ALTER TABLE audit_query_log DROP CONSTRAINT IF EXISTS audit_query_log_org_id_fkey;

-- ============================================
-- 步骤 2：删除 org_tree 表
-- ============================================
DROP INDEX IF EXISTS idx_org_tree_parent;
DROP TABLE IF EXISTS org_tree CASCADE;

-- ============================================
-- 步骤 3：创建 dim_org（与业务库结构完全一致，额外增加 synced_at）
-- ============================================
CREATE TABLE dim_org (
    id          INTEGER PRIMARY KEY,           -- 非 SERIAL，与业务库保持一致，手工指定
    org_name    VARCHAR(100) NOT NULL,
    parent_id   INTEGER NULL REFERENCES dim_org(id) ON DELETE RESTRICT,
    org_level   SMALLINT NOT NULL,             -- 1=母公司 2=子公司 3=部门
    org_code    VARCHAR(50) NOT NULL UNIQUE,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    synced_at   TIMESTAMP                      -- 最近同步时间（元数据库独有字段）
);

COMMENT ON TABLE dim_org IS '组织维表（从业务库同步），结构与 text2report.dim_org 一致';
COMMENT ON COLUMN dim_org.synced_at IS '最近一次从业务库同步写入的时间';

CREATE INDEX idx_dim_org_parent ON dim_org(parent_id);
CREATE INDEX idx_dim_org_code ON dim_org(org_code);
CREATE INDEX idx_dim_org_level ON dim_org(org_level);

-- ============================================
-- 步骤 4：初始化数据（与业务库 dim_org 一致）
-- ============================================
INSERT INTO dim_org (id, org_name, parent_id, org_level, org_code, is_active, synced_at) VALUES
(1,  '母公司',   NULL, 1, 'ORG-HQ',         TRUE, NOW()),
(2,  '子公司A',   1,   2, 'ORG-A',          TRUE, NOW()),
(3,  '子公司B',   1,   2, 'ORG-B',          TRUE, NOW()),
(4,  '子公司C',   1,   2, 'ORG-C',          TRUE, NOW()),
(11, '销售部',    2,   3, 'ORG-A-SALES',    TRUE, NOW()),
(12, '生产部',    2,   3, 'ORG-A-PROD',     TRUE, NOW()),
(13, '市场部',    2,   3, 'ORG-A-MKT',      TRUE, NOW()),
(14, '综合部',    2,   3, 'ORG-A-ADMIN',    TRUE, NOW()),
(21, '销售部',    3,   3, 'ORG-B-SALES',    TRUE, NOW()),
(22, '生产部',    3,   3, 'ORG-B-PROD',     TRUE, NOW()),
(23, '市场部',    3,   3, 'ORG-B-MKT',      TRUE, NOW()),
(24, '综合部',    3,   3, 'ORG-B-ADMIN',    TRUE, NOW()),
(31, '销售部',    4,   3, 'ORG-C-SALES',    TRUE, NOW()),
(32, '生产部',    4,   3, 'ORG-C-PROD',     TRUE, NOW()),
(33, '市场部',    4,   3, 'ORG-C-MKT',      TRUE, NOW()),
(34, '综合部',    4,   3, 'ORG-C-ADMIN',    TRUE, NOW());

-- ============================================
-- 步骤 5：重建 FK 引用（改指向 dim_org）
-- ============================================
ALTER TABLE meta_datasource
    ADD CONSTRAINT meta_datasource_org_id_fkey
    FOREIGN KEY (org_id) REFERENCES dim_org(id);

ALTER TABLE audit_query_log
    ADD CONSTRAINT audit_query_log_org_id_fkey
    FOREIGN KEY (org_id) REFERENCES dim_org(id);
```

**验收**：
- [ ] `text2report-meta` 中无 `org_tree` 表
- [ ] `dim_org` 有 16 行数据，结构和业务库一致（多一个 `synced_at` 字段）
- [ ] `meta_datasource.org_id` FK 指向 `dim_org`
- [ ] `audit_query_log.org_id` FK 指向 `dim_org`

---

### A-2. 新增 sys_user 表

```sql
CREATE TABLE sys_user (
    id              SERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(200) NOT NULL,
    display_name    VARCHAR(100),
    role            VARCHAR(20) NOT NULL DEFAULT 'USER'
                    CHECK (role IN ('ADMIN', 'MANAGER', 'USER')),
    email           VARCHAR(200),
    phone           VARCHAR(20),
    is_active       BOOLEAN DEFAULT TRUE,
    last_login_at   TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    created_by      VARCHAR(50),
    updated_at      TIMESTAMP DEFAULT NOW(),
    updated_by      VARCHAR(50)
);
COMMENT ON TABLE sys_user IS '系统账号表';
COMMENT ON COLUMN sys_user.role IS 'ADMIN=超管(手动INSERT), MANAGER=组织管理员, USER=查询用户';
CREATE INDEX idx_sys_user_role ON sys_user(role);
CREATE INDEX idx_sys_user_active ON sys_user(is_active) WHERE is_active = TRUE;
```

---

### A-3. 新增 sys_user_org 表

```sql
CREATE TABLE sys_user_org (
    id          SERIAL PRIMARY KEY,
    user_id     INT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    org_id      INT NOT NULL REFERENCES dim_org(id) ON DELETE RESTRICT,
    scope       VARCHAR(20) NOT NULL DEFAULT 'SELF'
                CHECK (scope IN ('SELF', 'CHILDREN', 'ALL')),
    is_primary  BOOLEAN DEFAULT FALSE,
    granted_by  VARCHAR(50),
    granted_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE (user_id, org_id)
);
COMMENT ON TABLE sys_user_org IS '账号-机构权限映射表';
COMMENT ON COLUMN sys_user_org.scope IS 'SELF=仅本节点, CHILDREN=本节点+直接子节点, ALL=本节点+全部后代';
COMMENT ON COLUMN sys_user_org.is_primary IS '默认组织，登录后 OrgSelector 自动选中';
CREATE INDEX idx_suo_user ON sys_user_org(user_id);
CREATE INDEX idx_suo_org ON sys_user_org(org_id);
```

注意：`org_id` 有物理 FK 指向 `dim_org(id)`，因为都在同一个元数据库内。

---

### A-4. 新增 audit_operation_log 表

```sql
CREATE TABLE audit_operation_log (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id         INT REFERENCES sys_user(id),
    operation_type  VARCHAR(30) NOT NULL,
    target_type     VARCHAR(30),
    target_id       VARCHAR(50),
    detail          JSONB,
    ip_address      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT NOW()
);
COMMENT ON COLUMN audit_operation_log.operation_type
    IS 'USER_CREATE/USER_DEACTIVATE/ORG_GRANT/ORG_REVOKE/ORG_SYNC/EMBEDDING_SYNC 等';
CREATE INDEX idx_aol_type ON audit_operation_log(operation_type);
CREATE INDEX idx_aol_user ON audit_operation_log(user_id);
CREATE INDEX idx_aol_created ON audit_operation_log(created_at DESC);
```

---

### A-5. 安装 pgvector 扩展 + 新增向量存储表（⚠️ v4 修改点）

```sql
-- 步骤 1：安装扩展（需要 superuser 或 rds_superuser）
CREATE EXTENSION IF NOT EXISTS vector;

-- 步骤 2：向量存储表
-- ⚠️ v4 变更：embedding 维度从 1536 改为 1024，对应本地 BAAI/bge-m3 模型输出维度
CREATE TABLE meta_embedding (
    id              SERIAL PRIMARY KEY,
    source_type     VARCHAR(30) NOT NULL,
    -- 值域: sql_example / glossary / table_description / column_description
    source_id       INT NOT NULL,
    content_text    TEXT NOT NULL,
    embedding       vector(1024) NOT NULL,          -- ⚠️ BGE-M3 本地模型，1024 维
    model_version   VARCHAR(50) DEFAULT 'bge-m3',  -- ⚠️ 默认模型标识改为 bge-m3
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE (source_type, source_id)
);

-- 步骤 3：HNSW 索引（向量近似最近邻检索）
-- BGE-M3 使用余弦相似度，ops 保持 vector_cosine_ops 不变
CREATE INDEX idx_meta_embedding_vec ON meta_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX idx_meta_embedding_type ON meta_embedding(source_type);

COMMENT ON TABLE meta_embedding IS 'RAG 向量存储表（本地 BAAI/bge-m3，1024 维）';
COMMENT ON COLUMN meta_embedding.content_text IS '被向量化的原始文本';
COMMENT ON COLUMN meta_embedding.embedding IS '1024 维（本地 BAAI/bge-m3），切 OpenAI 后需重建为 1536 维';
COMMENT ON COLUMN meta_embedding.model_version IS '向量化模型标识，切换模型后须全量重建';
```

**验收**：
- [ ] `SELECT * FROM pg_extension WHERE extname = 'vector'` 返回一行
- [ ] HNSW 索引创建成功，`embedding vector(1024)` 列类型正确
- [ ] 测试 INSERT（1024 维向量）+ cosine distance 查询正常
- [ ] 插入 1536 维向量时 PostgreSQL 报维度不匹配错误（维度约束生效）

---

### A-6. 种子数据

```sql
-- admin（BCrypt 哈希由应用层生成后填入）
INSERT INTO sys_user (username, password_hash, display_name, role, created_by)
VALUES ('admin', '$2a$10$PLACEHOLDER_FOR_admin123', '系统管理员', 'ADMIN', 'system');

INSERT INTO sys_user_org (user_id, org_id, scope, is_primary, granted_by)
VALUES ((SELECT id FROM sys_user WHERE username = 'admin'), 1, 'ALL', TRUE, 'system');

-- demo 查询用户
INSERT INTO sys_user (username, password_hash, display_name, role, created_by)
VALUES ('demo', '$2a$10$PLACEHOLDER_FOR_demo123', '演示用户', 'USER', 'admin');

INSERT INTO sys_user_org (user_id, org_id, scope, is_primary, granted_by)
VALUES ((SELECT id FROM sys_user WHERE username = 'demo'), 2, 'ALL', TRUE, 'admin');
```

Codex 执行时须先用 `BCryptPasswordEncoder.encode("admin123")` 和 `encode("demo123")` 生成真实哈希替换 `PLACEHOLDER`。

---

## 二、阶段 B — 后端改造

### B-1. OrgService 改为查元数据库 dim_org

**核心变更**：将所有 `org_tree` 引用改为 `dim_org`，字段映射变更。OrgService 仍然使用 `metaDataSource`，不跨库。

| 原 org_tree 字段 | 新 dim_org 字段 | 说明 |
|---|---|---|
| `name` | `org_name` | 组织名称 |
| `level` | `org_level` | 层级 |
| `code` | `org_code` | 编码 |
| `is_active` | `is_active` | 不变 |
| `parent_id` | `parent_id` | 不变 |
| `id` | `id` | 不变 |
| `created_at` | — | dim_org 无此字段 |
| — | `synced_at` | 元数据库独有 |

**OrgTreeMapper.xml 改写**（仍使用 `metaSqlSessionFactory`）：

```xml
<!-- getFullTree -->
<select id="selectAll" resultType="com.text2report.model.entity.DimOrg">
    SELECT id, org_name, parent_id, org_level, org_code, is_active, synced_at
    FROM dim_org
    WHERE is_active = TRUE
    ORDER BY org_level, id
</select>

<!-- resolveSubtreeIds -->
<select id="selectSubtreeIds" resultType="java.lang.Integer">
    WITH RECURSIVE subtree AS (
        SELECT id FROM dim_org WHERE id = #{orgId} AND is_active = TRUE
        UNION ALL
        SELECT c.id FROM dim_org c
        INNER JOIN subtree p ON c.parent_id = p.id
        WHERE c.is_active = TRUE
    )
    SELECT id FROM subtree
</select>

<!-- resolveAncestorPath -->
<select id="selectAncestorPath" resultType="java.lang.String">
    WITH RECURSIVE ancestors AS (
        SELECT id, org_name, parent_id FROM dim_org WHERE id = #{orgId}
        UNION ALL
        SELECT p.id, p.org_name, p.parent_id FROM dim_org p
        INNER JOIN ancestors a ON a.parent_id = p.id
    )
    SELECT STRING_AGG(org_name, ' > ' ORDER BY id) FROM ancestors
</select>

<!-- insertWithId (用于 syncFromBusiness) -->
<insert id="insertWithId">
    INSERT INTO dim_org (id, org_name, parent_id, org_level, org_code, is_active, synced_at)
    VALUES (#{id}, #{orgName}, #{parentId}, #{orgLevel}, #{orgCode}, #{isActive}, #{syncedAt})
</insert>

<!-- updateById -->
<update id="updateById">
    UPDATE dim_org SET
        org_name = #{orgName},
        parent_id = #{parentId},
        org_level = #{orgLevel},
        org_code = #{orgCode},
        is_active = #{isActive},
        synced_at = #{syncedAt}
    WHERE id = #{id}
</update>

<!-- deactivate -->
<update id="deactivate">
    UPDATE dim_org SET is_active = FALSE, synced_at = NOW() WHERE id = #{id}
</update>
```

**OrgService 方法清理**：

```
保留（改查 dim_org）：
  - getFullOrgTree() → 查 dim_org 构建嵌套树
  - resolveSubtreeIds(orgId) → 递归 CTE 查 dim_org + Redis 缓存
  - resolveOrgPath(orgId) → 祖先路径

删除：
  - createOrg()
  - updateOrg()
  - deactivateOrg()

新增：
  - syncFromBusiness() → 从业务库拉取 dim_org，UPSERT 到元数据库 dim_org
  - getOrgTreeForUser(userId) → 结合 sys_user_org 返回用户有权限的子树
  - getUsersByOrgId(orgId) → 查 sys_user_org JOIN sys_user
```

**syncFromBusiness() 完整实现**：

```java
@Transactional(transactionManager = "metaTransactionManager")
public OrgSyncResult syncFromBusiness() {
    // 1. 从业务库读取 dim_org 全表（使用 businessJdbcTemplate，只读）
    List<DimOrg> businessOrgs = businessJdbcTemplate.query(
        "SELECT id, org_name, parent_id, org_level, org_code, is_active FROM dim_org",
        dimOrgRowMapper);

    // 2. 读取元数据库当前 dim_org
    List<DimOrg> metaOrgs = dimOrgMapper.selectAll();
    Map<Integer, DimOrg> metaMap = metaOrgs.stream()
        .collect(Collectors.toMap(DimOrg::getId, Function.identity()));

    int inserted = 0, updated = 0, deactivated = 0;
    Set<Integer> businessIds = new HashSet<>();

    // 3. UPSERT：业务库有的 → 新增或更新
    LocalDateTime now = LocalDateTime.now();
    for (DimOrg biz : businessOrgs) {
        businessIds.add(biz.getId());
        DimOrg existing = metaMap.get(biz.getId());
        biz.setSyncedAt(now);
        if (existing == null) {
            dimOrgMapper.insertWithId(biz);
            inserted++;
        } else if (needsUpdate(existing, biz)) {
            dimOrgMapper.updateById(biz);
            updated++;
        }
    }

    // 4. 标记停用：元数据库有但业务库没有的
    for (DimOrg meta : metaOrgs) {
        if (!businessIds.contains(meta.getId()) && meta.getIsActive()) {
            dimOrgMapper.deactivate(meta.getId());
            deactivated++;
        }
    }

    // 5. 清除 Redis 缓存
    Set<String> keys = redisTemplate.keys("t2r:org:*");
    if (keys != null && !keys.isEmpty()) {
        redisTemplate.delete(keys);
    }

    // 6. 审计
    auditService.logOperation("ORG_SYNC", "SYSTEM", null,
        Map.of("inserted", inserted, "updated", updated,
               "deactivated", deactivated, "total", businessOrgs.size()));

    return new OrgSyncResult(inserted, updated, deactivated, businessOrgs.size());
}

private boolean needsUpdate(DimOrg existing, DimOrg biz) {
    return !Objects.equals(existing.getOrgName(), biz.getOrgName())
        || !Objects.equals(existing.getParentId(), biz.getParentId())
        || !Objects.equals(existing.getOrgLevel(), biz.getOrgLevel())
        || !Objects.equals(existing.getOrgCode(), biz.getOrgCode())
        || !Objects.equals(existing.getIsActive(), biz.getIsActive());
}
```

注意：`businessJdbcTemplate` 使用 `@Qualifier("businessJdbcTemplate")` 注入，从只读业务数据源读取。这是唯一一处跨库读取，且只用于 admin 触发的同步操作，不在查询热路径上。

**Entity 变更**：

```java
@Data
@TableName("dim_org")
public class DimOrg {
    @TableId(type = IdType.INPUT)  // 非自增，与业务库保持一致
    private Integer id;
    private String orgName;
    private Integer parentId;
    private Integer orgLevel;
    private String orgCode;
    private Boolean isActive;
    private LocalDateTime syncedAt;
}
```

**验收**：
- [ ] `OrgService.getFullOrgTree()` 返回 16 个节点的正确树结构
- [ ] `OrgService.resolveSubtreeIds(2)` 返回 `[2, 11, 12, 13, 14]`
- [ ] `OrgService.syncFromBusiness()` 从业务库拉取并写入元数据库
- [ ] 同步后 Redis 缓存已清除
- [ ] 所有 createOrg/updateOrg/deactivateOrg 方法已删除

---

### B-2. 新增 Entity + Mapper + DTO

| 文件 | 说明 |
|------|------|
| `entity/DimOrg.java` | 映射元数据库 `dim_org`，替代原 `OrgTree.java` |
| `entity/SysUser.java` | 映射 `sys_user` |
| `entity/SysUserOrg.java` | 映射 `sys_user_org` |
| `entity/MetaEmbedding.java` | 映射 `meta_embedding` |
| `dto/UserDTO.java` | 用户信息（不含 password_hash，含 orgGrants） |
| `dto/UserCreateRequest.java` | `@NotBlank username, password` |
| `dto/UserUpdateRequest.java` | 更新用户信息 |
| `dto/UserOrgGrantRequest.java` | `userId, orgId, scope, isPrimary` |
| `dto/OrgUserVO.java` | 组织下用户视图 |
| `dto/OrgSyncResult.java` | 同步结果 `inserted, updated, deactivated, total` |
| `dto/EmbeddingSyncResult.java` | 向量化同步结果 |
| `mapper/DimOrgMapper.java + .xml` | selectAll, selectSubtreeIds, selectAncestorPath, insertWithId, updateById, deactivate |
| `mapper/SysUserMapper.java + .xml` | selectByUsername, selectById, insert, updateById, list |
| `mapper/SysUserOrgMapper.java + .xml` | selectByUserId, selectByOrgId, selectPrimaryOrg, insert, deleteByUserAndOrg |
| `mapper/MetaEmbeddingMapper.java + .xml` | upsertEmbedding, searchSimilar, deleteBySource, countByType |

**MetaEmbedding Entity**（⚠️ v4：注释更新，结构不变）：

```java
@Data
@TableName("meta_embedding")
public class MetaEmbedding {
    private Integer id;
    private String sourceType;
    private Integer sourceId;
    private String contentText;
    // pgvector 类型在 Java 侧用 float[] 映射，通过 TypeHandler 处理
    // ⚠️ v4：BGE-M3 输出 1024 维，数据库列 vector(1024)
    private float[] embedding;
    private String modelVersion;  // 默认值 "bge-m3"，由数据库 DEFAULT 写入
    @TableField(exist = false)
    private Double similarity;    // searchSimilar 查询时的计算结果，非持久化字段
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**MetaEmbeddingMapper 关键 SQL**（无需修改，维度由 DDL 层约束）：

```xml
<select id="searchSimilar" resultType="com.text2report.model.entity.MetaEmbedding">
    SELECT id, source_type, source_id, content_text,
           1 - (embedding &lt;=&gt; #{queryVector}::vector) AS similarity
    FROM meta_embedding
    WHERE source_type = #{sourceType}
    ORDER BY embedding &lt;=&gt; #{queryVector}::vector
    LIMIT #{topK}
</select>

<insert id="upsertEmbedding">
    INSERT INTO meta_embedding (source_type, source_id, content_text, embedding, model_version, updated_at)
    VALUES (#{sourceType}, #{sourceId}, #{contentText}, #{embedding}::vector, #{modelVersion}, NOW())
    ON CONFLICT (source_type, source_id) DO UPDATE
    SET content_text = EXCLUDED.content_text,
        embedding = EXCLUDED.embedding,
        model_version = EXCLUDED.model_version,
        updated_at = NOW()
</insert>

<delete id="deleteBySource">
    DELETE FROM meta_embedding WHERE source_type = #{sourceType} AND source_id = #{sourceId}
</delete>

<select id="countByType" resultType="map">
    SELECT source_type, COUNT(*) AS cnt FROM meta_embedding GROUP BY source_type
</select>
```

---

### B-3. 新增 UserService

```java
public interface UserService {
    UserDTO createUser(UserCreateRequest req, String operatorUsername);
    UserDTO updateUser(Integer userId, UserUpdateRequest req, String operatorUsername);
    void deactivateUser(Integer userId, String operatorUsername);
    void activateUser(Integer userId, String operatorUsername);
    UserDTO getUserById(Integer userId);
    PageResult<UserDTO> listUsers(String role, Boolean isActive, Integer orgId, int page, int size);

    void grantOrgAccess(UserOrgGrantRequest req, String operatorUsername);
    void revokeOrgAccess(Integer userId, Integer orgId, String operatorUsername);
    List<SysUserOrg> getUserOrgGrants(Integer userId);
    List<OrgUserVO> getUsersByOrgId(Integer orgId);

    List<Integer> resolveAccessibleOrgIds(Integer userId);
    boolean hasAccessToOrg(Integer userId, Integer orgId);

    void changePassword(Integer userId, String newPassword, String operatorUsername);
}
```

**resolveAccessibleOrgIds 逻辑**：

```
1. user = sysUserMapper.selectById(userId)
2. if (user.role == 'ADMIN') → return dimOrgMapper.selectAllIds()
3. grants = sysUserOrgMapper.selectByUserId(userId)
4. Set<Integer> result = new HashSet<>()
5. for each grant in grants:
     switch (grant.scope) {
       case SELF:
         result.add(grant.orgId);
         break;
       case CHILDREN:
         result.add(grant.orgId);
         // 直接子节点（parent_id = grant.orgId）
         result.addAll(dimOrgMapper.selectDirectChildrenIds(grant.orgId));
         break;
       case ALL:
         // 递归子树全部
         result.addAll(orgService.resolveSubtreeIds(grant.orgId));
         break;
     }
6. return new ArrayList<>(result)
```

**权限边界校验**：

```
createUser(req, operator):
  opUser = sysUserMapper.selectByUsername(operator)
  if (opUser.role == 'ADMIN'):
    允许创建任何角色
  else if (opUser.role == 'MANAGER'):
    if (req.role != 'USER') throw 403 "MANAGER 只能创建 USER"
    // 后续 grantOrgAccess 时验证 orgId 在 MANAGER 的 accessibleOrgIds 内
  else:
    throw 403

grantOrgAccess(req, operator):
  opUser = sysUserMapper.selectByUsername(operator)
  if (opUser.role == 'MANAGER'):
    accessible = resolveAccessibleOrgIds(opUser.id)
    if (req.orgId NOT IN accessible) throw 403
```

**验收**：
- [ ] ADMIN 创建 MANAGER 成功
- [ ] ADMIN 创建 USER 成功
- [ ] MANAGER 创建 USER 成功（限本组织）
- [ ] MANAGER 创建 ADMIN 返回 403
- [ ] MANAGER 创建 MANAGER 返回 403
- [ ] USER 无法访问用户管理接口
- [ ] SELF scope 返回单节点
- [ ] CHILDREN scope 返回节点+直接子节点
- [ ] ALL scope 返回节点+全部后代
- [ ] ADMIN 的 accessibleOrgIds 为全部 dim_org id
- [ ] 回收权限后立即生效
- [ ] 审计日志记录每次权限变更

---

### B-4. 新增 EmbeddingService（RAG 核心）

```java
@Service
@Slf4j
public class EmbeddingService {

    @Autowired private LLMService llmService;
    @Autowired private MetaEmbeddingMapper embeddingMapper;
    @Autowired private MetaSqlExampleMapper sqlExampleMapper;
    @Autowired private MetaGlossaryMapper glossaryMapper;
    @Autowired private MetaTableMapper metaTableMapper;
    @Autowired private MetaColumnMapper metaColumnMapper;
    @Autowired private LLMConfig llmConfig;
    @Autowired @Qualifier("llmExecutor") private Executor llmExecutor;

    /**
     * 为单条元数据生成 embedding 并 upsert
     */
    public void embedAndStore(String sourceType, Integer sourceId, String contentText) {
        try {
            float[] vector = llmService.generateEmbedding(contentText);
            embeddingMapper.upsertEmbedding(
                sourceType, sourceId, contentText, vector, llmConfig.getEmbeddingModel());
        } catch (Exception e) {
            log.error("Embedding failed for {}:{}", sourceType, sourceId, e);
            // 失败不抛出，避免影响主流程
        }
    }

    /**
     * 异步向量化（用于保存后自动触发）
     */
    public void embedAndStoreAsync(String sourceType, Integer sourceId, String contentText) {
        CompletableFuture.runAsync(
            () -> embedAndStore(sourceType, sourceId, contentText), llmExecutor);
    }

    /**
     * 全量同步
     */
    public EmbeddingSyncResult syncAll() {
        int examples = syncAllExamples();
        int glossary = syncAllGlossary();
        int tables   = syncAllTableDescriptions();
        return new EmbeddingSyncResult(examples, glossary, tables);
    }

    /**
     * 向量检索
     */
    public List<MetaEmbedding> searchSimilar(String query, String sourceType, int topK) {
        try {
            float[] queryVector = llmService.generateEmbedding(query);
            return embeddingMapper.searchSimilar(queryVector, topK, sourceType);
        } catch (Exception e) {
            log.warn("Vector search failed, falling back to keyword", e);
            return Collections.emptyList();
        }
    }

    /**
     * 统计各类型向量数量
     */
    public Map<String, Long> getStats() {
        List<Map<String, Object>> rows = embeddingMapper.countByType();
        Map<String, Long> result = new HashMap<>();
        long total = 0;
        for (Map<String, Object> r : rows) {
            String type = (String) r.get("source_type");
            Long cnt = ((Number) r.get("cnt")).longValue();
            result.put(type, cnt);
            total += cnt;
        }
        result.put("total", total);
        return result;
    }

    private int syncAllExamples() {
        List<MetaSqlExample> examples = sqlExampleMapper.selectActiveList();
        for (MetaSqlExample ex : examples) {
            String text = "问题: " + ex.getQuestion() + "\nSQL: " + ex.getSql();
            embedAndStore("sql_example", ex.getId(), text);
        }
        return examples.size();
    }

    private int syncAllGlossary() {
        List<MetaGlossary> terms = glossaryMapper.selectActiveList();
        for (MetaGlossary g : terms) {
            String text = "术语: " + g.getTerm() + "\n定义: " + g.getDefinition();
            if (g.getSqlSnippet() != null) {
                text += "\nSQL: " + g.getSqlSnippet();
            }
            embedAndStore("glossary", g.getId(), text);
        }
        return terms.size();
    }

    private int syncAllTableDescriptions() {
        List<MetaTable> tables = metaTableMapper.selectActiveList();
        for (MetaTable t : tables) {
            List<MetaColumn> cols = metaColumnMapper.selectByTableId(t.getId());
            StringBuilder sb = new StringBuilder();
            sb.append("表: ").append(t.getTableName());
            if (t.getLabel() != null) sb.append(" (").append(t.getLabel()).append(")");
            sb.append("\n说明: ").append(t.getDescription() != null ? t.getDescription() : "无");
            sb.append("\n字段: ");
            for (MetaColumn c : cols) {
                sb.append(c.getColumnName());
                if (c.getLabel() != null) sb.append("(").append(c.getLabel()).append(")");
                sb.append(", ");
            }
            embedAndStore("table_description", t.getId(), sb.toString());
        }
        return tables.size();
    }
}
```

---

### B-5. LLMService 接口扩展 + 本地 BGE-M3 实现（⚠️ v4 核心修改点）

**LLMService 接口**（不变）：

```java
public interface LLMService {
    // 已有方法保持不变（由 DeepSeek 实现）
    String generateSQL(String prompt, String metadata, Integer orgId);
    String repairSQL(String prompt, String errorMessage, String originalSql);
    String summarizeResults(String question, String sql, ResultProfile profile);
    String detectIntent(String question);
    String recommendChartType(String question, int rowCount);

    // 新增：生成文本 embedding 向量
    // ⚠️ v4：由本地 BGE-M3 服务实现，不再调用 OpenAI Embeddings API
    float[] generateEmbedding(String text);
}
```

**⚠️ v4 新增：BgeEmbeddingServiceImpl（替代 v3 中 OpenAILLMService.generateEmbedding）**：

BGE-M3 本地推理服务（`Xinference` / `infinity` / `FastAPI` 封装）均支持兼容 OpenAI 格式的 `/embeddings` 接口。将 `generateEmbedding` 实现单独抽取为一个 `EmbeddingProvider` 策略，由 Spring 条件注入，与 DeepSeek 的 SQL 生成实现解耦：

```java
/**
 * 本地 BGE-M3 向量化实现
 * 通过本地 HTTP 推理服务生成 1024 维 embedding，兼容 OpenAI /embeddings 接口格式
 * LLM 推理（SQL生成、摘要等）由 DeepSeekLLMService 独立处理，两者互不干扰
 */
@Component
@Slf4j
public class BgeEmbeddingProvider {

    @Value("${llm.embedding-base-url:http://localhost:8001}")
    private String embeddingBaseUrl;

    @Value("${llm.embedding-model:bge-m3}")
    private String embeddingModel;

    // 期望维度，与数据库 vector(1024) 保持一致，启动时可做断言校验
    @Value("${llm.embedding-dimensions:1024}")
    private int embeddingDimensions;

    @Autowired private OkHttpClient httpClient;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 调用本地 BGE-M3 推理服务生成 embedding
     * 接口格式与 OpenAI /embeddings 完全兼容：
     *   POST {embeddingBaseUrl}/embeddings
     *   Body: {"model": "bge-m3", "input": "..."}
     *   Response: {"data": [{"embedding": [...1024个float...]}]}
     *
     * 本地服务通常不需要 Authorization header；
     * 若推理框架配置了 token，通过 ${llm.embedding-api-key} 注入即可。
     */
    public float[] generateEmbedding(String text) {
        String url = embeddingBaseUrl.replaceAll("/+$", "") + "/embeddings";
        String bodyJson;
        try {
            bodyJson = objectMapper.writeValueAsString(Map.of(
                "model", embeddingModel,
                "input", text
            ));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.LLM_ERROR, "BGE-M3 Embedding 请求序列化失败");
        }

        // 本地服务不需要 Authorization，如需 token 可在此注入
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(bodyJson, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new BusinessException(ErrorCode.LLM_ERROR,
                    "BGE-M3 Embedding 调用失败，HTTP " + response.code());
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");
            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }

            // ⚠️ 维度防御校验：快速失败，避免写入错误维度向量污染数据库
            // 数据库列 vector(1024) 也会拒绝，此处提前给出更清晰的错误信息
            if (result.length != embeddingDimensions) {
                throw new BusinessException(ErrorCode.LLM_ERROR,
                    "BGE-M3 返回维度异常：期望 " + embeddingDimensions
                    + " 维，实际 " + result.length + " 维，请检查本地推理服务配置");
            }
            return result;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.LLM_ERROR,
                "BGE-M3 Embedding 网络错误: " + e.getMessage());
        }
    }
}
```

**将 BgeEmbeddingProvider 注入 LLMService 实现类**：

DeepSeek 负责所有 LLM 推理，BGE-M3 单独负责 embedding，两者在 `DeepSeekLLMService`（或项目现有实现类）中组合：

```java
@Service
@Primary
@Slf4j
public class DeepSeekLLMService implements LLMService {

    // DeepSeek 相关配置（SQL生成、摘要等）
    @Value("${llm.deepseek.base-url}")
    private String deepseekBaseUrl;

    @Value("${llm.deepseek.api-key}")
    private String deepseekApiKey;

    // ⚠️ v4：embedding 委托给 BgeEmbeddingProvider，DeepSeek 不处理向量化
    @Autowired
    private BgeEmbeddingProvider bgeEmbeddingProvider;

    @Autowired private OkHttpClient httpClient;
    @Autowired private ObjectMapper objectMapper;

    @Override
    public String generateSQL(String prompt, String metadata, Integer orgId) {
        // 调用 DeepSeek（原有实现不变）
        // ...
    }

    @Override
    public String repairSQL(String prompt, String errorMessage, String originalSql) {
        // 调用 DeepSeek（原有实现不变）
        // ...
    }

    @Override
    public String summarizeResults(String question, String sql, ResultProfile profile) {
        // 调用 DeepSeek（原有实现不变）
        // ...
    }

    @Override
    public String detectIntent(String question) {
        // 调用 DeepSeek（原有实现不变）
        // ...
    }

    @Override
    public String recommendChartType(String question, int rowCount) {
        // 调用 DeepSeek（原有实现不变）
        // ...
    }

    /**
     * ⚠️ v4：embedding 由本地 BGE-M3 提供，与 DeepSeek 完全解耦
     * 未来切换其他模型只需修改 BgeEmbeddingProvider 实现
     */
    @Override
    public float[] generateEmbedding(String text) {
        return bgeEmbeddingProvider.generateEmbedding(text);
    }
}
```

**⚠️ v4 application.yml 配置**：

```yaml
llm:
  # DeepSeek —— 负责 SQL 生成、摘要、意图识别等所有 LLM 推理任务
  deepseek:
    base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${DEEPSEEK_API_KEY}
    model: ${DEEPSEEK_MODEL:deepseek-chat}

  # ⚠️ v4：本地 BGE-M3 —— 专门负责 embedding 向量化，与 DeepSeek 独立
  embedding-model: ${EMBEDDING_MODEL:bge-m3}
  embedding-dimensions: ${EMBEDDING_DIMS:1024}           # BGE-M3 输出维度，须与数据库 vector(N) 保持一致
  embedding-base-url: ${EMBEDDING_BASE_URL:http://localhost:8001}  # 本地推理服务地址（Xinference/infinity/FastAPI）
  # embedding-api-key: ${EMBEDDING_API_KEY:}             # 若本地服务启用了 token 鉴权，在此配置；默认无需

app:
  rag:
    similarity-threshold-example: 0.65
    similarity-threshold-glossary: 0.60
    similarity-threshold-table: 0.55
    top-k-example: 5
    top-k-glossary: 5
    top-k-table: 3
    fallback-to-keyword: true
```

**LLMConfig.java 新增字段**：

```java
@Configuration
@ConfigurationProperties(prefix = "llm")
@Data
public class LLMConfig {
    // 原有 deepseek 配置...

    // ⚠️ v4 新增
    private String embeddingModel = "bge-m3";
    private int embeddingDimensions = 1024;
    private String embeddingBaseUrl = "http://localhost:8001";
}
```

---

### B-6. 改造 MetadataService — 混合检索策略

```java
public MetadataContext retrieveForPrompt(String question, Integer orgId) {
    MetadataContext ctx = new MetadataContext();

    // 1. 问答示例：向量优先 → 关键词兜底
    List<MetaEmbedding> simExamples = embeddingService.searchSimilar(
        question, "sql_example", ragConfig.getTopKExample());
    List<Integer> exampleIds = simExamples.stream()
        .filter(e -> e.getSimilarity() > ragConfig.getThresholdExample())
        .map(MetaEmbedding::getSourceId)
        .collect(Collectors.toList());
    ctx.setExamples(exampleIds.isEmpty()
        ? sqlExampleMapper.searchByKeyword(question, 5)
        : sqlExampleMapper.selectBatchIds(exampleIds));

    // 2. 术语词典：同上策略
    List<MetaEmbedding> simGlossary = embeddingService.searchSimilar(
        question, "glossary", ragConfig.getTopKGlossary());
    List<Integer> glossaryIds = simGlossary.stream()
        .filter(e -> e.getSimilarity() > ragConfig.getThresholdGlossary())
        .map(MetaEmbedding::getSourceId)
        .collect(Collectors.toList());
    ctx.setGlossary(glossaryIds.isEmpty()
        ? glossaryMapper.searchByKeyword(question)
        : glossaryMapper.selectBatchIds(glossaryIds));

    // 3. 相关表：向量 + 关键词合并去重
    List<MetaEmbedding> simTables = embeddingService.searchSimilar(
        question, "table_description", ragConfig.getTopKTable());
    Set<Integer> tableIds = simTables.stream()
        .filter(e -> e.getSimilarity() > ragConfig.getThresholdTable())
        .map(MetaEmbedding::getSourceId)
        .collect(Collectors.toSet());
    metaTableMapper.searchByKeyword(question, orgId)
        .forEach(t -> tableIds.add(t.getId()));
    ctx.setTables(tableIds.isEmpty()
        ? Collections.emptyList()
        : metaTableMapper.selectBatchIds(new ArrayList<>(tableIds)));

    // 4. 字段和 JOIN（不变，基于已检索到的表）
    for (MetaTable table : ctx.getTables()) {
        ctx.getColumns().addAll(metaColumnMapper.selectByTableId(table.getId()));
    }
    ctx.setJoinEdges(joinEdgeMapper.selectByTables(
        ctx.getTables().stream().map(MetaTable::getTableName).collect(Collectors.toList())));

    return ctx;
}
```

**验收**：
- [ ] 向量检索结果相似度 > 阈值时采用
- [ ] 向量检索无结果或低于阈值时降级到关键词
- [ ] 表检索采用「向量 ∪ 关键词」合并策略
- [ ] 阈值通过配置读取，不硬编码

---

### B-7. 元数据保存时自动触发向量化

**改造点**：

```java
// MetadataService.java

public MetaSqlExample createExample(MetaSqlExample example, String username) {
    example.setCreatedBy(username);
    sqlExampleMapper.insert(example);
    String text = "问题: " + example.getQuestion() + "\nSQL: " + example.getSql();
    embeddingService.embedAndStoreAsync("sql_example", example.getId(), text);
    return example;
}

public MetaSqlExample updateExample(MetaSqlExample example, String username) {
    sqlExampleMapper.updateById(example);
    String text = "问题: " + example.getQuestion() + "\nSQL: " + example.getSql();
    embeddingService.embedAndStoreAsync("sql_example", example.getId(), text);
    return example;
}

public void deleteExample(Integer id) {
    sqlExampleMapper.deleteById(id);
    embeddingMapper.deleteBySource("sql_example", id);
}

// 术语词典 create/update/delete 同理：source_type = "glossary"

// DDLParserService.confirmAndSave() 末尾增加
for (MetaTable newTable : savedTables) {
    String text = buildTableDescription(newTable); // 与 EmbeddingService 相同的模板
    embeddingService.embedAndStoreAsync("table_description", newTable.getId(), text);
}
```

**验收**：
- [ ] 新增示例后，`meta_embedding` 表多一行 `source_type='sql_example'`
- [ ] 更新示例后，`meta_embedding` 对应行的 `updated_at` 变化
- [ ] 删除示例后，`meta_embedding` 对应行被删除
- [ ] 术语同理
- [ ] DDL 确认入库后，每张新表对应一行 `source_type='table_description'`
- [ ] 向量化失败不影响保存主流程

---

### B-8. 新增 EmbeddingController

```java
@RestController
@RequestMapping("/api/v1/embeddings")
@PreAuthorize("hasRole('ADMIN')")
public class EmbeddingController {

    @Autowired private EmbeddingService embeddingService;
    @Autowired private AuditService auditService;

    @PostMapping("/sync")
    public ApiResult<EmbeddingSyncResult> syncAll() {
        EmbeddingSyncResult result = embeddingService.syncAll();
        auditService.logOperation("EMBEDDING_SYNC", "SYSTEM", null,
            Map.of("examples", result.getExamples(),
                   "glossary", result.getGlossary(),
                   "tables", result.getTables()));
        return ApiResult.ok(result);
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Long>> stats() {
        return ApiResult.ok(embeddingService.getStats());
    }

    @PostMapping("/search")
    public ApiResult<List<MetaEmbedding>> testSearch(@RequestBody SearchRequest req) {
        return ApiResult.ok(embeddingService.searchSimilar(
            req.getQuery(), req.getSourceType(), req.getTopK()));
    }

    @Data
    public static class SearchRequest {
        @NotBlank private String query;
        @NotBlank private String sourceType;
        private Integer topK = 5;
    }
}
```

---

### B-9. 重构 AuthService

**改造清单**：

1. 删除 `DemoUsersProperties.java` 及 `application.yml` 中 `demo.users` 配置节
2. `login()` 改为从 `sys_user` 表查询用户
3. JWT Payload 增加 `userId` claim
4. 新增 `register()` 方法（仅 ADMIN/MANAGER 可调用）
5. `/api/v1/auth/me` 返回 `UserDTO`（含 orgGrants 列表）

**关键代码变更**：

```java
public LoginResponse login(String username, String password) {
    SysUser user = sysUserMapper.selectByUsername(username);
    if (user == null || !user.getIsActive()) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }
    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }

    // 更新最后登录时间
    sysUserMapper.updateLastLoginAt(user.getId(), LocalDateTime.now());

    // 生成 JWT，claims 增加 userId
    String token = jwtUtil.generateToken(Map.of(
        "userId", user.getId(),
        "username", user.getUsername(),
        "role", user.getRole().toUpperCase()
    ));

    return new LoginResponse(token, user.getRole(), user.getUsername(),
        user.getDisplayName(), jwtUtil.getExpirationSeconds());
}

public UserDTO getCurrentUser(String username) {
    SysUser user = sysUserMapper.selectByUsername(username);
    if (user == null) throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
    UserDTO dto = convertToDTO(user);
    dto.setOrgGrants(userService.getUserOrgGrants(user.getId())
        .stream().map(this::convertGrantToVO).collect(Collectors.toList()));
    return dto;
}
```

**验收**：
- [ ] admin/admin123 登录成功，JWT 包含 userId
- [ ] demo/demo123 登录成功
- [ ] 错误密码返回 401
- [ ] 禁用账号返回 401
- [ ] `/auth/me` 返回完整用户信息 + 授权组织列表
- [ ] `DemoUsersProperties.java` 已删除
- [ ] `application.yml` 中 `demo.users` 配置节已移除

---

### B-10. 改造 OrchestratorService 权限注入

**查询链路**：

```
query(QueryRequest req, String username):
  1. SysUser user = sysUserMapper.selectByUsername(username)
  2. Integer targetOrgId = req.getOrgId();
     if (targetOrgId == null) {
         SysUserOrg primary = sysUserOrgMapper.selectPrimaryOrg(user.getId());
         if (primary == null) throw 400 "用户未绑定默认组织"
         targetOrgId = primary.getOrgId();
     }
  3. List<Integer> accessibleOrgIds = userService.resolveAccessibleOrgIds(user.getId())
  4. if (!accessibleOrgIds.contains(targetOrgId))
         throw new BusinessException(FORBIDDEN, "您没有该组织的查询权限")
  5. List<Integer> subtreeIds = orgService.resolveSubtreeIds(targetOrgId)
  6. List<Integer> effectiveOrgIds = intersect(subtreeIds, accessibleOrgIds)
  7. QueryContext ctx = new QueryContext();
     ctx.setUserId(user.getId());
     ctx.setUsername(username);
     ctx.setTargetOrgId(targetOrgId);
     ctx.setEffectiveOrgIds(effectiveOrgIds);
  8. MetadataContext metadata = metadataService.retrieveForPrompt(req.question, targetOrgId)
  9. String prompt = promptBuilder.buildSqlGenerationPrompt(...)
 10. String sql = llmService.generateSQL(prompt, ...)
 11. GuardResult gr = sqlGuardService.validate(sql, effectiveOrgIds)
 12. ... Execute + Audit（不变）
```

**验收**：
- [ ] admin 账号可查询任意 orgId
- [ ] demo 授权子公司A → 查询子公司A 返回 200
- [ ] demo 授权子公司A → 查询子公司B 返回 403
- [ ] 未传 orgId 时自动使用 primary
- [ ] effectiveOrgIds 正确（scope=CHILDREN 时不包含孙子节点）

---

### B-11. 改造 OrgController

```
保留/修改：
  GET  /api/v1/orgs/tree                       → dim_org 完整树（ADMIN/MANAGER）
  GET  /api/v1/orgs/my-tree                    → 当前用户有权限的子树（所有角色）
  GET  /api/v1/orgs/{id}/subtree-ids           → 子树 ID 列表

新增：
  POST /api/v1/orgs/sync                       → 触发从业务库同步（ADMIN）
  GET  /api/v1/orgs/sync/status                → 上次同步时间和状态
  GET  /api/v1/orgs/{id}/users                 → 该组织下的系统账号（ADMIN/MANAGER）
  POST /api/v1/orgs/{id}/users                 → 为该组织添加账号
  PUT  /api/v1/orgs/{id}/users/{userId}/scope  → 修改 scope
  DELETE /api/v1/orgs/{id}/users/{userId}      → 从该组织移除账号

删除（返回 410 Gone）：
  POST   /api/v1/orgs
  PUT    /api/v1/orgs/{id}
  DELETE /api/v1/orgs/{id}
```

---

### B-12. 新增 UserController

```
POST   /api/v1/users                          → 创建用户
GET    /api/v1/users                          → 用户列表（role/isActive/orgId/page/size）
GET    /api/v1/users/{id}                     → 用户详情（含 orgGrants）
PUT    /api/v1/users/{id}                     → 更新用户信息
PUT    /api/v1/users/{id}/deactivate          → 禁用
PUT    /api/v1/users/{id}/activate            → 启用
PUT    /api/v1/users/{id}/password            → 修改密码（ADMIN 改任何人，用户改自己）
POST   /api/v1/users/{id}/org-grants          → 授予组织权限
DELETE /api/v1/users/{id}/org-grants/{orgId}  → 回收组织权限
GET    /api/v1/users/{id}/org-grants          → 查看权限列表
```

---

### B-13. SecurityConfig 更新

```java
http.authorizeHttpRequests(auth -> auth
    // 用户管理（ADMIN/MANAGER）
    .requestMatchers("/api/v1/users/**").hasAnyRole("ADMIN", "MANAGER")

    // 组织查看
    .requestMatchers("/api/v1/orgs/my-tree").authenticated()
    .requestMatchers("/api/v1/orgs/tree").hasAnyRole("ADMIN", "MANAGER")
    .requestMatchers(HttpMethod.GET, "/api/v1/orgs/*/subtree-ids").authenticated()

    // 组织同步
    .requestMatchers(HttpMethod.POST, "/api/v1/orgs/sync").hasRole("ADMIN")
    .requestMatchers(HttpMethod.GET, "/api/v1/orgs/sync/status").hasRole("ADMIN")

    // 组织-人员关系
    .requestMatchers(HttpMethod.GET, "/api/v1/orgs/*/users").hasAnyRole("ADMIN", "MANAGER")
    .requestMatchers(HttpMethod.POST, "/api/v1/orgs/*/users").hasAnyRole("ADMIN", "MANAGER")
    .requestMatchers(HttpMethod.PUT, "/api/v1/orgs/*/users/*/scope").hasAnyRole("ADMIN", "MANAGER")
    .requestMatchers(HttpMethod.DELETE, "/api/v1/orgs/*/users/*").hasAnyRole("ADMIN", "MANAGER")

    // 向量检索
    .requestMatchers("/api/v1/embeddings/**").hasRole("ADMIN")

    // 废弃的组织 CUD 接口
    .requestMatchers(HttpMethod.POST, "/api/v1/orgs").denyAll()
    .requestMatchers(HttpMethod.PUT, "/api/v1/orgs/*").denyAll()
    .requestMatchers(HttpMethod.DELETE, "/api/v1/orgs/*").denyAll()

    // 其他已有规则...
);
```

---

## 三、阶段 C — 前端改造

### C-1. 重构 OrgTreePage → 组织人员管理

**布局**：保持左右分栏结构。

```
┌─────────────────────────────────┬──────────────────────────────────────────┐
│  组织结构（只读）                 │  [子公司A] 的系统账号                      │
│  [🔄 同步组织] 上次: 04-15 10:30 │  [+ 添加账号到此组织]                      │
│                                 │  ┌──────┬──────┬──────┬──────┬────┐     │
│  ▼ 母公司                       │  │ 用户名│ 姓名  │ 角色  │ 范围  │ 操作 │     │
│    ▼ 子公司A ← 选中             │  ├──────┼──────┼──────┼──────┼────┤     │
│      销售部                     │  │ demo │ 演示  │ USER │ ALL  │  ✕ │     │
│      生产部                     │  │ zhang│ 张三  │ USER │ SELF │  ✕ │     │
│      市场部                     │  └──────┴──────┴──────┴──────┴────┘     │
│      综合部                     │  共 2 个账号                              │
│    ▶ 子公司B                    │                                          │
│    ▶ 子公司C                    │                                          │
│                                 │                                          │
│  共 16 个节点                    │                                          │
└─────────────────────────────────┴──────────────────────────────────────────┘
```

**左侧**：
- 调用 `GET /api/v1/orgs/tree`
- 只读 Ant `<Tree>`，无增删改按钮
- 顶部「🔄 同步组织」按钮（仅 ADMIN 可见），点击调用 `POST /api/v1/orgs/sync`
- 同步成功后刷新树 + Toast 提示「新增 X 条，更新 Y 条」
- 显示上次同步时间

**右侧** — `OrgUserPanel` 组件：
- 调用 `GET /api/v1/orgs/{orgId}/users`
- 表格列：用户名、显示名、角色（Tag）、scope（可编辑 Select）、isPrimary（图标）、操作（移除）
- 「+ 添加账号到此组织」按钮 → Modal：
  - Select 搜索已有用户（调 `/api/v1/users?role=USER`）
  - Radio 选择 scope：SELF / CHILDREN / ALL
  - Checkbox 是否设为默认组织
  - 提交 → `POST /api/v1/orgs/{orgId}/users`
- scope 列行内切换 → `PUT /api/v1/orgs/{orgId}/users/{userId}/scope`
- 「✕」移除按钮 → 确认弹窗 → `DELETE /api/v1/orgs/{orgId}/users/{userId}`

**验收**：
- [ ] 左侧组织树展示元数据库 dim_org
- [ ] 选中组织节点右侧加载对应账号
- [ ] 同步组织按钮仅 ADMIN 可见
- [ ] 同步成功刷新树和 Toast 提示
- [ ] 添加账号成功后列表刷新
- [ ] scope 修改实时生效
- [ ] 移除账号确认后刷新
- [ ] MANAGER 只能操作 accessibleOrgIds 范围内的组织

---

### C-2. 新增 UserManagePage（账号管理页面）

路由 `/admin/users`。

**功能**：
1. 用户列表 Ant Table
   - 列：用户名、显示名、角色（Tag 不同颜色）、状态、最后登录时间、操作
   - 顶部筛选：角色（All/ADMIN/MANAGER/USER）、状态（启用/禁用）、搜索框
   - 分页
2. 「+ 新建用户」按钮 → Drawer：
   - 用户名（唯一校验）、密码（强度校验）、确认密码
   - 显示名、角色（ADMIN 可选全部，MANAGER 只能选 USER）
   - 邮箱、手机
3. 编辑按钮 → Drawer（同表单，不含密码）
4. 重置密码 → Modal：新密码 + 确认
5. 禁用/启用切换
6. 点击用户名 → 跳转用户详情页（展示 orgGrants 列表，只读）

**验收**：
- [ ] ADMIN 看到全部用户
- [ ] MANAGER 看到本组织下的 USER
- [ ] USER 侧边栏不显示此菜单
- [ ] 新建表单校验
- [ ] 禁用用户后该用户无法登录（前端测试账号）
- [ ] orgGrants 列表展示正确的组织路径

---

### C-3. 新增 EmbeddingManagePage（向量检索管理）

路由 `/admin/embeddings`。

**页面结构**：

```
┌──────────────────────────────────────────────────────────────────┐
│  向量检索管理                                                        │
│                                                                  │
│  ┌───────────┬───────────┬───────────┬───────────┐              │
│  │问答示例     │术语词典     │表描述       │总计          │              │
│  │    20 条  │    15 条  │     4 条  │    39 条  │              │
│  └───────────┴───────────┴───────────┴───────────┘              │
│                                                                  │
│  [🔄 全量同步] （上次同步: 2026-04-15 10:30:00）                     │
│                                                                  │
│  ─────────────── 检索测试 ───────────────                           │
│                                                                  │
│  类型：[问答示例 ▼]  Top K：[5]                                     │
│  测试问题：[_______________________________]  [测试检索]              │
│                                                                  │
│  结果：                                                           │
│  ┌─────┬────────────────────────────────────┬──────────┐         │
│  │  #  │ 内容                                │ 相似度    │         │
│  ├─────┼────────────────────────────────────┼──────────┤         │
│  │  1  │ 问题: 子公司A毛利率...                │  0.87   │         │
│  │  2  │ 问题: 2024年Q3销售额...              │  0.76   │         │
│  └─────┴────────────────────────────────────┴──────────┘         │
└──────────────────────────────────────────────────────────────────┘
```

**功能**：
1. 统计卡片：调用 `GET /api/v1/embeddings/stats`
2. 「全量同步」按钮：调用 `POST /api/v1/embeddings/sync`，loading 状态，成功后刷新统计
3. 检索测试：调用 `POST /api/v1/embeddings/search`，展示结果

---

### C-4. AdminLayout 菜单更新

```
👤 账号管理           /admin/users         (ADMIN/MANAGER)
🏢 组织人员管理        /admin/org-tree      (ADMIN/MANAGER)
📤 DDL 上传解析       /admin/ddl-upload    (ADMIN/MANAGER)
💾 数据源管理          /admin/datasources   (ADMIN)
📋 表与字段管理        /admin/tables        (ADMIN/MANAGER)
📖 术语词典管理        /admin/glossary      (ADMIN/MANAGER)
🔗 JOIN 关系管理       /admin/joins         (ADMIN/MANAGER)
💬 问答示例管理        /admin/examples      (ADMIN/MANAGER)
🧠 向量检索管理        /admin/embeddings    (ADMIN)        ← 新增
📝 审计日志           /admin/audit         (ADMIN/MANAGER)
```

原「组织机构管理」改名为「组织人员管理」。

---

### C-5. 改造 OrgSelector（查询域）

```typescript
// useOrgStore.ts
const fetchOrgTree = async () => {
    const { data } = await orgApi.getMyTree();  // 改为 /my-tree
    setOrgTree(data);
    // 默认选中 is_primary=true 的节点
    const primary = findPrimary(data);
    if (primary) setSelectedOrgId(primary.id);
};
```

**验收**：
- [ ] USER 登录后 OrgSelector 只显示授权组织
- [ ] ADMIN 登录看到全部组织
- [ ] 默认选中 primary 组织
- [ ] 切换组织后查询使用新 orgId

---

### C-6. 改造 ExamplesPage / GlossaryPage

保存/编辑成功后，Toast 提示「已自动向量化」。前端无需额外调用 embedding API——后端在 create/update 时已自动异步触发。

同理改造 DDLUploadPage：确认入库后 Toast 「已保存并启动向量化」。

---

### C-7. 新增前端 TypeScript 类型 + Service

```typescript
// types/user.ts
export interface SysUser {
    id: number;
    username: string;
    displayName: string;
    role: 'ADMIN' | 'MANAGER' | 'USER';
    email?: string;
    phone?: string;
    isActive: boolean;
    lastLoginAt?: string;
    orgGrants?: UserOrgGrant[];
}

export interface UserOrgGrant {
    id: number;
    orgId: number;
    orgName: string;        // 后端 JOIN dim_org 填充
    orgPath: string;        // 如 "母公司 > 子公司A"
    scope: 'SELF' | 'CHILDREN' | 'ALL';
    isPrimary: boolean;
    grantedBy: string;
    grantedAt: string;
}

export interface OrgUserVO {
    userId: number;
    username: string;
    displayName: string;
    role: string;
    scope: 'SELF' | 'CHILDREN' | 'ALL';
    isPrimary: boolean;
    grantedAt: string;
}

export interface UserCreateRequest {
    username: string;
    password: string;
    displayName: string;
    role: 'MANAGER' | 'USER';  // 前端不允许创建 ADMIN
    email?: string;
    phone?: string;
}

export interface UserOrgGrantRequest {
    userId: number;
    orgId: number;
    scope: 'SELF' | 'CHILDREN' | 'ALL';
    isPrimary?: boolean;
}

export interface OrgSyncResult {
    inserted: number;
    updated: number;
    deactivated: number;
    total: number;
}

export interface EmbeddingSyncResult {
    examples: number;
    glossary: number;
    tables: number;
}

export interface EmbeddingStats {
    sql_example: number;
    glossary: number;
    table_description: number;
    total: number;
}
```

```typescript
// services/userApi.ts
export const userApi = {
    list: (params) => apiGet<PageResult<SysUser>>('/users', { params }),
    getById: (id: number) => apiGet<SysUser>(`/users/${id}`),
    create: (data: UserCreateRequest) => apiPost<SysUser>('/users', data),
    update: (id, data) => apiPut<SysUser>(`/users/${id}`, data),
    deactivate: (id) => apiPut(`/users/${id}/deactivate`),
    activate: (id) => apiPut(`/users/${id}/activate`),
    changePassword: (id, data) => apiPut(`/users/${id}/password`, data),
    getOrgGrants: (id) => apiGet<UserOrgGrant[]>(`/users/${id}/org-grants`),
};

// services/orgApi.ts — 改造
export const orgApi = {
    getTree: () => apiGet<OrgTreeNode[]>('/orgs/tree'),
    getMyTree: () => apiGet<OrgTreeNode[]>('/orgs/my-tree'),
    getSubtreeIds: (orgId) => apiGet<number[]>(`/orgs/${orgId}/subtree-ids`),
    sync: () => apiPost<OrgSyncResult>('/orgs/sync'),
    getSyncStatus: () => apiGet<{ lastSyncAt: string }>('/orgs/sync/status'),
    getUsersByOrg: (orgId) => apiGet<OrgUserVO[]>(`/orgs/${orgId}/users`),
    addUserToOrg: (orgId, data: UserOrgGrantRequest) =>
        apiPost(`/orgs/${orgId}/users`, data),
    updateUserScope: (orgId, userId, scope) =>
        apiPut(`/orgs/${orgId}/users/${userId}/scope`, { scope }),
    removeUserFromOrg: (orgId, userId) =>
        apiDelete(`/orgs/${orgId}/users/${userId}`),
};

// services/embeddingApi.ts
export const embeddingApi = {
    sync: () => apiPost<EmbeddingSyncResult>('/embeddings/sync'),
    stats: () => apiGet<EmbeddingStats>('/embeddings/stats'),
    testSearch: (query, sourceType, topK) =>
        apiPost<MetaEmbedding[]>('/embeddings/search', { query, sourceType, topK }),
};
```

---

### C-8. MSW Mock Handlers

| 文件 | 说明 |
|------|------|
| `userHandlers.ts` | 新增 — 用户 CRUD + 权限管理 Mock |
| `embeddingHandlers.ts` | 新增 — sync / stats / testSearch Mock |
| `orgHandlers.ts` | 改造 — 移除 POST/PUT/DELETE；新增 /my-tree, /sync, /{id}/users |
| `authHandlers.ts` | 改造 — /auth/me 返回 orgGrants |
| `userData.ts` | 新增 — Mock 用户数据（ADMIN/MANAGER/USER 各一个 + 权限映射） |

---

## 四、阶段 D — RAG 系统数据流全景

```
                        管理员操作域
                            │
┌───────────────────────────┼──────────────────────────────┐
│                           │                              │
新增/修改问答示例         新增/修改术语词典            DDL 确认入库
│                           │                              │
▼                           ▼                              ▼
MetadataService         MetadataService                DDLParserService
.createExample()        .createGlossary()              .confirmAndSave()
│                           │                              │
└───────────异步触发─────────┴──────────────────────────────┘
                            │
                            ▼
                    EmbeddingService
                  .embedAndStoreAsync()
                            │
                ┌───────────┴───────────┐
                ▼                       ▼
         LLMService               MetaEmbeddingMapper
         .generateEmbedding()     .upsertEmbedding()
         ⚠️ v4: 调用本地           （写入 pgvector，1024 维）
         BGE-M3 推理服务
         POST http://localhost:8001/embeddings
                            │
                            ▼
                    meta_embedding 表
              (source_type + source_id + vector(1024))


                        查询域
                          │
                用户提交自然语言问题
                          │
                          ▼
              OrchestratorService.query()
                          │
            ┌─────────────┼──────────────┐
            ▼                            ▼
        权限校验                  MetadataService
     (sys_user_org)             .retrieveForPrompt()
                                         │
                              ┌──────────┴──────────┐
                              ▼                     ▼
                      EmbeddingService         关键词 ILIKE
                      .searchSimilar()         (兜底策略)
                              │
                  ┌───────────┴───────────┐
                  ▼                       ▼
         generateEmbedding         pgvector 近邻检索
         (question → 1024维向量)    ORDER BY embedding <=> query_vec
         ⚠️ v4: 本地 BGE-M3        LIMIT topK
                                       │
                              ┌──────────┴──────────┐
                              ▼                     ▼
                     similarity > 阈值         similarity ≤ 阈值
                     → 采用向量结果           → 降级到关键词结果
                                         │
                                         ▼
                              合并去重 → MetadataContext
                      (tables + columns + glossary + examples + joins)
                                         │
                                         ▼
                    PromptBuilder.buildSqlGenerationPrompt()
                                         │
                                         ▼
                          LLMService.generateSQL()
                          ⚠️ v4: 由 DeepSeek 处理（与 embedding 解耦）
```

### 向量化内容模板

| source_type | content_text 模板 | 示例 |
|---|---|---|
| `sql_example` | `问题: {question}\nSQL: {sql}` | `问题: 子公司A 2024年Q3毛利率\nSQL: SELECT ...` |
| `glossary` | `术语: {term}\n定义: {definition}\nSQL: {sql_snippet}` | `术语: 毛利率\n定义: (收入-成本)/收入\nSQL: (revenue-cost)/revenue` |
| `table_description` | `表: {table_name} ({label})\n说明: {description}\n字段: col1(中文名), col2(中文名), ...` | `表: fact_gross_profit_detail (毛利明细)\n说明: ...\n字段: period_month(账期月份), revenue(销售收入), ...` |

### 相似度阈值配置

```yaml
app:
  rag:
    similarity-threshold-example: 0.65
    similarity-threshold-glossary: 0.60
    similarity-threshold-table: 0.55
    top-k-example: 5
    top-k-glossary: 5
    top-k-table: 3
    fallback-to-keyword: true
```

---

## 五、阶段 E — 测试清单

### E-1. 单元测试（新增 ~67 个）

| 测试类 | 测试用例 | 数量 |
|--------|---------|------|
| `DimOrgMapperTest` | selectAll / selectSubtreeIds / selectAncestorPath / insertWithId / updateById / deactivate | 6 |
| `SysUserMapperTest` | selectByUsername / selectById / insert / updateById / updateLastLoginAt | 5 |
| `SysUserOrgMapperTest` | selectByUserId / selectByOrgId / selectPrimaryOrg / insert / delete / 唯一约束 | 6 |
| `UserServiceTest` | ADMIN创建MANAGER成功 / MANAGER创建USER成功 / MANAGER创建ADMIN拒绝 / MANAGER创建MANAGER拒绝 / deactivateUser / resolveAccessibleOrgIds(SELF) / resolveAccessibleOrgIds(CHILDREN) / resolveAccessibleOrgIds(ALL) / resolveAccessibleOrgIds(ADMIN全量) / hasAccessToOrg(有权限) / hasAccessToOrg(无权限) / grantOrgAccess / revokeOrgAccess | 13 |
| `AuthServiceTest` | login成功 / 密码错误 / 账号禁用 / JWT包含userId / register成功 / register权限不足 | 6 |
| `OrgServiceTest` | getFullOrgTree / resolveSubtreeIds / resolveOrgPath / syncFromBusiness(新增场景) / syncFromBusiness(更新场景) / syncFromBusiness(停用场景) / syncFromBusiness(幂等) / getOrgTreeForUser / getUsersByOrgId | 9 |
| `OrchestratorServiceTest` | query有权限 / 无权限403 / orgId为空使用primary / effectiveOrgIds交集计算正确 | 4 |
| `EmbeddingServiceTest` | embedAndStore成功 / embedAndStoreAsync / searchSimilar返回结果排序 / syncAllExamples计数 / syncAllGlossary / syncAllTables / embedding失败降级 | 7 |
| `MetaEmbeddingMapperTest` | upsertEmbedding / searchSimilar / deleteBySource / countByType | 4 |
| `MetadataServiceTest(改造)` | retrieveForPrompt向量优先 / retrieveForPrompt降级关键词 / 阈值过滤 / 表检索合并策略 | 4 |
| `BgeEmbeddingProviderTest`（⚠️ v4 新增，替代 v3 OpenAILLMServiceTest embedding 用例） | generateEmbedding成功（1024维） / generateEmbedding维度不匹配异常 / generateEmbedding本地服务不可达 / generateEmbedding超时 / generateEmbedding解析错误 | 5 |

**合计：约 69 个新增单元测试**

---

### E-2. 集成测试场景（15 个）

| # | 场景 | 步骤 | 期望结果 |
|---|------|------|---------|
| 1 | admin 登录并查看组织 | login → GET /orgs/tree | 返回 dim_org 完整树（16 节点） |
| 2 | admin 触发组织同步 | POST /orgs/sync | 200 + OrgSyncResult |
| 3 | admin 创建 manager | POST /users {role:MANAGER} | 201 + userId |
| 4 | admin 授权 manager 到子公司A | POST /orgs/2/users {userId, scope:ALL} | 200 |
| 5 | manager 登录查看组织 | login(manager) → GET /orgs/my-tree | 仅子公司A及其子节点 |
| 6 | manager 查看子公司A 人员 | GET /orgs/2/users | 返回 manager 自己 |
| 7 | manager 创建 USER | POST /users {role:USER} | 201 |
| 8 | manager 尝试创建 ADMIN | POST /users {role:ADMIN} | 403 |
| 9 | admin 授权 user 到子公司A | POST /orgs/2/users | 200 |
| 10 | user 查询有权限的数据 | POST /query {orgId:2} | 200 + 结果 |
| 11 | user 查询无权限的数据 | POST /query {orgId:3} | 403 |
| 12 | admin 全量向量化 | POST /embeddings/sync | 200 + 计数 |
| 13 | 向量检索测试（BGE-M3） | POST /embeddings/search | top 5 结果，返回 1024 维相似度计算正确 |
| 14 | 新增示例自动向量化 | POST /metadata/examples → GET /embeddings/stats | sql_example 计数 +1 |
| 15 | 权限回收即时生效 | DELETE /orgs/2/users/{userId} → user 查询 | 403 |

---

### E-3. 回归测试清单

| 模块 | 验证内容 | 方法 |
|------|---------|------|
| 登录/登出 | admin/demo 均可登录，JWT 含 userId | 手动 + 自动化 |
| 端到端查询 | 含向量检索的完整查询链路（BGE-M3 → pgvector → DeepSeek → 结果） | 手动 + 自动化 |
| DDL 上传 | 上传 → 解析 → 确认 → 自动向量化 | 手动 |
| 元数据 CRUD | 表/字段/术语/JOIN/示例正常 + 术语和示例保存自动向量化 | 手动 |
| 数据源管理 | CRUD 正常 | 手动 |
| SQL Guard | AST 校验 + LIMIT 注入 + org_id 权限校验 | 自动化 |
| Redis 缓存 | 组织同步后缓存清除、查询缓存正常 | 手动 |
| 前端编译 | `npx tsc --noEmit && npm run build` | 自动化 |
| 后端编译 | `mvn compile && mvn test` | 自动化 |

---

## 六、阶段 F — 部署 + 数据迁移

### F-1. 部署前检查清单

- [ ] `docs/migration/V10_1__permission_and_rag.sql` 在测试库执行通过
- [ ] BCrypt 哈希由 `BCryptPasswordEncoder` 生成后替换种子数据占位符
- [ ] pgvector 扩展已在生产库安装
- [ ] **本地 BGE-M3 推理服务已启动并可访问**（`curl http://localhost:8001/embeddings`）
- [ ] **`${EMBEDDING_BASE_URL}` 环境变量已配置为实际推理服务地址**
- [ ] `DemoUsersProperties.java` 已删除
- [ ] `application.yml` 中 `demo.users` 配置节已移除
- [ ] 所有测试通过：`mvn test` + `npm run build`
- [ ] 前端路由守卫支持三级角色

### F-2. 部署步骤

```
1. 在 text2report-meta 执行 DDL（按顺序）：
   a. CREATE EXTENSION vector;
   b. 删除 org_tree 的 FK 约束
   c. 删除 org_tree 表
   d. 创建 dim_org 表 + 索引 + 种子数据
   e. 重建 meta_datasource / audit_query_log 的 FK 指向 dim_org
   f. 创建 sys_user / sys_user_org / audit_operation_log / meta_embedding（vector(1024)）
   g. 插入 admin / demo 种子账号 + sys_user_org 绑定

2. 确认本地 BGE-M3 推理服务正常运行（Xinference / infinity / FastAPI 均可）

3. 部署后端新版本（含 BgeEmbeddingProvider 配置）

4. admin 登录管理后台：
   a. POST /api/v1/orgs/sync        → 从业务库同步 dim_org
   b. POST /api/v1/embeddings/sync  → 全量向量化（调用本地 BGE-M3 生成 1024 维向量）

5. 创建业务账号：
   a. admin 创建 manager 账号，绑定对应子公司（scope=ALL）
   b. manager 创建 user 账号，授权对应部门

6. 验收：
   a. 各角色登录验证
   b. 组织人员管理页面验证
   c. 向量检索管理页面验证（确认相似度计算正确）
   d. 端到端查询（含 RAG 向量检索）验证
```

---

## 七、文件变更清单

### 新增文件

| 文件 | 层级 |
|------|------|
| `entity/DimOrg.java` | 后端（替代 OrgTree.java） |
| `entity/SysUser.java` | 后端 |
| `entity/SysUserOrg.java` | 后端 |
| `entity/MetaEmbedding.java` | 后端 |
| `dto/UserDTO.java` | 后端 |
| `dto/UserCreateRequest.java` | 后端 |
| `dto/UserUpdateRequest.java` | 后端 |
| `dto/UserOrgGrantRequest.java` | 后端 |
| `dto/OrgUserVO.java` | 后端 |
| `dto/OrgSyncResult.java` | 后端 |
| `dto/EmbeddingSyncResult.java` | 后端 |
| `mapper/DimOrgMapper.java + .xml` | 后端（替代 OrgTreeMapper） |
| `mapper/SysUserMapper.java + .xml` | 后端 |
| `mapper/SysUserOrgMapper.java + .xml` | 后端 |
| `mapper/MetaEmbeddingMapper.java + .xml` | 后端 |
| `service/UserService.java + UserServiceImpl.java` | 后端 |
| `service/EmbeddingService.java` | 后端 |
| `controller/UserController.java` | 后端 |
| `controller/EmbeddingController.java` | 后端 |
| `config/RagConfig.java` | 后端（读取 app.rag.* 配置） |
| `config/BusinessJdbcTemplateConfig.java` | 后端（businessJdbcTemplate Bean） |
| **`embedding/BgeEmbeddingProvider.java`** | **后端（⚠️ v4 新增，本地 BGE-M3 HTTP 调用实现）** |
| `docs/migration/V10_1__permission_and_rag.sql` | 迁移脚本 |
| `pages/admin/UserManagePage.tsx` | 前端 |
| `pages/admin/EmbeddingManagePage.tsx` | 前端 |
| `components/admin/UserForm.tsx` | 前端 |
| `components/admin/OrgUserPanel.tsx` | 前端 |
| `services/userApi.ts` | 前端 |
| `services/embeddingApi.ts` | 前端 |
| `types/user.ts` | 前端 |
| `mocks/handlers/userHandlers.ts` | 前端 |
| `mocks/handlers/embeddingHandlers.ts` | 前端 |
| `mocks/data/userData.ts` | 前端 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `OrgService.java` | 查 dim_org 替代 org_tree，字段映射变更，删除 CUD，新增 syncFromBusiness / getUsersByOrgId / getOrgTreeForUser |
| `OrgController.java` | 移除 CUD，新增 /sync / /my-tree / /{id}/users |
| `AuthService.java` | 改查 sys_user，删除 DemoUsersProperties，JWT 增 userId，新增 register |
| `JwtUtil.java` | Token Payload 增加 userId claim |
| `JwtAuthFilter.java` | SecurityContext 设置 userId |
| `SecurityConfig.java` | 新增路由权限规则，废弃组织 CUD 接口 denyAll |
| `OrchestratorService.java` | 权限注入 + effectiveOrgIds 交集 |
| `MetadataService.java` | retrieveForPrompt 改为向量 + 关键词混合；create/update/delete 触发向量化 |
| `DDLParserService.java` | confirmAndSave 末尾触发 table_description 向量化 |
| `LLMService.java` | 接口新增 generateEmbedding 方法 |
| **`DeepSeekLLMService.java`（或现有实现类）** | **⚠️ v4：实现 generateEmbedding，委托给 BgeEmbeddingProvider；v3 中 OpenAILLMService 的同名方法废弃** |
| `LLMConfig.java` | 新增 embeddingModel / embeddingDimensions / **embeddingBaseUrl**（⚠️ v4） |
| `application.yml` | ⚠️ v4：embedding-model 改为 bge-m3，embedding-dimensions 改为 1024，新增 embedding-base-url；llm.deepseek.* 配置保持 |
| `schema-meta.sql` | 删除 org_tree，新增 dim_org / sys_user / sys_user_org / audit_operation_log / **meta_embedding（vector(1024)）**（⚠️ v4） |
| `OrgTreePage.tsx` | 只读树 + 右侧 OrgUserPanel + 同步按钮 |
| `OrgTreeEditor.tsx` | 移除编辑功能（改为只读展示） |
| `AdminLayout.tsx` | 菜单变更（新增账号管理、向量检索管理；组织管理改名） |
| `orgApi.ts` | 移除 CUD，新增 sync / myTree / getUsersByOrg / addUserToOrg / removeUserFromOrg |
| `authHandlers.ts` | /auth/me 返回含 orgGrants 的 UserDTO |
| `orgHandlers.ts` | 移除 POST/PUT/DELETE Mock |
| `useOrgStore.ts` | 使用 /orgs/my-tree 替代 /orgs/tree |
| `useAuthStore.ts` | 存储 userId 和 orgGrants |
| `ExamplesPage.tsx` | 保存后 Toast 提示已向量化 |
| `GlossaryPage.tsx` | 保存后 Toast 提示已向量化 |
| `DDLUploadPage.tsx` | 确认入库后 Toast 提示已启动向量化 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `entity/OrgTree.java` | 被 DimOrg.java 替代 |
| `mapper/OrgTreeMapper.java + .xml` | 被 DimOrgMapper 替代 |
| `config/DemoUsersProperties.java` | 被 sys_user 表替代 |
| `components/admin/OrgNodeDetail.tsx` | 被 OrgUserPanel 替代 |
| `application.yml` 中 `demo.users` 配置节 | 不再需要 |

---

## 八、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| pgvector 扩展需要 superuser 权限安装 | 部署失败 | 提前在服务器执行 `CREATE EXTENSION vector`；阿里云 RDS PG 13+ 原生支持 |
| 组织同步需跨库读取业务库 | 跨库依赖 | 仅 syncFromBusiness() 使用 businessJdbcTemplate，只读，不在查询热路径 |
| dim_org 从 org_tree 改名涉及大量 SQL 和 Entity 变更 | 遗漏引发 bug | 全局搜索替换 + 编译验证 + 完整单元测试覆盖 |
| Embedding 调用延迟影响保存体验 | 用户感知卡顿 | 向量化异步执行（CompletableFuture + llmExecutor），不阻塞主流程 |
| **⚠️ v4：pgvector 维度固定 1024（BGE-M3），切换其他模型后需重建** | 切换成本 | `meta_embedding.model_version` 字段记录模型；切换时执行全量 `POST /embeddings/sync` 重建；新维度须同步修改 DDL 和 `llm.embedding-dimensions` 配置 |
| **⚠️ v4：本地 BGE-M3 推理服务不可用** | 向量化失败，降级到关键词检索 | `embedAndStore` 捕获异常不抛出，主流程不受影响；`searchSimilar` 失败时返回空列表触发关键词兜底；部署前确认推理服务健康状态 |
| **⚠️ v4：本地 BGE-M3 与数据库 vector(1024) 维度不一致** | 写入失败或检索结果错误 | `BgeEmbeddingProvider` 内置维度防御校验（`result.length != embeddingDimensions`）；数据库约束为第二道防线 |
| JWT 缓存：权限回收后旧 token 仍有效 | 权限窗口期风险 | 方案A：缩短 JWT TTL 至 4h。方案B：权限变更时将旧 token 加入 Redis 黑名单 |
| dim_org.id 是 INTEGER 非 SERIAL | 插入语法差异 | DimOrg Entity 使用 `@TableId(type=IdType.INPUT)`，insertWithId 明确指定 ID |
| 向量检索对问题表述敏感 | 召回率不稳定 | 保留关键词兜底策略；阈值可配置；管理后台提供测试工具辅助调优 |
| 首次同步/向量化耗时较长 | 上线等待 | 同步和向量化均为一次性操作，有进度展示；后续保存是增量 |
