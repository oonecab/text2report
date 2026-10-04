package com.text2report.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Prompt template loader and filler
 *
 * Loads templates from filesystem (production) or classpath (development fallback).
 * Template placeholders use {key} syntax and are replaced via fill().
 */
@Slf4j
@Component
public class PromptBuilder {

    @Value("${app.prompt-dir:prompts/}")
    private String promptDir;

    /**
     * Load a template file by name (without extension).
     * Searches filesystem first, then classpath.
     *
     * @param templateName e.g. "sql_generation_v1"
     * @return template content
     */
    public String loadTemplate(String templateName) {
        String fileName = templateName + ".txt";

        // Check for classpath: prefix
        if (promptDir != null && promptDir.startsWith("classpath:")) {
            String classpathDir = promptDir.substring("classpath:".length()).replaceAll("^/", "");
            try {
                ClassPathResource resource = new ClassPathResource(classpathDir + "/" + fileName);
                String content = new String(resource.getInputStream().readAllBytes());
                log.debug("Loaded template from classpath: {}/{}", classpathDir, fileName);
                return content;
            } catch (IOException e) {
                log.error("Template not found in classpath: {}/{}", classpathDir, fileName);
                throw new RuntimeException("Prompt template not found: " + templateName);
            }
        }

        // Try multiple filesystem locations to support different working directories
        String[] relativeAttempts = {promptDir, "../" + promptDir, "../../" + promptDir};
        Path triedPath = null;
        for (String rel : relativeAttempts) {
            if (rel == null) continue;
            Path filePath = Paths.get(rel, fileName).toAbsolutePath().normalize();
            triedPath = filePath;
            if (Files.exists(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    log.debug("Loaded template from filesystem: {}", filePath);
                    return content;
                } catch (IOException e) {
                    log.warn("Failed to read template from filesystem {}: {}", filePath, e.getMessage());
                }
            }
        }

        // Fallback to classpath (development / packaged)
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + fileName);
            String content = new String(resource.getInputStream().readAllBytes());
            log.debug("Loaded template from classpath: prompts/{}", fileName);
            return content;
        } catch (IOException e) {
            log.error("Template not found: {} (tried filesystem {} and classpath)", fileName, triedPath);
            throw new RuntimeException("Prompt template not found: " + templateName);
        }
    }

    /**
     * Replace all {key} placeholders with values from the map.
     * Keys not found in the map are left as-is (no exception thrown).
     *
     * @param template template string with {key} placeholders
     * @param variables map of placeholder values
     * @return filled template
     */
    public String fill(String template, Map<String, String> variables) {
        if (template == null || variables == null) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * Build SQL generation prompt
     */
    public String buildSqlGenerationPrompt(
            String dialect,
            String orgContext,
            String orgIds,
            String metadataJson,
            String userQuestion) {
        String template = loadTemplate("sql_generation_v1");
        return fill(template, Map.of(
                "dialect", dialect != null ? dialect : "postgresql",
                "org_context", orgContext != null ? orgContext : "未指定组织",
                "org_ids", orgIds != null ? orgIds : "",
                "metadata_json", metadataJson != null ? metadataJson : "{}",
                "user_question", userQuestion != null ? userQuestion : ""
        ));
    }

    /**
     * Build SQL repair prompt
     */
    public String buildSqlRepairPrompt(
            String dialect,
            String metadataJson,
            String userQuestion,
            String previousSql,
            String errorMessage) {
        String template = loadTemplate("sql_repair_v1");
        return fill(template, Map.of(
                "dialect", dialect != null ? dialect : "postgresql",
                "metadata_json", metadataJson != null ? metadataJson : "{}",
                "user_question", userQuestion != null ? userQuestion : "",
                "previous_sql", previousSql != null ? previousSql : "",
                "error_message", errorMessage != null ? errorMessage : ""
        ));
    }

    /**
     * Build result summary prompt
     */
    public String buildResultSummaryPrompt(
            String userQuestion,
            String resultProfile) {
        String template = loadTemplate("result_summary_v1");
        return fill(template, Map.of(
                "user_question", userQuestion != null ? userQuestion : "",
                "result_profile", resultProfile != null ? resultProfile : "{}"
        ));
    }
}
