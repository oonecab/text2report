package com.text2report.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.AuditQueryLog;
import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.model.dto.FeedbackRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Feedback service for query history.
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final AuditQueryLogMapper auditQueryLogMapper;

    public void submitFeedback(String requestId, FeedbackRequest request) {
        Integer userId = requireUserId();
        findOwnedLog(requestId, userId);
        validateRating(request.getRating());

        String normalizedFeedback = normalizeFeedback(request);
        Integer rating = request.getRating() != null ? request.getRating()
                : ("good".equals(normalizedFeedback) ? 5 : ("bad".equals(normalizedFeedback) ? 1 : null));

        UpdateWrapper<AuditQueryLog> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("request_id", requestId)
                .eq("user_id", userId)
                .set("feedback", normalizedFeedback)
                .set("feedback_rating", rating)
                .set("feedback_comment", request.getComment())
                .set("feedback_at", LocalDateTime.now());
        auditQueryLogMapper.update(null, updateWrapper);
    }

    private String normalizeFeedback(FeedbackRequest request) {
        if (StringUtils.hasText(request.getFeedback())) {
            String feedback = request.getFeedback().trim().toLowerCase();
            if ("good".equals(feedback) || "bad".equals(feedback)) {
                return feedback;
            }
            throw new BusinessException(ErrorCode.PARAM_INVALID, "feedback 仅支持 good 或 bad");
        }
        if (request.getRating() != null) {
            return request.getRating() >= 4 ? "good" : "bad";
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "feedback 或 rating 至少提供一个");
    }

    private void validateRating(Integer rating) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "rating 必须在 1 到 5 之间");
        }
    }

    private AuditQueryLog findOwnedLog(String requestId, Integer userId) {
        QueryWrapper<AuditQueryLog> wrapper = new QueryWrapper<>();
        wrapper.eq("request_id", requestId)
                .eq("user_id", userId);
        AuditQueryLog log = auditQueryLogMapper.selectOne(wrapper);
        if (log == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "查询记录不存在");
        }
        return log;
    }

    private Integer requireUserId() {
        Integer userId = TraceContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或Token失效");
        }
        return userId;
    }
}
