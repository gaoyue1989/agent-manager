package io.agentmanager.framework.util;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ExternalUrlGuard 白名单裁决矩阵单测（issue #483）：穿越/编码穿越/重定向 escape 的
 * 裁决语义由 FileToolsTest 与 FileControllerTest 在入口层锁定，本类锁定裁决函数本身。
 */
class ExternalUrlGuardTest {

    @Test
    void shouldAllowExactPrefixAndSubpaths() {
        var p = List.of("http://backend:8080/api/v1/packages");
        assertEquals("http://backend:8080/api/v1/packages/3/download",
            ExternalUrlGuard.allowlisted("http://backend:8080/api/v1/packages/3/download", p).toString());
        assertEquals("http://backend:8080/api/v1/packages",
            ExternalUrlGuard.allowlisted("http://backend:8080/api/v1/packages", p).toString());
        assertEquals("http://backend:8080/api/v1/packages/4/download",
            ExternalUrlGuard.allowlisted("http://backend:8080/api/v1/packages/3/../4/download", p).toString(),
            "界内 .. 段规范化后放行并返回规范化 URI");
    }

    @Test
    void shouldRejectEscapeBeyondPrefix() {
        var p = List.of("http://backend:8080/api/v1/packages");
        for (var url : new String[]{
                "http://backend:8080/api/v1/packages/../services",
                "http://backend:8080/api/v1/services",
                "http://backend:8080/api/v1/packages/../..",
                "http://backend:8080/",
                "http://backend:8080/apiv1packages/x"}) {
            assertNull(ExternalUrlGuard.allowlisted(url, p), "越出前缀应拒绝: " + url);
        }
    }

    @Test
    void shouldRejectEncodedTraversalAndBackslash() {
        var p = List.of("http://backend:8080/pkg");
        for (var url : new String[]{
                "http://backend:8080/pkg/%2e%2e/secret",
                "http://backend:8080/pkg/%2E%2E/secret",
                "http://backend:8080/pkg/a%2fb",
                "http://backend:8080/pkg/..%2fsecret",
                "http://backend:8080/pkg/%5c..%5csecret",
                "http://backend:8080/pkg/a\\b"}) {
            assertNull(ExternalUrlGuard.allowlisted(url, p), "编码穿越/反斜杠应拒绝: " + url);
        }
    }

    @Test
    void shouldRejectSchemeAuthorityMismatch() {
        var p = List.of("http://backend:8080/pkg");
        for (var url : new String[]{
                "https://backend:8080/pkg/x",
                "http://backend:8081/pkg/x",
                "http://evil.example/pkg/x",
                "http://backend.evil.example/pkg/x",
                "http://user@backend:8080/pkg/x",
                "file:///etc/passwd",
                "ftp://backend:8080/pkg/x"}) {
            assertNull(ExternalUrlGuard.allowlisted(url, p), "scheme/authority 不匹配应拒绝: " + url);
        }
    }

    @Test
    void shouldRejectMalformedUrlAndEmptyPrefixes() {
        assertNull(ExternalUrlGuard.allowlisted("http://backend:8080/pkg x", List.of("http://backend:8080/pkg")),
            "非法 URI 字符应拒绝");
        assertNull(ExternalUrlGuard.allowlisted("http://backend:8080/pkg/x", List.of()));
        assertNull(ExternalUrlGuard.allowlisted(null, List.of("http://backend:8080")));
    }

    @Test
    void shouldNormalizeDotSegmentsOnReturn() {
        var p = List.of("http://backend:8080/pkg");
        assertEquals("http://backend:8080/pkg/x.zip",
            ExternalUrlGuard.allowlisted("http://backend:8080/pkg/./x.zip", p).toString());
    }

    @Test
    void shouldSupportRootPrefixAndMultipleEntries() {
        var root = ExternalUrlGuard.allowlisted("http://backend:8080/any/where.bin", List.of("http://backend:8080"));
        assertNotNull(root, "无路径前缀=仅限 scheme/authority");
        var multi = List.of("http://a.example/x", "http://b.example/y");
        assertNotNull(ExternalUrlGuard.allowlisted("http://b.example/y/z", multi));
        assertNull(ExternalUrlGuard.allowlisted("http://a.example/y/z", multi), "只匹配各自路径前缀");
    }

    @Test
    void shouldNormalizeHostCaseDefaultPortsAndIpv6PerRfc3986() {
        var httpPrefix = List.of("http://files.internal/api/v1/packages");
        assertNotNull(ExternalUrlGuard.allowlisted("HTTP://FILES.INTERNAL/api/v1/packages/a.zip", httpPrefix));
        assertNotNull(ExternalUrlGuard.allowlisted("http://files.internal:80/api/v1/packages/a.zip", httpPrefix));
        assertNull(ExternalUrlGuard.allowlisted("http://files.internal:8080/api/v1/packages/a.zip", httpPrefix));

        var httpsPrefix = List.of("https://cdn.example.com/files");
        assertNotNull(ExternalUrlGuard.allowlisted("HTTPS://CDN.EXAMPLE.COM:443/files/out.pdf", httpsPrefix));
        assertNull(ExternalUrlGuard.allowlisted("https://cdn.example.com:80/files/out.pdf", httpsPrefix));

        var ipv6Prefix = List.of("http://[2001:db8::1]:8080/pkg");
        assertNotNull(ExternalUrlGuard.allowlisted("http://[2001:DB8::1]:8080/pkg/a.zip", ipv6Prefix));
        assertNotNull(ExternalUrlGuard.allowlisted("http://[2001:0db8:0:0::1]:8080/pkg/a.zip", ipv6Prefix));

        assertNull(ExternalUrlGuard.allowlisted("http://user:pass@files.internal/api/v1/packages/a.zip", httpPrefix));
    }
}
