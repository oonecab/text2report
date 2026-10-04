package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.text2report.common.result.PageResult;
import com.text2report.domain.meta.entity.MetaLlmProvider;
import com.text2report.domain.meta.mapper.MetaLlmConfigAuditLogMapper;
import com.text2report.domain.meta.mapper.MetaLlmProviderMapper;
import com.text2report.domain.meta.mapper.MetaLlmProviderSecretMapper;
import com.text2report.domain.meta.mapper.MetaLlmRoutingPolicyMapper;
import com.text2report.model.dto.LlmProviderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmProviderConfigServiceTest {

    @Mock
    private MetaLlmProviderMapper providerMapper;

    @Mock
    private MetaLlmProviderSecretMapper secretMapper;

    @Mock
    private MetaLlmRoutingPolicyMapper routingPolicyMapper;

    @Mock
    private MetaLlmConfigAuditLogMapper auditLogMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private LlmProviderConfigService service;

    @Test
    @DisplayName("Should list providers with backend pagination and search filters")
    void shouldListProvidersWithBackendPaginationAndSearchFilters() {
        MetaLlmProvider provider = new MetaLlmProvider();
        provider.setId(1);
        provider.setProviderKey("deepseek-default");
        provider.setDisplayName("DeepSeek 默认");
        provider.setProviderMode("ONLINE");
        provider.setVendorType("OPENAI_COMPATIBLE");
        provider.setDeploymentType("SAAS");
        provider.setModelCode("deepseek-chat");
        provider.setEnabled(true);
        provider.setIsDefault(true);
        provider.setPriority(0);
        provider.setConfigVersion(1);

        when(providerMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(providerMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(provider));
        when(secretMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        PageResult<LlmProviderVO> result = service.listProviders(2, 10, "ONLINE", true, "deepseek");

        assertEquals(1, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getPageSize());
        assertEquals("deepseek-default", result.getList().get(0).getProviderKey());

        ArgumentCaptor<QueryWrapper<MetaLlmProvider>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(providerMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getCustomSqlSegment();
        assertTrue(sqlSegment.contains("provider_mode"));
        assertTrue(sqlSegment.contains("enabled"));
        assertTrue(sqlSegment.contains("provider_key"));
        assertTrue(sqlSegment.contains("display_name"));
        assertTrue(sqlSegment.contains("model_code"));
        assertTrue(sqlSegment.contains("LIMIT 10 OFFSET 10"));
    }
}
