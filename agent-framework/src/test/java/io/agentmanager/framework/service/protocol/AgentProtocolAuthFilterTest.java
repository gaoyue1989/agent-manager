package io.agentmanager.framework.service.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

/**
 * /tasks* 服务间认证过滤器测试：无/错 token 一律 401 且不进入业务链（设计 §6.2），
 * 正确 token 放行；空白配置 token 时不放行任何请求（兜底防御）。
 */
class AgentProtocolAuthFilterTest {

    private static final String TOKEN = "secret-token-1";

    private MockHttpServletResponse filter(MockHttpServletRequest request, String expectedToken)
            throws ServletException, IOException {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new AgentProtocolAuthFilter(expectedToken).doFilter(request, response, chain);
        // chain 是否被放行经 request attribute 回传断言
        if (chain.getRequest() != null) {
            response.addHeader("X-Test-Passed-Through", "true");
        }
        return response;
    }

    private MockHttpServletRequest requestWithToken(String token) {
        var request = new MockHttpServletRequest("POST", "/tasks");
        if (token != null) {
            request.addHeader(AgentProtocolAuthFilter.TOKEN_HEADER, token);
        }
        return request;
    }

    /** 无 token（缺 Header）→ 401，不进入业务链 */
    @Test
    void missingTokenShouldReturn401() throws Exception {
        var response = filter(requestWithToken(null), TOKEN);
        assertEquals(401, response.getStatus());
        assertNull(response.getHeader("X-Test-Passed-Through"), "401 时不得放行进入业务链");
    }

    /** 错 token → 401（与缺失同响应，不做区分，防探测） */
    @Test
    void wrongTokenShouldReturn401() throws Exception {
        var response = filter(requestWithToken("wrong"), TOKEN);
        assertEquals(401, response.getStatus());
        assertEquals("{\"error\":\"unauthorized\"}", response.getContentAsString());
        assertNull(response.getHeader("X-Test-Passed-Through"));
    }

    /** 正确 token → 放行进入业务链 */
    @Test
    void correctTokenShouldPassThrough() throws Exception {
        var response = filter(requestWithToken(TOKEN), TOKEN);
        assertEquals(200, response.getStatus());
        assertEquals("true", response.getHeader("X-Test-Passed-Through"));
    }

    /** 空白 Header 值视同缺失 → 401 */
    @Test
    void blankTokenHeaderShouldReturn401() throws Exception {
        var response = filter(requestWithToken(" "), TOKEN);
        assertEquals(401, response.getStatus());
        assertNull(response.getHeader("X-Test-Passed-Through"));
    }

    /** 期望 token 空白（fail-fast 之外的兜底）→ 一律 401，永不放行 */
    @Test
    void blankExpectedTokenShouldNeverAuthorize() throws Exception {
        var response = filter(requestWithToken(""), "");
        assertEquals(401, response.getStatus());
        assertNull(response.getHeader("X-Test-Passed-Through"));
    }

    /** /tasks 子路径语义由 URL 模式注册保证；过滤器本体对任意路径做同一校验 */
    @Test
    void subresourcePathShouldRequireToken() throws Exception {
        var request = new MockHttpServletRequest("POST", "/tasks/t-123/resume");
        var response = filter(request, TOKEN);
        assertEquals(401, response.getStatus());

        request.addHeader(AgentProtocolAuthFilter.TOKEN_HEADER, TOKEN);
        response = filter(request, TOKEN);
        assertEquals(200, response.getStatus());
    }

    /** 常数时间比较路径：超长错误 token 同样 401（长度差不抛异常） */
    @Test
    void lengthMismatchedTokenShouldReturn401() throws UnsupportedEncodingException {
        assertDoesNotThrow(() -> {
            var response = filter(requestWithToken("a".repeat(4096)), TOKEN);
            assertEquals(401, response.getStatus());
        });
    }
}
