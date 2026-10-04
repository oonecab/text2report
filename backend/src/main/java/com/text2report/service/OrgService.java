package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.DimOrg;
import com.text2report.domain.meta.mapper.DimOrgMapper;
import com.text2report.model.dto.OrgSyncResult;
import com.text2report.model.dto.OrgTreeNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Organization service backed by metadata dim_org.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgService {

    private static final String CACHE_KEY_PREFIX = "t2r:org:";
    private static final long CACHE_TTL_SECONDS = 3600;

    private final DimOrgMapper dimOrgMapper;
    private final StringRedisTemplate stringRedisTemplate;
    @Qualifier("businessJdbcTemplate")
    private final JdbcTemplate businessJdbcTemplate;
    private final UserService userService;

    public List<OrgTreeNode> getFullOrgTree() {
        List<DimOrg> allNodes = dimOrgMapper.selectAll();
        return buildTree(allNodes, null);
    }

    public List<OrgTreeNode> getOrgTreeForUser(Integer userId) {
        List<DimOrg> allNodes = dimOrgMapper.selectAll();
        Set<Integer> accessibleIds = userService.resolveAccessibleOrgIds(userId).stream().collect(Collectors.toSet());
        return buildTree(allNodes, accessibleIds);
    }

    public List<Integer> resolveSubtreeIds(Integer orgId) {
        if (orgId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "Org ID cannot be null");
        }
        String cacheKey = CACHE_KEY_PREFIX + "subtree:" + orgId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return Arrays.stream(cached.split(","))
                        .filter(value -> !value.isBlank())
                        .map(Integer::parseInt)
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Failed to read org subtree cache", e);
        }
        List<Integer> ids = dimOrgMapper.selectSubtreeIds(orgId);
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Organization not found");
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                    CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("Failed to write org subtree cache", e);
        }
        return ids;
    }

    public String resolveOrgPath(Integer orgId) {
        if (orgId == null) {
            return "未指定组织";
        }
        String path = dimOrgMapper.selectAncestorPath(orgId);
        return path != null ? path : "未知组织";
    }

    public DimOrg getOrgById(Integer orgId) {
        return orgId == null ? null : dimOrgMapper.selectById(orgId);
    }

    @Transactional(transactionManager = "metaTransactionManager")
    public OrgSyncResult syncFromBusiness() {
        List<DimOrg> businessOrgs = businessJdbcTemplate.query(
                "SELECT id, org_name, parent_id, org_level, org_code, is_active FROM dim_org",
                this::mapDimOrg
        );
        List<DimOrg> metaOrgs = dimOrgMapper.selectAll();
        Map<Integer, DimOrg> metaMap = metaOrgs.stream()
                .collect(Collectors.toMap(DimOrg::getId, value -> value, (left, right) -> left, LinkedHashMap::new));

        int inserted = 0;
        int updated = 0;
        int deactivated = 0;
        LocalDateTime now = LocalDateTime.now();
        Set<Integer> businessIds = businessOrgs.stream().map(DimOrg::getId).collect(Collectors.toSet());

        for (DimOrg org : businessOrgs) {
            org.setSyncedAt(now);
            DimOrg existing = metaMap.get(org.getId());
            if (existing == null) {
                dimOrgMapper.insertWithId(org);
                inserted++;
            } else if (needsUpdate(existing, org)) {
                dimOrgMapper.updateById(org);
                updated++;
            }
        }

        for (DimOrg meta : metaOrgs) {
            if (!businessIds.contains(meta.getId()) && Boolean.TRUE.equals(meta.getIsActive())) {
                dimOrgMapper.deactivate(meta.getId());
                deactivated++;
            }
        }

        clearOrgCaches();
        return new OrgSyncResult(inserted, updated, deactivated, businessOrgs.size());
    }

    public List<com.text2report.model.dto.OrgUserVO> getUsersByOrgId(Integer orgId) {
        return userService.getUsersByOrgId(orgId);
    }

    public LocalDateTime getLastSyncTime() {
        return dimOrgMapper.selectAll().stream()
                .map(DimOrg::getSyncedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private List<OrgTreeNode> buildTree(List<DimOrg> allNodes, Set<Integer> filterIds) {
        Map<Integer, OrgTreeNode> nodeMap = new HashMap<>();
        for (DimOrg org : allNodes) {
            if (filterIds != null && !filterIds.contains(org.getId())) {
                continue;
            }
            nodeMap.put(org.getId(), OrgTreeNode.builder()
                    .id(org.getId())
                    .name(org.getOrgName())
                    .code(org.getOrgCode())
                    .level(org.getOrgLevel())
                    .parentId(org.getParentId())
                    .isActive(org.getIsActive())
                    .children(new ArrayList<>())
                    .build());
        }
        List<OrgTreeNode> roots = new ArrayList<>();
        for (OrgTreeNode node : nodeMap.values()) {
            if (node.getParentId() == null || !nodeMap.containsKey(node.getParentId())) {
                roots.add(node);
            } else {
                nodeMap.get(node.getParentId()).getChildren().add(node);
            }
        }
        roots.sort(java.util.Comparator.comparing(OrgTreeNode::getId));
        return roots;
    }

    private DimOrg mapDimOrg(ResultSet rs, int rowNum) throws SQLException {
        DimOrg org = new DimOrg();
        org.setId(rs.getInt("id"));
        org.setOrgName(rs.getString("org_name"));
        Object parentId = rs.getObject("parent_id");
        org.setParentId(parentId == null ? null : rs.getInt("parent_id"));
        org.setOrgLevel(rs.getInt("org_level"));
        org.setOrgCode(rs.getString("org_code"));
        org.setIsActive(rs.getBoolean("is_active"));
        return org;
    }

    private boolean needsUpdate(DimOrg existing, DimOrg candidate) {
        return !java.util.Objects.equals(existing.getOrgName(), candidate.getOrgName())
                || !java.util.Objects.equals(existing.getParentId(), candidate.getParentId())
                || !java.util.Objects.equals(existing.getOrgLevel(), candidate.getOrgLevel())
                || !java.util.Objects.equals(existing.getOrgCode(), candidate.getOrgCode())
                || !java.util.Objects.equals(existing.getIsActive(), candidate.getIsActive());
    }

    private void clearOrgCaches() {
        try {
            Set<String> keys = stringRedisTemplate.keys(CACHE_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Failed to clear org caches", e);
        }
    }
}
