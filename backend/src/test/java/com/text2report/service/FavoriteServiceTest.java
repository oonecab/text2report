package com.text2report.service;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.domain.meta.entity.AuditQueryLog;
import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.model.dto.FavoriteItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    @Mock
    private AuditQueryLogMapper auditQueryLogMapper;

    private FavoriteService favoriteService;

    @BeforeEach
    void setUp() {
        favoriteService = new FavoriteService(auditQueryLogMapper);
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-1");
        traceInfo.setUserId(7);
        TraceContext.set(traceInfo);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("Should add favorite")
    void shouldAddFavorite() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(new AuditQueryLog());
        when(auditQueryLogMapper.update(isNull(), any())).thenReturn(1);

        favoriteService.addFavorite("req-1");

        verify(auditQueryLogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("Should remove favorite")
    void shouldRemoveFavorite() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(new AuditQueryLog());
        when(auditQueryLogMapper.update(isNull(), any())).thenReturn(1);

        favoriteService.removeFavorite("req-1");

        verify(auditQueryLogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("Should query favorites by current user")
    void shouldQueryFavoritesByCurrentUser() {
        when(auditQueryLogMapper.selectFavorites(7)).thenReturn(List.of());

        favoriteService.getFavorites();

        verify(auditQueryLogMapper).selectFavorites(7);
    }

    @Test
    @DisplayName("Should get favorites list")
    void shouldGetFavoritesList() {
        when(auditQueryLogMapper.selectFavorites(7)).thenReturn(List.of(
                FavoriteItem.builder()
                        .requestId("req-1")
                        .question("子公司A毛利")
                        .orgId(2)
                        .orgName("子公司A")
                        .recommendedChart("bar")
                        .rowCount(1)
                        .favoritedAt(LocalDateTime.now())
                        .lastRunAt(LocalDateTime.now())
                        .build()
        ));

        List<FavoriteItem> favorites = favoriteService.getFavorites();

        assertEquals(1, favorites.size());
    }

    @Test
    @DisplayName("Should reject favoriting another users query")
    void shouldRejectFavoritingAnotherUsersQuery() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> favoriteService.addFavorite("req-other"));
    }

    @Test
    @DisplayName("Should require login for favorites access")
    void shouldRequireLoginForFavoritesAccess() {
        TraceContext.clear();

        BusinessException exception = assertThrows(BusinessException.class, () -> favoriteService.getFavorites());

        assertEquals("未登录或Token失效", exception.getMessage());
    }
}
