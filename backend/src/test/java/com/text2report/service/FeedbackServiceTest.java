package com.text2report.service;

import com.text2report.common.context.TraceContext;
import com.text2report.common.exception.BusinessException;
import com.text2report.domain.meta.entity.AuditQueryLog;
import com.text2report.domain.meta.mapper.AuditQueryLogMapper;
import com.text2report.model.dto.FeedbackRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private AuditQueryLogMapper auditQueryLogMapper;

    private FeedbackService feedbackService;

    @BeforeEach
    void setUp() {
        feedbackService = new FeedbackService(auditQueryLogMapper);
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-1");
        traceInfo.setUserId(7);
        TraceContext.set(traceInfo);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("Should submit feedback successfully")
    void shouldSubmitFeedbackSuccessfully() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(new AuditQueryLog());
        when(auditQueryLogMapper.update(isNull(), any())).thenReturn(1);
        FeedbackRequest request = new FeedbackRequest();
        request.setFeedback("good");
        request.setComment("很好");

        feedbackService.submitFeedback("req-1", request);

        verify(auditQueryLogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("Should return not found when requestId does not exist")
    void shouldReturnNotFoundWhenRequestIdDoesNotExist() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(null);
        FeedbackRequest request = new FeedbackRequest();
        request.setFeedback("good");

        assertThrows(BusinessException.class, () -> feedbackService.submitFeedback("missing", request));
    }

    @Test
    @DisplayName("Should reject rating out of range")
    void shouldRejectRatingOutOfRange() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(new AuditQueryLog());
        FeedbackRequest request = new FeedbackRequest();
        request.setRating(6);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> feedbackService.submitFeedback("req-1", request));
        assertEquals("rating 必须在 1 到 5 之间", exception.getMessage());
    }

    @Test
    @DisplayName("Should reject feedback for another users query")
    void shouldRejectFeedbackForAnotherUsersQuery() {
        when(auditQueryLogMapper.selectOne(any())).thenReturn(null);
        FeedbackRequest request = new FeedbackRequest();
        request.setFeedback("bad");

        assertThrows(BusinessException.class, () -> feedbackService.submitFeedback("req-other", request));
    }
}
