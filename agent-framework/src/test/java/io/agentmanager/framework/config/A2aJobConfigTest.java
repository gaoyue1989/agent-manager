package io.agentmanager.framework.config;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.a2ajob.A2aJobAuthFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A2A Job 配置与过滤器单测（Issue #69 §2.3/§6）：
 * enabled=true 而 token 空白 → fail-fast；token 比对常数时间 + 无/错不分 401。
 */
class A2aJobConfigTest {

    // ===== fail-fast =====

    @Test
    void enabledWithoutAuthTokenShouldFailFast() {
        var blank = new AgentA2aJobProperties(true, " ", 300, 24, 32);
        var ex = assertThrows(IllegalStateException.class, () -> A2aJobConfig.requireAuthToken(blank));
        assertEquals(true, ex.getMessage().contains("AGENT_A2A_JOB_TOKEN"));
    }

    @Test
    void enabledWithTokenShouldPass() {
        A2aJobConfig.requireAuthToken(new AgentA2aJobProperties(true, "tok", 300, 24, 32));
        // 不抛出即通过
    }

    // ===== 过滤器（复刻 AgentProtocolAuthFilterTest 口径） =====

    @Test
    void filterShouldRejectMissingAndWrongTokenAlike() throws Exception {
        var filter = new A2aJobAuthFilter("secret");

        var missing = roundtrip(filter, null);
        assertEquals(401, missing.status());
        assertEquals(true, missing.body().contains("unauthorized"));

        var wrong = roundtrip(filter, "guess");
        assertEquals(401, wrong.status());
    }

    @Test
    void filterShouldPassMatchingToken() throws Exception {
        var filter = new A2aJobAuthFilter("secret");
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/a2a/jobs");
        request.addHeader(A2aJobAuthFilter.TOKEN_HEADER, "secret");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        org.mockito.Mockito.verify(chain).doFilter(request, response);
    }

    private record FilterResult(int status, String body) {}

    private static FilterResult roundtrip(A2aJobAuthFilter filter, String token) throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/a2a/jobs");
        if (token != null) {
            request.addHeader(A2aJobAuthFilter.TOKEN_HEADER, token);
        }
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var chain = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(request, response, chain);
        return new FilterResult(response.getStatus(), response.getContentAsString());
    }
}
