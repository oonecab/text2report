package com.text2report.service;

import com.text2report.domain.meta.entity.DimOrg;
import com.text2report.domain.meta.mapper.DimOrgMapper;
import com.text2report.model.dto.OrgTreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrgService.
 */
@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

    @Mock
    private DimOrgMapper dimOrgMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JdbcTemplate businessJdbcTemplate;

    @Mock
    private UserService userService;

    @InjectMocks
    private OrgService orgService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Nested
    @DisplayName("getFullOrgTree")
    class GetFullOrgTreeTests {

        @Test
        @DisplayName("Should return tree structure with children")
        void shouldReturnTreeStructure() {
            DimOrg root = createOrg(1, "总公司", null, 1, "ORG-HQ", true);
            DimOrg child = createOrg(2, "子公司A", 1, 2, "ORG-A", true);
            when(dimOrgMapper.selectAll()).thenReturn(List.of(root, child));

            List<OrgTreeNode> result = orgService.getFullOrgTree();

            assertEquals(1, result.size());
            assertEquals("总公司", result.get(0).getName());
            assertEquals(1, result.get(0).getChildren().size());
            assertEquals("子公司A", result.get(0).getChildren().get(0).getName());
        }

        @Test
        @DisplayName("Should return empty list when no orgs")
        void shouldReturnEmptyWhenNoOrgs() {
            when(dimOrgMapper.selectAll()).thenReturn(List.of());
            assertTrue(orgService.getFullOrgTree().isEmpty());
        }
    }

    @Nested
    @DisplayName("resolveSubtreeIds")
    class ResolveSubtreeIdsTests {

        @Test
        @DisplayName("Should return subtree IDs from cache")
        void shouldReturnFromCache() {
            when(valueOperations.get("t2r:org:subtree:1")).thenReturn("1,2,3");

            List<Integer> result = orgService.resolveSubtreeIds(1);

            assertEquals(List.of(1, 2, 3), result);
            verify(dimOrgMapper, never()).selectSubtreeIds(any());
        }

        @Test
        @DisplayName("Should query DB when cache miss")
        void shouldQueryDbOnCacheMiss() {
            when(valueOperations.get("t2r:org:subtree:1")).thenReturn(null);
            when(dimOrgMapper.selectSubtreeIds(1)).thenReturn(List.of(1, 2));

            List<Integer> result = orgService.resolveSubtreeIds(1);

            assertEquals(List.of(1, 2), result);
            verify(dimOrgMapper).selectSubtreeIds(1);
            verify(valueOperations).set(eq("t2r:org:subtree:1"), eq("1,2"), anyLong(), any());
        }

        @Test
        @DisplayName("Should throw exception when org not found")
        void shouldThrowWhenOrgNotFound() {
            when(valueOperations.get("t2r:org:subtree:999")).thenReturn(null);
            when(dimOrgMapper.selectSubtreeIds(999)).thenReturn(List.of());

            assertThrows(Exception.class, () -> orgService.resolveSubtreeIds(999));
        }
    }

    @Nested
    @DisplayName("resolveOrgPath")
    class ResolveOrgPathTests {

        @Test
        @DisplayName("Should return path string")
        void shouldReturnPathString() {
            when(dimOrgMapper.selectAncestorPath(2)).thenReturn("总公司 > 子公司A");
            assertEquals("总公司 > 子公司A", orgService.resolveOrgPath(2));
        }

        @Test
        @DisplayName("Should return unknown when org not found")
        void shouldReturnUnknownWhenNotFound() {
            when(dimOrgMapper.selectAncestorPath(999)).thenReturn(null);
            assertEquals("未知组织", orgService.resolveOrgPath(999));
        }

        @Test
        @DisplayName("Should return default when orgId is null")
        void shouldReturnDefaultWhenNull() {
            assertEquals("未指定组织", orgService.resolveOrgPath(null));
        }
    }

    @Nested
    @DisplayName("getOrgById")
    class GetOrgByIdTests {

        @Test
        @DisplayName("Should return org by ID")
        void shouldReturnOrgById() {
            DimOrg org = createOrg(1, "总公司", null, 1, "ORG-HQ", true);
            when(dimOrgMapper.selectById(1)).thenReturn(org);

            DimOrg result = orgService.getOrgById(1);

            assertNotNull(result);
            assertEquals(1, result.getId());
            assertEquals("总公司", result.getOrgName());
        }

        @Test
        @DisplayName("Should return null when not found")
        void shouldReturnNullWhenNotFound() {
            when(dimOrgMapper.selectById(999)).thenReturn(null);
            assertNull(orgService.getOrgById(999));
        }
    }

    private DimOrg createOrg(int id, String name, Integer parentId, int level, String code, boolean active) {
        DimOrg org = new DimOrg();
        org.setId(id);
        org.setOrgName(name);
        org.setParentId(parentId);
        org.setOrgLevel(level);
        org.setOrgCode(code);
        org.setIsActive(active);
        return org;
    }
}
