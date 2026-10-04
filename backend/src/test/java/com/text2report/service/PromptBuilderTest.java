package com.text2report.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PromptBuilder
 */
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
        // Use classpath for tests
        ReflectionTestUtils.setField(promptBuilder, "promptDir", "classpath:prompts/");
    }

    @Nested
    @DisplayName("Template loading")
    class TemplateLoadingTests {

        @Test
        @DisplayName("Should load sql_generation_v1 template")
        void shouldLoadSqlGenerationTemplate() {
            String template = promptBuilder.loadTemplate("sql_generation_v1");
            assertNotNull(template);
            assertTrue(template.contains("数据库方言"));
            assertTrue(template.contains("表选择建议"));
            assertTrue(template.contains("recommendation"));
            assertTrue(template.contains("{dialect}"));
        }

        @Test
        @DisplayName("Should load sql_repair_v1 template")
        void shouldLoadSqlRepairTemplate() {
            String template = promptBuilder.loadTemplate("sql_repair_v1");
            assertNotNull(template);
            assertTrue(template.contains("上一次生成的 SQL"));
        }

        @Test
        @DisplayName("Should load result_summary_v1 template")
        void shouldLoadResultSummaryTemplate() {
            String template = promptBuilder.loadTemplate("result_summary_v1");
            assertNotNull(template);
            assertTrue(template.contains("查询结果统计"));
        }
    }

    @Nested
    @DisplayName("Placeholder filling")
    class PlaceholderFillingTests {

        @Test
        @DisplayName("Should replace all placeholders")
        void shouldReplaceAllPlaceholders() {
            String template = "Hello {name}, you are {age} years old";
            Map<String, String> vars = Map.of("name", "Alice", "age", "30");
            String result = promptBuilder.fill(template, vars);
            assertEquals("Hello Alice, you are 30 years old", result);
        }

        @Test
        @DisplayName("Should leave unreplaced placeholders as-is")
        void shouldLeaveUnreplacedPlaceholders() {
            String template = "Hello {name}, you like {food}";
            Map<String, String> vars = Map.of("name", "Bob");
            String result = promptBuilder.fill(template, vars);
            assertEquals("Hello Bob, you like {food}", result);
        }

        @Test
        @DisplayName("Should handle null template")
        void shouldHandleNullTemplate() {
            String result = promptBuilder.fill(null, Map.of("key", "value"));
            assertNull(result);
        }

        @Test
        @DisplayName("Should handle null variables")
        void shouldHandleNullVariables() {
            String template = "Hello {name}";
            String result = promptBuilder.fill(template, null);
            assertEquals("Hello {name}", result);
        }
    }

    @Nested
    @DisplayName("SQL generation prompt building")
    class SqlGenerationPromptTests {

        @Test
        @DisplayName("Should build SQL generation prompt with all fields")
        void shouldBuildSqlGenerationPrompt() {
            String prompt = promptBuilder.buildSqlGenerationPrompt(
                    "postgresql",
                    "总公司 > 子公司A",
                    "1,2,11",
                    "{\"tables\":[]}",
                    "查询子公司A的毛利"
            );
            assertNotNull(prompt);
            assertTrue(prompt.contains("postgresql"));
            assertTrue(prompt.contains("总公司 > 子公司A"));
            assertTrue(prompt.contains("1,2,11"));
            assertTrue(prompt.contains("查询子公司A的毛利"));
            assertTrue(prompt.contains("表选择建议"));
        }

        @Test
        @DisplayName("Should use default dialect when null")
        void shouldUseDefaultDialect() {
            String prompt = promptBuilder.buildSqlGenerationPrompt(
                    null, "org", "ids", "{}", "question");
            assertNotNull(prompt);
            assertTrue(prompt.contains("postgresql"));
        }
    }

    @Nested
    @DisplayName("SQL repair prompt building")
    class SqlRepairPromptTests {

        @Test
        @DisplayName("Should build SQL repair prompt")
        void shouldBuildSqlRepairPrompt() {
            String prompt = promptBuilder.buildSqlRepairPrompt(
                    "postgresql",
                    "{}",
                    "query",
                    "SELECT * FROM table",
                    "column does not exist"
            );
            assertNotNull(prompt);
            assertTrue(prompt.contains("SELECT * FROM table"));
            assertTrue(prompt.contains("column does not exist"));
        }
    }

    @Nested
    @DisplayName("Result summary prompt building")
    class ResultSummaryPromptTests {

        @Test
        @DisplayName("Should build result summary prompt")
        void shouldBuildResultSummaryPrompt() {
            String prompt = promptBuilder.buildResultSummaryPrompt(
                    "查询毛利",
                    "{\"rowCount\": 10}"
            );
            assertNotNull(prompt);
            assertTrue(prompt.contains("查询毛利"));
            assertTrue(prompt.contains("rowCount"));
        }
    }
}
