package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.AuditQueryLog;
import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.model.dto.FavoriteItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Favorite query service.
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final AuditQueryLogMapper auditQueryLogMapper;

    public void addFavorite(String requestId) {
        updateFavorite(requestId, true);
    }

    public void removeFavorite(String requestId) {
        updateFavorite(requestId, false);
    }

    public List<FavoriteItem> getFavorites() {
        return auditQueryLogMapper.selectFavorites(requireUserId());
    }

    private void updateFavorite(String requestId, boolean favorite) {
        Integer userId = requireUserId();
        ensureOwnedLog(requestId, userId);

        UpdateWrapper<AuditQueryLog> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("request_id", requestId)
                .eq("user_id", userId)
                .set("is_favorite", favorite)
                .set("favorite_at", favorite ? LocalDateTime.now() : null);
        auditQueryLogMapper.update(null, updateWrapper);
    }

    private void ensureOwnedLog(String requestId, Integer userId) {
        QueryWrapper<AuditQueryLog> wrapper = new QueryWrapper<>();
        wrapper.eq("request_id", requestId)
                .eq("user_id", userId);
        if (auditQueryLogMapper.selectOne(wrapper) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "查询记录不存在");
        }
    }

    private Integer requireUserId() {
        Integer userId = TraceContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或Token失效");
        }
        return userId;
    }
}
