package com.text2report.service;

import com.text2report.domain.meta.entity.MetaGlossary;
import com.text2report.domain.meta.entity.MetaSqlExample;
import com.text2report.domain.meta.entity.MetaTable;
import com.text2report.domain.meta.mapper.MetaColumnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Business-aware second-pass ranking for metadata table candidates.
 */
@Service
@RequiredArgsConstructor
public class TableRelevanceScorer {

    public static final double WEIGHT_GLOSSARY_REFERENCE = 0.25d;
    public static final double WEIGHT_EXAMPLE_REFERENCE = 0.25d;
    public static final double WEIGHT_TABLE_TYPE = 0.15d;
    public static final double WEIGHT_METADATA_QUALITY = 0.15d;
    public static final double WEIGHT_VECTOR_SIMILARITY = 0.20d;

    private static final Pattern FROM_JOIN_TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)?)"
    );
    private static final Pattern LOW_TRUST_NAME_PATTERN = Pattern.compile("(?i)(?:_test|_tmp|_backup|_bak|_old)(?:$|_)");

    private final MetaColumnMapper metaColumnMapper;

    /**
     * 对候选表列表做多维度加权排序。
     */
    public List<MetaTable> rankTables(List<MetaTable> candidateTables,
                                      List<MetaGlossary> matchedGlossary,
                                      List<MetaSqlExample> matchedExamples,
                                      String question) {
        return rankTables(candidateTables, matchedGlossary, matchedExamples, question, Map.of());
    }

    public List<MetaTable> rankTables(List<MetaTable> candidateTables,
                                      List<MetaGlossary> matchedGlossary,
                                      List<MetaSqlExample> matchedExamples,
                                      String question,
                                      Map<Integer, Double> vectorSimilarityByTableId) {
        if (candidateTables == null || candidateTables.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<Integer, MetaTable> deduped = new LinkedHashMap<>();
        for (MetaTable table : candidateTables) {
            if (table != null && Boolean.TRUE.equals(table.getPublished())) {
                deduped.putIfAbsent(table.getId(), table);
            }
        }
        if (deduped.isEmpty()) {
            return List.of();
        }

        Map<Integer, Integer> columnCounts = loadColumnCounts(deduped.values());
        Set<String> candidateNames = deduped.values().stream()
                .map(MetaTable::getTableName)
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> glossaryReferences = extractReferencedTableNamesFromGlossary(matchedGlossary, candidateNames);
        Set<String> exampleReferences = extractReferencedTableNamesFromExamples(matchedExamples, candidateNames);
        Map<String, Integer> exampleReferenceCounts = countExampleReferences(matchedExamples, candidateNames);

        return deduped.values().stream()
                .map(table -> scoredTable(
                        table,
                        columnCounts.getOrDefault(table.getId(), 0),
                        glossaryReferences,
                        exampleReferences,
                        exampleReferenceCounts,
                        vectorSimilarityByTableId
                ))
                .sorted(Comparator.comparingDouble(ScoredTable::score).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredTable::exampleReferenceCount).reversed())
                        .thenComparing(Comparator.comparingInt(ScoredTable::tablePriority).reversed())
                        .thenComparing(item -> item.table().getTableName(), Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(ScoredTable::table)
                .toList();
    }

    public Set<String> extractReferencedTableNamesFromGlossary(List<MetaGlossary> glossary, Collection<String> knownTableNames) {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        Set<String> normalizedKnownNames = normalizeNames(knownTableNames);
        for (MetaGlossary item : safeList(glossary)) {
            tableNames.addAll(extractReferencedTableNames(
                    String.join("\n",
                            nullToEmpty(item.getSqlSnippet()),
                            nullToEmpty(item.getDefinition()),
                            nullToEmpty(item.getAliases())),
                    normalizedKnownNames
            ));
        }
        return tableNames;
    }

    public Set<String> extractReferencedTableNamesFromExamples(List<MetaSqlExample> examples, Collection<String> knownTableNames) {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        Set<String> normalizedKnownNames = normalizeNames(knownTableNames);
        for (MetaSqlExample example : safeList(examples)) {
            tableNames.addAll(extractReferencedTableNames(example.getSqlQuery(), normalizedKnownNames));
        }
        return tableNames;
    }

    private ScoredTable scoredTable(MetaTable table,
                                    int columnCount,
                                    Set<String> glossaryReferences,
                                    Set<String> exampleReferences,
                                    Map<String, Integer> exampleReferenceCounts,
                                    Map<Integer, Double> vectorSimilarityByTableId) {
        String tableName = normalize(table.getTableName());
        double glossaryScore = glossaryReferences.contains(tableName) ? 1.0d : 0.0d;
        double exampleScore = exampleReferences.contains(tableName) ? 1.0d : 0.0d;
        double typeScore = tableTypeScore(table);
        double qualityScore = metadataQualityScore(table, columnCount);
        double vectorScore = clamp(vectorSimilarityByTableId.getOrDefault(table.getId(), 0.5d));

        double score = glossaryScore * WEIGHT_GLOSSARY_REFERENCE
                + exampleScore * WEIGHT_EXAMPLE_REFERENCE
                + typeScore * WEIGHT_TABLE_TYPE
                + qualityScore * WEIGHT_METADATA_QUALITY
                + vectorScore * WEIGHT_VECTOR_SIMILARITY;

        if (isSparseMetadata(table, columnCount)) {
            score *= 0.5d;
        }
        if (LOW_TRUST_NAME_PATTERN.matcher(tableName).find()) {
            score *= 0.3d;
        }

        return new ScoredTable(table, score, exampleReferenceCounts.getOrDefault(tableName, 0), tablePriority(table));
    }

    private Map<Integer, Integer> loadColumnCounts(Collection<MetaTable> tables) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (MetaTable table : tables) {
            counts.put(table.getId(), metaColumnMapper.selectByTableId(table.getId()).size());
        }
        return counts;
    }

    private double tableTypeScore(MetaTable table) {
        int priority = tablePriority(table);
        if (priority == 2) {
            return 1.0d;
        }
        if (priority == 1) {
            return 0.7d;
        }
        return 0.3d;
    }

    private int tablePriority(MetaTable table) {
        String type = normalize(table.getTableType());
        String name = normalize(table.getTableName());
        if ("fact".equals(type) || name.startsWith("fact_")) {
            return 2;
        }
        if ("dimension".equals(type) || "dim".equals(type) || name.startsWith("dim_")) {
            return 1;
        }
        return 0;
    }

    private double metadataQualityScore(MetaTable table, int columnCount) {
        double score = Boolean.TRUE.equals(table.getPublished()) ? 0.5d : 0.0d;
        if (StringUtils.hasText(table.getDescription()) && table.getDescription().trim().length() > 10) {
            score += 0.2d;
        }
        if (columnCount >= 3) {
            score += 0.15d;
        }
        if (StringUtils.hasText(table.getLabel())) {
            score += 0.15d;
        }
        return Math.min(score, 1.0d);
    }

    private boolean isSparseMetadata(MetaTable table, int columnCount) {
        return !StringUtils.hasText(table.getDescription())
                && !StringUtils.hasText(table.getLabel())
                && columnCount <= 2;
    }

    private Map<String, Integer> countExampleReferences(List<MetaSqlExample> examples, Collection<String> knownTableNames) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> normalizedKnownNames = normalizeNames(knownTableNames);
        for (MetaSqlExample example : safeList(examples)) {
            for (String tableName : extractReferencedTableNames(example.getSqlQuery(), normalizedKnownNames)) {
                counts.merge(tableName, 1, Integer::sum);
            }
        }
        return counts;
    }

    private Set<String> extractReferencedTableNames(String text, Collection<String> knownTableNames) {
        LinkedHashSet<String> tableNames = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return tableNames;
        }
        String normalizedText = normalize(text);
        Matcher matcher = FROM_JOIN_TABLE_PATTERN.matcher(text);
        while (matcher.find()) {
            tableNames.add(simpleTableName(matcher.group(1)));
        }
        for (String knownTableName : normalizeNames(knownTableNames)) {
            if (containsIdentifier(normalizedText, knownTableName)) {
                tableNames.add(knownTableName);
            }
        }
        return tableNames;
    }

    private boolean containsIdentifier(String text, String identifier) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(identifier)) {
            return false;
        }
        return Pattern.compile("(?<![a-zA-Z0-9_])" + Pattern.quote(identifier) + "(?![a-zA-Z0-9_])")
                .matcher(text)
                .find();
    }

    private String simpleTableName(String tableName) {
        String normalized = normalize(tableName);
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex >= 0 ? normalized.substring(dotIndex + 1) : normalized;
    }

    private Set<String> normalizeNames(Collection<String> names) {
        if (names == null) {
            return Set.of();
        }
        return names.stream()
                .filter(StringUtils::hasText)
                .map(this::simpleTableName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ScoredTable(MetaTable table, double score, int exampleReferenceCount, int tablePriority) {
    }
}
