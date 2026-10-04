package com.text2report.controller;

import com.text2report.common.context.TraceFilter;
import com.text2report.config.RateLimitFilter;
import com.text2report.config.RequestLoggingFilter;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.domain.meta.entity.MetaJoinEdge;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaDatasourceMapper;
import com.text2report.security.JwtAuthFilter;
import com.text2report.service.DDLParserService;
import com.text2report.service.MetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = MetadataController.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestLoggingFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TraceFilter.class)
        }
)
@Import(MetadataControllerTest.TestSecurityConfig.class)
class MetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MetadataService metadataService;
    @MockBean
    private DDLParserService ddlParserService;
    @MockBean
    private MetaDatasourceMapper metaDatasourceMapper;

    @Test
    @DisplayName("ADMIN 可以解析 DDL")
    void adminCanParseDdl() throws Exception {
        DDLParserService.DDLParseResult result = new DDLParserService.DDLParseResult();
        result.setUploadId("upload-1");
        result.setDatasourceId(1);
        result.setTables(List.of());
        when(ddlParserService.parseDDL(eq("CREATE TABLE t (id INT);"), eq(1), eq("admin")))
                .thenReturn(result);

        mockMvc.perform(post("/api/v1/metadata/ddl/parse")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rawDdl": "CREATE TABLE t (id INT);",
                                  "datasourceId": 1
                                }
                                """))
                .andExpect(status().isOk());

        verify(ddlParserService).parseDDL("CREATE TABLE t (id INT);", 1, "admin");
    }

    @Test
    @DisplayName("ADMIN 可以确认保存 DDL")
    void adminCanConfirmDdl() throws Exception {
        MetaDatasource datasource = new MetaDatasource();
        datasource.setId(1);
        datasource.setActive(true);
        when(metaDatasourceMapper.selectById(1)).thenReturn(datasource);

        mockMvc.perform(post("/api/v1/metadata/ddl/confirm")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "uploadId": "upload-1",
                                  "datasourceId": 1,
                                  "tables": [
                                    {
                                      "tableName": "t",
                                      "suggestedLabel": "测试表",
                                      "columns": [
                                        {
                                          "columnName": "id",
                                          "dataType": "INT",
                                          "label": "ID",
                                          "description": "主键",
                                          "commentSource": "ddl"
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        verify(ddlParserService).confirmAndSave(org.mockito.ArgumentMatchers.any(), eq("admin"));
    }

    @Test
    @DisplayName("MANAGER 不能解析 DDL")
    void managerCannotParseDdl() throws Exception {
        mockMvc.perform(post("/api/v1/metadata/ddl/parse")
                        .with(user("wanger").roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rawDdl": "CREATE TABLE t (id INT);",
                                  "datasourceId": 1
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(ddlParserService, never()).parseDDL(any(), any(), any());
    }

    @Test
    @DisplayName("MANAGER 可以读取表关系图需要的表和JOIN元数据")
    void managerCanReadSchemaGraphMetadata() throws Exception {
        MetaTable table = new MetaTable();
        table.setId(1);
        table.setDatasourceId(1);
        table.setTableName("fact_gross_profit");
        MetaJoinEdge join = new MetaJoinEdge();
        join.setId(1);
        join.setFromTableId(1);
        join.setToTableId(2);
        join.setJoinType("LEFT");
        when(metadataService.listTablesByDatasource(1)).thenReturn(List.of(table));
        when(metadataService.listJoinEdges()).thenReturn(List.of(join));

        mockMvc.perform(get("/api/v1/metadata/tables")
                        .param("datasourceId", "1")
                        .with(user("wanger").roles("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/metadata/joins")
                        .with(user("wanger").roles("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN 可以发布、取消发布和批量发布表")
    void adminCanPublishAndUnpublishTables() throws Exception {
        MetaTable table = new MetaTable();
        table.setId(1);
        table.setActive(true);
        table.setPublished(true);
        when(metadataService.publishTable(1)).thenReturn(table);

        MetaTable unpublished = new MetaTable();
        unpublished.setId(1);
        unpublished.setActive(true);
        unpublished.setPublished(false);
        when(metadataService.unpublishTable(1)).thenReturn(unpublished);

        mockMvc.perform(put("/api/v1/metadata/tables/1/publish")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/metadata/tables/1/unpublish")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/metadata/tables/batch-publish")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2]"))
                .andExpect(status().isOk());

        verify(metadataService).publishTable(1);
        verify(metadataService).unpublishTable(1);
        verify(metadataService).publishTables(List.of(1, 2));
    }

    @Test
    @DisplayName("MANAGER 不能发布表")
    void managerCannotPublishTable() throws Exception {
        mockMvc.perform(put("/api/v1/metadata/tables/1/publish")
                        .with(user("wanger").roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("普通 USER 不能解析 DDL")
    void userCannotParseDdl() throws Exception {
        mockMvc.perform(post("/api/v1/metadata/ddl/parse")
                        .with(user("wangsi").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rawDdl": "CREATE TABLE t (id INT);",
                                  "datasourceId": 1
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @org.springframework.context.annotation.Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .build();
        }
    }
}
