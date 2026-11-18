package com.yuzhuo.filehub.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("没有 X-Request-Id 时生成一个，写响应头，请求结束清掉 MDC")
    void generatesWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertNotNull(id);
        assertFalse(id.isBlank());
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "线程池复用，MDC 必须清干净");
    }

    @Test
    @DisplayName("合规的调用方 ID 原样复用")
    void reusesSafeHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "trace-abc_123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("trace-abc_123", response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    @DisplayName("带换行或超长的 ID 被替换，防止日志注入")
    void rejectsUnsafeHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "bad\nvalue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertNotEquals("bad\nvalue", id);
        assertFalse(id.contains("\n"));
    }
}
