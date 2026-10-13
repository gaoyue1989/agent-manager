package io.agentmanager.framework.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExternalUrlSupportTest {

    private MockHttpServletRequest req() {
        var req = new MockHttpServletRequest();
        req.setScheme("http");
        req.setServerName("10.0.0.1");
        req.setServerPort(8100);
        return req;
    }

    // ===== normalizePrefix =====

    @Test
    void normalizePrefixShouldReturnNullForMissingOrRoot() {
        assertNull(ExternalUrlSupport.normalizePrefix(null));
        assertNull(ExternalUrlSupport.normalizePrefix(""));
        assertNull(ExternalUrlSupport.normalizePrefix("   "));
        assertNull(ExternalUrlSupport.normalizePrefix("/"));
        assertNull(ExternalUrlSupport.normalizePrefix("//"));
    }

    @Test
    void normalizePrefixShouldTrimTrailingSlashes() {
        assertEquals("/agent/foo", ExternalUrlSupport.normalizePrefix("/agent/foo/"));
        assertEquals("/agent/foo", ExternalUrlSupport.normalizePrefix("/agent/foo//"));
        assertEquals("/agent/foo", ExternalUrlSupport.normalizePrefix(" /agent/foo/ "));
    }

    @Test
    void normalizePrefixShouldTakeFirstCommaToken() {
        assertEquals("/agent/foo", ExternalUrlSupport.normalizePrefix("/agent/foo, /other"));
        assertEquals("/agent/foo", ExternalUrlSupport.normalizePrefix(" /agent/foo ,/x "));
    }

    // ===== forwardedPrefix =====

    @Test
    void forwardedPrefixShouldReadHeader() {
        var req = req();
        req.addHeader("X-Forwarded-Prefix", "/agent/demo");
        assertEquals("/agent/demo", ExternalUrlSupport.forwardedPrefix(req));
    }

    @Test
    void forwardedPrefixShouldReturnNullWithoutHeader() {
        assertNull(ExternalUrlSupport.forwardedPrefix(req()));
    }

    // ===== externalBase =====

    @Test
    void externalBaseShouldCombineForwardedHeaders() {
        var req = req();
        req.addHeader("X-Forwarded-Prefix", "/agent/demo/");
        req.addHeader("X-Forwarded-Host", "entry.example:30080");
        req.addHeader("X-Forwarded-Proto", "https");
        assertEquals("https://entry.example:30080/agent/demo", ExternalUrlSupport.externalBase(req));
    }

    @Test
    void externalBaseShouldFallBackToHostHeaderAndScheme() {
        var req = req();
        req.addHeader("Host", "172.20.0.3:30080");
        req.addHeader("X-Forwarded-Prefix", "/agent/demo");
        assertEquals("http://172.20.0.3:30080/agent/demo", ExternalUrlSupport.externalBase(req));
    }

    @Test
    void externalBaseShouldFallBackToServerNameWhenHostMissing() {
        var req = req();
        req.addHeader("X-Forwarded-Prefix", "/agent/demo");
        // MockHttpServletRequest 不强制 Host 头，serverName/port 兜底路径可构造
        assertEquals("http://10.0.0.1:8100/agent/demo", ExternalUrlSupport.externalBase(req));
    }

    @Test
    void externalBaseShouldReturnNullWithoutPrefix() {
        var req = req();
        req.addHeader("X-Forwarded-Host", "entry.example");
        assertNull(ExternalUrlSupport.externalBase(req));
    }

    @Test
    void externalBaseShouldTakeFirstProtoToken() {
        var req = req();
        req.addHeader("X-Forwarded-Prefix", "/agent/demo");
        req.addHeader("X-Forwarded-Proto", "https,http");
        req.addHeader("Host", "entry.example");
        assertEquals("https://entry.example/agent/demo", ExternalUrlSupport.externalBase(req));
    }

    // ===== safeLocationPrefix（issue #116：302 Location 前缀信任边界）=====

    @Test
    void safeLocationPrefixShouldAcceptWellFormedPrefix() {
        var req = req();
        req.addHeader("X-Forwarded-Prefix", "/agent/demo");
        assertEquals("/agent/demo", ExternalUrlSupport.safeLocationPrefix(req));
    }

    @Test
    void safeLocationPrefixShouldNormalizeTrailingSlashes() {
        var req = req();
        req.addHeader("X-Forwarded-Prefix", " /agent/demo/ ");
        assertEquals("/agent/demo", ExternalUrlSupport.safeLocationPrefix(req));
    }

    @Test
    void safeLocationPrefixShouldRejectProtocolRelativeAndAbsoluteUrl() {
        // //evil.com 浏览器按协议相对解析 → 跨域开放重定向；http:// 绝对地址同理
        for (var evil : new String[] {"//evil.com", "http://evil.com", "https://evil.com/x"}) {
            var req = req();
            req.addHeader("X-Forwarded-Prefix", evil);
            assertNull(ExternalUrlSupport.safeLocationPrefix(req), "应拒绝: " + evil);
        }
    }

    @Test
    void safeLocationPrefixShouldReturnNullWithoutHeader() {
        assertNull(ExternalUrlSupport.safeLocationPrefix(req()));
    }
}
