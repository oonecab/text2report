package com.text2report.domain.meta.mapper;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.text2report.domain.meta.entity.SysUser;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringJUnitConfig(classes = SysUserMapperIntegrationTest.TestConfig.class)
class SysUserMapperIntegrationTest {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpSchema() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE sys_user (
                    id INTEGER PRIMARY KEY,
                    username VARCHAR(50) NOT NULL,
                    password_hash VARCHAR(200) NOT NULL,
                    display_name VARCHAR(100),
                    role VARCHAR(20) NOT NULL,
                    email VARCHAR(200),
                    phone VARCHAR(20),
                    is_active BOOLEAN,
                    last_login_at TIMESTAMP,
                    created_at TIMESTAMP,
                    created_by VARCHAR(50),
                    updated_at TIMESTAMP,
                    updated_by VARCHAR(50)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE sys_user_org (
                    id INTEGER PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    org_id INTEGER NOT NULL,
                    scope VARCHAR(20) NOT NULL,
                    is_primary BOOLEAN,
                    granted_by VARCHAR(50),
                    granted_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE dim_org (
                    id INTEGER PRIMARY KEY,
                    org_name VARCHAR(100),
                    parent_id INTEGER,
                    org_level SMALLINT,
                    org_code VARCHAR(50),
                    is_active BOOLEAN
                )
                """);

        jdbcTemplate.update("INSERT INTO dim_org (id, org_name, parent_id, org_level, org_code, is_active) VALUES (2, '子公司A', NULL, 2, 'A', TRUE)");
        jdbcTemplate.update("INSERT INTO dim_org (id, org_name, parent_id, org_level, org_code, is_active) VALUES (5, '部门一', 2, 3, 'A-1', TRUE)");
        jdbcTemplate.update("INSERT INTO dim_org (id, org_name, parent_id, org_level, org_code, is_active) VALUES (6, '部门二', 2, 3, 'A-2', TRUE)");
        jdbcTemplate.update("INSERT INTO dim_org (id, org_name, parent_id, org_level, org_code, is_active) VALUES (9, '子公司B', NULL, 2, 'B', TRUE)");

        insertUser(10, "manager", "组织管理员", "MANAGER", true);
        insertUser(20, "user_scope_ok", "范围内用户", "USER", true);
        insertUser(30, "user_extra_scope", "越界用户", "USER", true);
        insertUser(40, "user_children_scope", "子节点越界用户", "USER", true);
        insertUser(50, "user_inactive", "禁用用户", "USER", false);

        insertGrant(1, 20, 5, "SELF");
        insertGrant(2, 30, 5, "SELF");
        insertGrant(3, 30, 9, "SELF");
        insertGrant(4, 40, 2, "CHILDREN");
    }

    @Test
    @DisplayName("list/count 应支持关键字搜索和数据库分页")
    void listAndCountSupportKeywordAndPagination() {
        List<SysUser> users = sysUserMapper.list("USER", true, null, "user_", null, null, 1, 1);
        long total = sysUserMapper.count("USER", true, null, "user_", null, null);

        assertEquals(1, users.size());
        assertEquals("user_extra_scope", users.get(0).getUsername());
        assertEquals(3L, total);
    }

    @Test
    @DisplayName("MANAGER 过滤应排除权限范围外或无授权的 USER")
    void managerVisibilityFiltersOutUsersOutsideAllowedOrgScope() {
        List<SysUser> users = sysUserMapper.list(null, true, null, null, List.of(2, 5), 10, 20, 0);
        long total = sysUserMapper.count(null, true, null, null, List.of(2, 5), 10);

        assertEquals(List.of("user_scope_ok"), users.stream().map(SysUser::getUsername).toList());
        assertEquals(1L, total);
    }

    @Test
    @DisplayName("空 allowedOrgIds 应在 Mapper 层兜底为无结果")
    void emptyAllowedOrgIdsReturnsNoRows() {
        List<SysUser> users = sysUserMapper.list(null, true, null, null, List.of(), 10, 20, 0);
        long total = sysUserMapper.count(null, true, null, null, List.of(), 10);

        assertEquals(List.of(), users);
        assertEquals(0L, total);
    }

    @Test
    @DisplayName("列表查询不应加载 password_hash")
    void listDoesNotLoadPasswordHash() {
        List<SysUser> users = sysUserMapper.list("USER", true, null, "scope_ok", null, null, 10, 0);

        assertEquals(1, users.size());
        assertNull(users.get(0).getPasswordHash());
    }

    private void insertUser(int id, String username, String displayName, String role, boolean isActive) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (
                    id, username, password_hash, display_name, role, email, phone, is_active,
                    last_login_at, created_at, created_by, updated_at, updated_by
                ) VALUES (?, ?, 'hashed', ?, ?, NULL, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', CURRENT_TIMESTAMP, 'test')
                """, id, username, displayName, role, isActive);
    }

    private void insertGrant(int id, int userId, int orgId, String scope) {
        jdbcTemplate.update("""
                INSERT INTO sys_user_org (id, user_id, org_id, scope, is_primary, granted_by, granted_at)
                VALUES (?, ?, ?, ?, FALSE, 'tester', CURRENT_TIMESTAMP)
                """, id, userId, orgId, scope);
    }

    @Configuration
    @MapperScan(basePackages = "com.text2report.domain.meta.mapper", sqlSessionFactoryRef = "metaSqlSessionFactory")
    static class TestConfig {

        @Bean
        DataSource metaDataSource() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:sysuser-mapper;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        SqlSessionFactory metaSqlSessionFactory(DataSource metaDataSource) throws Exception {
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(metaDataSource);
            factoryBean.setMapperLocations(
                    new PathMatchingResourcePatternResolver().getResources("classpath:mapper/meta/*.xml")
            );
            factoryBean.setTypeAliasesPackage("com.text2report.domain.meta.entity");
            return factoryBean.getObject();
        }

        @Bean
        DataSourceTransactionManager metaTransactionManager(DataSource metaDataSource) {
            return new DataSourceTransactionManager(metaDataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource metaDataSource) {
            return new JdbcTemplate(metaDataSource);
        }
    }
}
