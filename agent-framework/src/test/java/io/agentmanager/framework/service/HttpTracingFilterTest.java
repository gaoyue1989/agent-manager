package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.agentmanager.framework.TracingTestBase;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import jakarta.servlet.ServletException;

/**
 * HttpTracingFilter 测试：HTTP span 创建、状态、属性（Mock 请求/响应，无需容器）。
 */
class HttpTracingFilterTest extends TracingTestBase {

    @Test
    void shouldCreateSpanWithHttpAttributes() throws Exception {
        var filter = new HttpTracingFilter();
        var request = new MockHttpServletRequest("POST", "/");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        var spans = findSpans("POST /");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertEquals("POST", spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("http.request.method")));
        assertEquals("/", spans.get(0).getAttributes()
                .get(AttributeKey.stringKey("url.path")));
        assertEquals(200, spans.get(0).getAttributes()
                .get(AttributeKey.longKey("http.response.status_code")));
    }

    @Test
    void shouldMarkErrorOn4xxOr5xxResponse() throws Exception {
        var filter = new HttpTracingFilter();
        var request = new MockHttpServletRequest("GET", "/chat/stream");
        var response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(request, response, new MockFilterChain());

        var spans = findSpans("GET /chat/stream");
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    }

    @Test
    void shouldRecordExceptionWhenChainFails() throws Exception {
        var filter = new HttpTracingFilter();
        var request = new MockHttpServletRequest("POST", "/");
        var response = new MockHttpServletResponse();
        // 用抛异常的 FilterChain 验证错误路径
        var failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req,
                                 jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                throw new ServletException("boom");
            }
        };

        try {
            filter.doFilter(request, response, failingChain);
        } catch (ServletException ignored) {
            // 预期传播
        }

        var spans = findSpans("POST /");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
    }
}