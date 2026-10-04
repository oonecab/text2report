package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaColumn;
import com.text2report.domain.meta.entity.MetaDdlUpload;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import com.text2report.domain.meta.mapper.MetaDdlUploadMapper;
import com.text2report.domain.meta.mapper.MetaTableMapper;
import com.text2report.model.dto.DDLConfirmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL Parser Service - parses SQL DDL statements to extract metadata
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DDLParserService {

    private final MetaTableMapper metaTableMapper;
    private final MetaColumnMapper metaColumnMapper;
    private final MetaDdlUploadMapper metaDdlUploadMapper;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final QueryCacheService queryCacheService;

    // Regex patterns for DDL comment formats
    private static final Pattern LINE_COMMENT_PATTERN = Pattern.compile(
            "--\\s*([^\\-]+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern SP_ADDEXTENDEDPROPERTY_PATTERN = Pattern.compile(
            "EXEC\\s+sp_addextendedproperty\\s+@name\\s*=\\s*N\\s*['\"]([^'\"]+?)['\"]\\s*,\\s*@value\\s*=\\s*N\\s*['\"]([^'\"]+?)['\"]",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern COMMENT_ON_COLUMN_PATTERN = Pattern.compile(
            "COMMENT\\s+ON\\s+COLUMN\\s+([\\w.]+)\\s+IS\\s+['\"]([^'\"]+?)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_ON_TABLE_PATTERN = Pattern.compile(
            "COMMENT\\s+ON\\s+TABLE\\s+([\\w.]+)\\s+IS\\s+['\"]([^'\"]+?)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_DEF_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s+([A-Za-z0-9_(),\\s]+?)(?:\\s*,\\s*PRIMARY\\s+KEY|\\s*,\\s*$|\\s*PRIMARY\\s+KEY|\\s*$)",
            Pattern.MULTILINE);
    private static final Pattern COLUMN_NAME_TYPE_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s+([A-Za-z0-9_,()\\s]+?)(?:\\s*--.*)?$",
            Pattern.MULTILINE);

    /**
     * Parse DDL content and return preview result (no DB write)
     *
     * @param rawDDL raw DDL SQL content
     * @param datasourceId data source ID to associate with parsed tables
     * @param uploadBy user who uploaded
     * @return parsed DDL result with tables and columns
     */
    public DDLParseResult parseDDL(String rawDDL, Integer datasourceId, String uploadBy) {
        log.info("Parsing DDL, datasourceId: {}, length: {}", datasourceId, rawDDL != null ? rawDDL.length() : 0);

        if (rawDDL == null || rawDDL.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "DDL content is empty");
        }

        String uploadId = UUID.randomUUID().toString();

        // Extract comments by format
        Map<String, String> columnDescriptions = new HashMap<>();
        extractSpExtendedPropertyComments(rawDDL, columnDescriptions);
        extractCommentOnTableComments(rawDDL, columnDescriptions);
        extractCommentOnColumnComments(rawDDL, columnDescriptions);
        Map<String, String> lineComments = extractLineComments(rawDDL);
        columnDescriptions.putAll(lineComments);

        // Parse CREATE TABLE statements
        List<DDLConfirmRequest.ParsedTable> tables = parseCreateTableStatements(rawDDL, columnDescriptions);
        List<String> warnings = buildDuplicateWarnings(tables, datasourceId);

        DDLParseResult result = new DDLParseResult();
        result.setUploadId(uploadId);
        result.setDatasourceId(datasourceId);
        result.setTables(tables);
        result.setWarnings(warnings);
        return result;
    }

    /**
     * Backward-compatible: parse DDL with datasourceId but default uploadBy
     */
    public DDLParseResult parseDDL(String rawDDL, Integer datasourceId) {
        return parseDDL(rawDDL, datasourceId, "system");
    }

    /**
     * Backward-compatible: parse DDL without uploadBy parameter
     */
    public DDLParseResult parseDDL(String rawDDL) {
        return parseDDL(rawDDL, null, "system");
    }

    /**
     * Confirm and save parsed DDL to metadata tables
     *
     * @param request confirm request with upload ID and parsed data
     * @param uploadBy user who uploaded
     */
    @Transactional(transactionManager = "metaTransactionManager")
    public void confirmAndSave(DDLConfirmRequest request, String uploadBy) {
        if (request == null || request.getTables() == null || request.getTables().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "No tables to save");
        }

        log.info("Confirming DDL save, uploadId: {}, tables: {}", request.getUploadId(), request.getTables().size());

        // Save each table
        int columnsSaved = 0;
        Set<String> requestTableKeys = new HashSet<>();
        for (DDLConfirmRequest.ParsedTable parsedTable : request.getTables()) {
            Integer datasourceId = request.getDatasourceId() != null ? request.getDatasourceId() : 1;
            String schemaName = normalizeSchemaName(parsedTable.getSchemaName());
            String tableKey = tableKey(datasourceId, schemaName, parsedTable.getTableName());
            if (!requestTableKeys.add(tableKey)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "DDL 中存在重复表定义: " + displayTableName(schemaName, parsedTable.getTableName()));
            }

            // DDL import is only for adding new metadata. Existing tables must be maintained
            // through the table/column management pages to avoid overwriting curated metadata.
            LambdaQueryWrapper<MetaTable> tableWrapper = tableIdentityWrapper(
                    datasourceId, schemaName, parsedTable.getTableName());
            MetaTable existingTable = metaTableMapper.selectOne(tableWrapper);
            if (existingTable != null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "表已存在，DDL 导入不允许覆盖已有元数据，请在表与字段管理中维护: "
                                + displayTableName(schemaName, parsedTable.getTableName()));
            }

            MetaTable table = new MetaTable();
            table.setTableName(parsedTable.getTableName());
            table.setSchemaName(schemaName);
            table.setLabel(parsedTable.getSuggestedLabel());
            table.setDescription(parsedTable.getDescription());
            table.setDatasourceId(datasourceId);
            table.setActive(true);

            table.setPublished(false);
            metaTableMapper.insert(table);

            // Save columns
            for (DDLConfirmRequest.ParsedColumn parsedColumn : parsedTable.getColumns()) {
                LambdaQueryWrapper<MetaColumn> colWrapper = new LambdaQueryWrapper<>();
                colWrapper.eq(MetaColumn::getTableId, table.getId())
                        .eq(MetaColumn::getColumnName, parsedColumn.getColumnName());
                MetaColumn existingCol = metaColumnMapper.selectOne(colWrapper);

                MetaColumn column = new MetaColumn();
                column.setTableId(table.getId());
                column.setColumnName(parsedColumn.getColumnName());
                column.setDataType(parsedColumn.getDataType());
                column.setLabel(parsedColumn.getLabel());
                column.setDescription(parsedColumn.getDescription());
                column.setCommentSource(parsedColumn.getCommentSource());
                column.setActive(true);

                if (existingCol != null) {
                    column.setId(existingCol.getId());
                    metaColumnMapper.updateById(column);
                } else {
                    metaColumnMapper.insert(column);
                }
                columnsSaved++;
            }

            LambdaQueryWrapper<MetaColumn> savedColumnWrapper = new LambdaQueryWrapper<>();
            savedColumnWrapper.eq(MetaColumn::getTableId, table.getId())
                    .eq(MetaColumn::getActive, true)
                    .orderByAsc(MetaColumn::getSortOrder, MetaColumn::getColumnName);
            List<MetaColumn> savedColumns = metaColumnMapper.selectList(savedColumnWrapper);
            embeddingService.embedAndStoreAsync(
                    "table_description",
                    table.getId(),
                    embeddingService.buildTableDescriptionText(table, savedColumns)
            );
        }

        // Save DDL upload record
        MetaDdlUpload upload = new MetaDdlUpload();
        upload.setFilename(request.getUploadId());
        upload.setTableCount(request.getTables().size());
        upload.setColumnCount(columnsSaved);
        upload.setStatus("confirmed");
        try {
            upload.setErrorMessage(objectMapper.writeValueAsString(request.getTables()));
        } catch (Exception e) {
            // ignore serialization error
        }
        metaDdlUploadMapper.insert(upload);
        queryCacheService.invalidateAllQueryCache();

        log.info("DDL confirmed and saved: {} tables, {} columns. New tables are saved but unpublished; admin confirmation is required before they become queryable.",
                request.getTables().size(), columnsSaved);
    }

    /**
     * Backward-compatible: parse and commit DDL in one step
     */
    @Transactional(transactionManager = "metaTransactionManager")
    public DDLCommitResult commitDDL(String ddlContent) {
        if (ddlContent == null || ddlContent.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "DDL content is empty");
        }

        DDLParseResult parsed = parseDDL(ddlContent, null, "system");
        DDLConfirmRequest confirmRequest = new DDLConfirmRequest();
        confirmRequest.setUploadId(parsed.getUploadId());
        confirmRequest.setTables(parsed.getTables());
        confirmAndSave(confirmRequest, "system");

        DDLCommitResult result = new DDLCommitResult();
        result.setTablesCreated(parsed.getTables().size());
        result.setColumnsCreated(parsed.getTables().stream()
                .mapToInt(t -> t.getColumns() != null ? t.getColumns().size() : 0).sum());
        result.setTablesUpdated(0);
        result.setWarnings(List.of());
        return result;
    }

    private void extractSpExtendedPropertyComments(String ddl, Map<String, String> comments) {
        // Pattern to match: EXEC sp_addextendedproperty @name=N'MS_Description', @value=N'注释内容',
        //   @level1type=N'Table', @level1name=N'表名',
        //   @level2type=N'Column', @level2name=N'列名'
        Pattern pattern = Pattern.compile(
                "sp_addextendedproperty\\s+@name\\s*=\\s*N?'MS_Description'\\s*," +
                "\\s*@value\\s*=\\s*N?'([^']*)'\\s*," +
                "(?:.*?)@level1name\\s*=\\s*N?'([^']*)'\\s*," +
                "(?:.*?)@level2name\\s*=\\s*N?'([^']*)'",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(ddl);
        while (matcher.find()) {
            String comment = matcher.group(1).trim();
            String tableName = matcher.group(2).trim();
            String columnName = matcher.group(3).trim();
            // Write to comments Map with table.column as key
            String key = (tableName + "." + columnName).toLowerCase();
            comments.put(key, comment);
        }
    }

    private void extractCommentOnColumnComments(String ddl, Map<String, String> comments) {
        Matcher matcher = COMMENT_ON_COLUMN_PATTERN.matcher(ddl);
        while (matcher.find()) {
            String fullColumnName = matcher.group(1).trim();
            String description = matcher.group(2).trim();
            comments.put(fullColumnName.toLowerCase(), description);
        }
    }

    private void extractCommentOnTableComments(String ddl, Map<String, String> comments) {
        Matcher matcher = COMMENT_ON_TABLE_PATTERN.matcher(ddl);
        while (matcher.find()) {
            String fullTableName = matcher.group(1).trim();
            String tableName = fullTableName.contains(".")
                    ? fullTableName.substring(fullTableName.lastIndexOf('.') + 1)
                    : fullTableName;
            String description = matcher.group(2).trim();
            comments.put(fullTableName.toLowerCase(Locale.ROOT), description);
            comments.put(tableName.toLowerCase(Locale.ROOT), description);
        }
    }

    private Map<String, String> extractLineComments(String ddl) {
        Map<String, String> comments = new HashMap<>();
        String[] lines = ddl.split("\n");
        String currentTable = null;

        for (String line : lines) {
            // Detect table context
            Matcher tableMatcher = Pattern.compile(
                    "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:(\\w+)\\s*\\.\\s*)?(\\w+)",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (tableMatcher.find()) {
                currentTable = tableMatcher.group(2).trim();
            }

            // Extract line comments (-- description at end of line after column definition)
            Matcher commentMatcher = Pattern.compile("--\\s*(.+?)\\s*$").matcher(line);
            if (commentMatcher.find()) {
                String comment = commentMatcher.group(1).trim();
                // Try to find preceding column definition
                String beforeComment = line.substring(0, commentMatcher.start());
                Matcher colMatcher = Pattern.compile("^\\s*(\\w+)\\s+\\S+.*$").matcher(beforeComment);
                if (colMatcher.find()) {
                    String colName = colMatcher.group(1).trim();
                    if (currentTable != null && !colName.equalsIgnoreCase("PRIMARY")) {
                        comments.put((currentTable + "." + colName).toLowerCase(), comment);
                    }
                }
            }
        }
        return comments;
    }

    private List<DDLConfirmRequest.ParsedTable> parseCreateTableStatements(String ddl, Map<String, String> comments) {
        List<DDLConfirmRequest.ParsedTable> tables = new ArrayList<>();
        Pattern createPattern = Pattern.compile(
                "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:(\\w+)\\s*\\.\\s*)?(\\w+)\\s*\\(\\s*([^;]+)\\s*\\)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher createMatcher = createPattern.matcher(ddl);

        while (createMatcher.find()) {
            String schemaName = normalizeSchemaName(createMatcher.group(1));
            String tableName = createMatcher.group(2).trim();
            String columnsBlock = createMatcher.group(3);
            DDLConfirmRequest.ParsedTable table = parseColumnsBlock(schemaName, tableName, columnsBlock, comments);
            tables.add(table);
        }

        return tables;
    }

    private DDLConfirmRequest.ParsedTable parseColumnsBlock(String schemaName, String tableName, String columnsBlock, Map<String, String> comments) {
        DDLConfirmRequest.ParsedTable table = new DDLConfirmRequest.ParsedTable();
        table.setSchemaName(schemaName);
        table.setTableName(tableName);
        String tableDesc = firstPresent(
                comments.get(tableName.toLowerCase(Locale.ROOT)),
                comments.get(((schemaName == null ? "public" : schemaName) + "." + tableName).toLowerCase(Locale.ROOT)));
        table.setSuggestedLabel(tableDesc);
        table.setDescription(tableDesc);

        List<DDLConfirmRequest.ParsedColumn> columns = new ArrayList<>();
        Pattern constraintPattern = Pattern.compile(
                "^\\s*(?:PRIMARY\\s+KEY|FOREIGN\\s+KEY|CONSTRAINT|UNIQUE|CHECK|INDEX)\\b",
                Pattern.CASE_INSENSITIVE);

        for (String definition : splitColumns(columnsBlock)) {
            if (constraintPattern.matcher(definition).find() || definition.trim().startsWith("--")) {
                continue;
            }
            parseAndAddColumn(definition, schemaName, tableName, comments, columns);
        }

        table.setColumns(columns);
        return table;
    }

    private List<String> buildDuplicateWarnings(List<DDLConfirmRequest.ParsedTable> tables, Integer requestDatasourceId) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        Integer datasourceId = requestDatasourceId != null ? requestDatasourceId : 1;
        List<String> warnings = new ArrayList<>();
        Set<String> requestTableKeys = new HashSet<>();

        for (DDLConfirmRequest.ParsedTable table : tables) {
            String schemaName = normalizeSchemaName(table.getSchemaName());
            String tableKey = tableKey(datasourceId, schemaName, table.getTableName());
            if (!requestTableKeys.add(tableKey)) {
                warnings.add("DDL 中存在重复表定义: " + displayTableName(schemaName, table.getTableName()));
                continue;
            }

            MetaTable existing = metaTableMapper.selectOne(tableIdentityWrapper(
                    datasourceId, schemaName, table.getTableName()));
            if (existing != null) {
                warnings.add("表已存在，保存将被拒绝，请在表与字段管理中维护: "
                        + displayTableName(schemaName, table.getTableName()));
            }
        }

        return warnings;
    }

    private LambdaQueryWrapper<MetaTable> tableIdentityWrapper(Integer datasourceId, String schemaName, String tableName) {
        LambdaQueryWrapper<MetaTable> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MetaTable::getDatasourceId, datasourceId)
                .eq(MetaTable::getTableName, tableName);
        if (StringUtils.hasText(schemaName)) {
            wrapper.eq(MetaTable::getSchemaName, schemaName);
        } else {
            wrapper.isNull(MetaTable::getSchemaName);
        }
        return wrapper;
    }

    private String normalizeSchemaName(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return "public".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private String tableKey(Integer datasourceId, String schemaName, String tableName) {
        return datasourceId + ":" + (schemaName == null ? "" : schemaName.toLowerCase(Locale.ROOT))
                + "." + (tableName == null ? "" : tableName.toLowerCase(Locale.ROOT));
    }

    private String displayTableName(String schemaName, String tableName) {
        return StringUtils.hasText(schemaName) ? schemaName + "." + tableName : tableName;
    }

    private String firstPresent(String first, String second) {
        return first != null ? first : second;
    }

    private void parseAndAddColumn(String colDef, String schemaName, String tableName, Map<String, String> comments,
                                   List<DDLConfirmRequest.ParsedColumn> columns) {
        Matcher colMatcher = Pattern.compile("^\\s*(\\w+)\\s+(.+?)\\s*$", Pattern.DOTALL).matcher(colDef);
        if (!colMatcher.find()) return;

        String colName = colMatcher.group(1).trim();
        String dataType = colMatcher.group(2)
                .replaceAll("(?m)\\s*--.*$", "")
                .replaceAll("\\s*,\\s*$", "")
                .trim()
                .replaceAll("\\s+", " ");

        DDLConfirmRequest.ParsedColumn col = new DDLConfirmRequest.ParsedColumn();
        col.setColumnName(colName);
        col.setDataType(dataType);

        String fullColKey = (tableName + "." + colName).toLowerCase(Locale.ROOT);
        String comment = firstPresent(
                comments.get(fullColKey),
                comments.get(((schemaName == null ? "public" : schemaName) + "." + tableName + "." + colName).toLowerCase(Locale.ROOT)));
        if (comment != null) {
            col.setLabel(comment);
            col.setDescription(comment);
            col.setCommentSource("ddl");
            col.setNeedsReview(false);
        } else {
            Matcher lineCommentMatcher = Pattern.compile("--\\s*(.+?)\\s*$").matcher(colDef);
            if (lineCommentMatcher.find()) {
                col.setLabel(lineCommentMatcher.group(1).trim());
                col.setDescription(lineCommentMatcher.group(1).trim());
                col.setCommentSource("ddl");
                col.setNeedsReview(false);
            } else {
                col.setCommentSource("manual");
                col.setNeedsReview(true);
            }
        }

        columns.add(col);
    }

    /**
     * Split column definitions by comma, ignoring:
     * 1. Commas inside parentheses (e.g., DECIMAL(18,2))
     * 2. Commas inside line comments (e.g., -- 账期月份，格式YYYYMM)
     * For example: "DECIMAL(10,2), NUMERIC(18,4), VARCHAR(100)" splits into 3 columns.
     */
    private List<String> splitColumns(String columnsBlock) {
        columnsBlock = columnsBlock.replaceAll("(?m)--.*$", "");
        List<String> columns = new ArrayList<>();
        int depth = 0;
        boolean inComment = false;
        int start = 0;
        for (int i = 0; i < columnsBlock.length(); i++) {
            char c = columnsBlock.charAt(i);

            // Check for comment start (--) outside of parentheses
            if (!inComment && depth == 0 && c == '-' && i + 1 < columnsBlock.length() && columnsBlock.charAt(i + 1) == '-') {
                inComment = true;
            }
            // Check for comment end (newline)
            else if (inComment && c == '\n') {
                inComment = false;
            }
            // Track parentheses depth (only outside comments)
            else if (!inComment) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }

            // Split on comma only if not inside parentheses and not in a comment
            if (c == ',' && depth == 0 && !inComment) {
                String col = columnsBlock.substring(start, i).trim();
                if (!col.isBlank()) {
                    columns.add(col);
                }
                start = i + 1;
            }
        }
        // Last column
        String last = columnsBlock.substring(start).trim();
        if (!last.isBlank()) {
            columns.add(last);
        }
        return columns;
    }

    // ============ DTO classes ============

    @lombok.Data
    public static class DDLParseResult {
        private String uploadId;
        private Integer datasourceId;
        private List<DDLConfirmRequest.ParsedTable> tables;
        private List<String> warnings = List.of();
    }

    @lombok.Data
    public static class DDLCommitResult {
        private int tablesCreated;
        private int columnsCreated;
        private int tablesUpdated;
        private List<String> warnings;
    }

    // Backward-compatible: keep old DDLCommitResult at module level
    @lombok.Data
    public static class DDLCommitResult2 {
        private int tablesCreated;
        private int columnsCreated;
        private List<String> warnings;
    }
}
