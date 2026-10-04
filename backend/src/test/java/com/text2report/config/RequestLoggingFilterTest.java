package com.text2report.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.text2report.common.context.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter requestLoggingFilter = new RequestLoggingFilter();
    private Logger logger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        TraceContext.clear();
        logger.detachAppender(listAppender);
    }

    @Test
    @DisplayName("Should log request start and end")
    void shouldLogRequestStartAndEnd() throws Exception {
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-123");
        traceInfo.setUserId(7);
        TraceContext.set(traceInfo);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/query/history");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        requestLoggingFilter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));

        List<String> messages = listAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).contains("[req-123] -> GET /api/v1/query/history"));
        assertTrue(messages.get(1).contains("[req-123] <- 204"));
    }

    @Test
    @DisplayName("Should not log health endpoint")
    void shouldNotLogHealthEndpoint() throws Exception {
        TraceContext.set(new TraceContext.TraceInfo("req-health"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        requestLoggingFilter.doFilter(request, response, noopChain());

        assertTrue(listAppender.list.isEmpty());
    }

    @Test
    @DisplayName("Should include request id in logs")
    void shouldIncludeRequestIdInLogs() throws Exception {
        TraceContext.TraceInfo traceInfo = new TraceContext.TraceInfo("req-xyz");
        traceInfo.setUserId(9);
        TraceContext.set(traceInfo);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        MockHttpServletResponse response = new MockHttpServletResponse();

        requestLoggingFilter.doFilter(request, response, noopChain());

        assertTrue(listAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> message.contains("req-xyz")));
    }

    private FilterChain noopChain() {
        return (request, response) -> { };
    }
}
