package com.text2report.service;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaDatasource;
import com.text2report.domain.meta.entity.SysUserOrg;
import com.text2report.domain.meta.mapper.SysUserOrgMapper;
import com.text2report.model.dto.MetadataContext;
import com.text2report.model.dto.OrgTreeNode;
import com.text2report.model.dto.QueryContext;
import com.text2report.model.dto.QueryPlan;
import com.text2report.service.llm.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves intent, datasource, metadata and permission-aware organization scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryPlanningService {

    private static final Set<String> SUPPORTED_INTENTS = Set.of(
            "query", "trend", "ranking", "aggregation", "comparison", "extreme"
    );

    private final MetadataService metadataService;
    private final OrgService orgService;
    private final UserService userService;
    private final SysUserOrgMapper sysUserOrgMapper;
    private final LLMService llmService;

    public QueryContext buildContext(String question, Integer requestedOrgId, int maxRetries) {
        TraceContext.TraceInfo traceInfo = TraceContext.get();
        String requestId = traceInfo != null ? traceInfo.getRequestId() : null;
        Integer userId = traceInfo != null ? traceInfo.getUserId() : null;
        Integer resolvedOrgId = resolveOrgContext(userId, requestedOrgId);

        return QueryContext.builder()
                .requestId(requestId)
                .question(question)
                .orgId(resolvedOrgId)
                .userId(userId)
                .retryCount(0)
                .maxRetries(maxRetries)
                .startTime(System.nanoTime())
                .build();
    }

    public QueryPlan plan(QueryContext queryContext) {
        String intent = llmService.detectIntent(queryContext.getQuestion());
        validateIntent(intent);

        List<Integer> accessibleOrgIds = userService.resolveAccessibleOrgIds(queryContext.getUserId());
        if (!accessibleOrgIds.contains(queryContext.getOrgId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "您没有该组织的查询权限");
        }

        List<Integer> subtreeIds = orgService.resolveSubtreeIds(queryContext.getOrgId());
        List<Integer> selectedScopeIds = subtreeIds.stream()
                .filter(accessibleOrgIds::contains)
                .toList();
        List<Integer> orgScopeIds = resolveQuestionOrgScope(queryContext.getQuestion(), selectedScopeIds);
        String orgPath = orgService.resolveOrgPath(queryContext.getOrgId());
        Integer datasourceId = resolveDatasourceId();
        MetadataContext metadataContext = metadataService.retrieveForPrompt(queryContext.getQuestion(), datasourceId);
        String metadataJson = metadataService.buildMetadataJson(metadataContext);

        queryContext.setIntent(intent);
        queryContext.setOrgSubtreeIds(orgScopeIds);
        queryContext.setOrgPath(orgPath);
        queryContext.setMetadataContext(metadataContext);
        queryContext.setMetadataJson(metadataJson);

        return QueryPlan.builder()
                .intent(intent)
                .datasourceId(datasourceId)
                .orgScopeIds(orgScopeIds)
                .orgPath(orgPath)
                .metadataContext(metadataContext)
                .metadataJson(metadataJson)
                .build();
    }

    private void validateIntent(String intent) {
        if (!StringUtils.hasText(intent) || "unsupported".equalsIgnoreCase(intent) || !SUPPORTED_INTENTS.contains(intent)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不支持的查询意图");
        }
    }

    private Integer resolveDatasourceId() {
        List<MetaDatasource> datasources = metadataService.listActiveDatasources();
        if (datasources.isEmpty()) {
            throw new BusinessException(ErrorCode.METADATA_ERROR, "未找到可用数据源");
        }
        return datasources.get(0).getId();
    }

    private Integer resolveOrgContext(Integer userId, Integer requestedOrgId) {
        if (requestedOrgId != null) {
            return requestedOrgId;
        }
        if (userId != null) {
            SysUserOrg primary = sysUserOrgMapper.selectPrimaryOrg(userId);
            if (primary != null) {
                return primary.getOrgId();
            }
        }

        Integer traceOrgId = TraceContext.getOrgId();
        if (traceOrgId != null) {
            return traceOrgId;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "orgId 不能为空");
    }

    private List<Integer> resolveQuestionOrgScope(String question, List<Integer> selectedScopeIds) {
        if (!StringUtils.hasText(question) || selectedScopeIds == null || selectedScopeIds.isEmpty()) {
            return selectedScopeIds;
        }

        Set<Integer> mentionedScopeIds = new LinkedHashSet<>();
        boolean hasMentionedOrg = false;
        for (OrgTreeNode node : flattenOrgTree(safeFullOrgTree())) {
            if (!isOrgMentioned(question, node)) {
                continue;
            }
            hasMentionedOrg = true;
            List<Integer> matchedSubtreeIds = orgService.resolveSubtreeIds(node.getId());
            matchedSubtreeIds.stream()
                    .filter(selectedScopeIds::contains)
                    .forEach(mentionedScopeIds::add);
        }

        if (mentionedScopeIds.isEmpty()) {
            if (hasMentionedOrg) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "您没有该组织的查询权限");
            }
            return selectedScopeIds;
        }
        return mentionedScopeIds.stream().toList();
    }

    private List<OrgTreeNode> safeFullOrgTree() {
        try {
            List<OrgTreeNode> tree = orgService.getFullOrgTree();
            return tree != null ? tree : List.of();
        } catch (Exception e) {
            log.warn("Failed to resolve org mentions from question, falling back to selected org scope", e);
            return List.of();
        }
    }

    private List<OrgTreeNode> flattenOrgTree(List<OrgTreeNode> roots) {
        List<OrgTreeNode> nodes = new ArrayList<>();
        if (roots == null) {
            return nodes;
        }
        for (OrgTreeNode root : roots) {
            flattenOrgTree(root, nodes);
        }
        return nodes;
    }

    private void flattenOrgTree(OrgTreeNode node, List<OrgTreeNode> nodes) {
        if (node == null) {
            return;
        }
        nodes.add(node);
        if (node.getChildren() == null) {
            return;
        }
        for (OrgTreeNode child : node.getChildren()) {
            flattenOrgTree(child, nodes);
        }
    }

    private boolean isOrgMentioned(String question, OrgTreeNode node) {
        if (node == null) {
            return false;
        }
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        return containsOrgToken(normalizedQuestion, node.getName())
                || containsOrgToken(normalizedQuestion, node.getCode());
    }

    private boolean containsOrgToken(String normalizedQuestion, String token) {
        return StringUtils.hasText(token) && normalizedQuestion.contains(token.toLowerCase(Locale.ROOT));
    }
}
