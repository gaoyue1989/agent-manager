package io.agentmanager.framework.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChatRequestHeaders} 单元测试：配置驱动捕获、平台 denylist（解析期剔除 + 捕获期
 * 二道防线）、多值合并、限额、toRuntimeContext 随行。
 */
class ChatRequestHeadersTest {

    private static HttpHeaders headersOf(Map<String, List<String>> raw) {
        var headers = new HttpHeaders();
        raw.forEach(headers::put);
        return headers;
    }

    // ===== 捕获 =====

    @Test
    void shouldCaptureOnlyDeclaredNames() {
        var request = headersOf(Map.of(
            "Authorization", List.of("Bearer t-1"),
            "Cookie", List.of("sid=secret"),
            "X-Request-Id", List.of("r-1")));

        var captured = ChatRequestHeaders.capture(request, Set.of("authorization", "x-request-id"));

        assertEquals(Map.of("authorization", "Bearer t-1", "x-request-id", "r-1"), captured.headers());
        assertFalse(captured.headers().containsKey("cookie"), "未声明的头一个字节都不捕获");
    }

    @Test
    void shouldReturnEmptyWhenNoNamesOrNoRequest() {
        var request = headersOf(Map.of("Authorization", List.of("Bearer t-1")));

        assertSame(ChatRequestHeaders.EMPTY, ChatRequestHeaders.capture(request, Set.of()));
        assertSame(ChatRequestHeaders.EMPTY, ChatRequestHeaders.capture(request, null));
        assertSame(ChatRequestHeaders.EMPTY, ChatRequestHeaders.capture(null, Set.of("authorization")));
        assertSame(ChatRequestHeaders.EMPTY, ChatRequestHeaders.capture(
            request, Set.of("x-not-sent")), "请求未带头 → 空集（调用期 deny 兜底）");
    }

    @Test
    void shouldJoinMultiValueWithComma() {
        var request = headersOf(Map.of("X-Trace", List.of("a", "b", "c")));

        var captured = ChatRequestHeaders.capture(request, Set.of("x-trace"));

        assertEquals("a, b, c", captured.headers().get("x-trace"));
    }

    @Test
    void shouldLookupHeaderNameCaseInsensitively() {
        // 客户端发 Authorization，名单声明小写
        var request = headersOf(Map.of("Authorization", List.of("Bearer t-1")));

        var captured = ChatRequestHeaders.capture(request, Set.of("authorization"));

        assertEquals("Bearer t-1", captured.headers().get("authorization"));
    }

    // ===== denylist（捕获期二道防线）=====

    @Test
    void shouldDropDeniedHeadersAtCaptureTime() {
        var request = headersOf(Map.of(
            "Cookie", List.of("sid=secret"),
            "X-Forwarded-For", List.of("10.0.0.1"),
            "Sec-Fetch-Mode", List.of("cors"),
            "X-Forwarded-Prefix", List.of("/agent/x"),
            "Authorization", List.of("Bearer t-1")));

        var captured = ChatRequestHeaders.capture(request, Set.of(
            "cookie", "x-forwarded-for", "sec-fetch-mode", "x-forwarded-prefix", "authorization"));

        assertEquals(Map.of("authorization", "Bearer t-1"), captured.headers());
    }

    @Test
    void isDeniedShouldCoverPlatformAndHopByHopHeaders() {
        assertTrue(ChatRequestHeaders.isDenied("cookie"));
        assertTrue(ChatRequestHeaders.isDenied("set-cookie"));
        assertTrue(ChatRequestHeaders.isDenied("connection"));
        assertTrue(ChatRequestHeaders.isDenied("transfer-encoding"));
        assertTrue(ChatRequestHeaders.isDenied("host"));
        assertTrue(ChatRequestHeaders.isDenied("x-forwarded-for"));
        assertTrue(ChatRequestHeaders.isDenied("sec-ch-ua"));
        assertTrue(ChatRequestHeaders.isDenied("x-forwarded-prefix"));
        // 前缀族（CR #1）：代理凭据与报文描述类枚举漏网防护
        assertTrue(ChatRequestHeaders.isDenied("proxy-authorization"));
        assertTrue(ChatRequestHeaders.isDenied("proxy-connection"));
        assertTrue(ChatRequestHeaders.isDenied("content-disposition"));
        assertTrue(ChatRequestHeaders.isDenied("content-language"));
        assertTrue(ChatRequestHeaders.isDenied("accept-patch"));
        assertTrue(ChatRequestHeaders.isDenied("accept-encoding"));
        assertTrue(ChatRequestHeaders.isDenied(null));
        assertTrue(ChatRequestHeaders.isDenied(""));
        // Authorization 恰是主用例，不得 deny
        assertFalse(ChatRequestHeaders.isDenied("authorization"));
        assertFalse(ChatRequestHeaders.isDenied("x-request-id"));
        assertFalse(ChatRequestHeaders.isDenied("x-tenant-id"));
    }

    // ===== 限额 =====

    @Test
    void shouldDropOversizedValueAndKeepOthers() {
        var big = "x".repeat(ChatRequestHeaders.MAX_VALUE_LENGTH + 1);
        var request = headersOf(Map.of(
            "X-Big", List.of(big),
            "X-Ok", List.of("ok")));

        var captured = ChatRequestHeaders.capture(request, Set.of("x-big", "x-ok"));

        assertEquals(Map.of("x-ok", "ok"), captured.headers());
    }

    @Test
    void shouldStopAtTotalCap() {
        // 每个值 2KB，8 个 = 16KB；再放第 9 个超 32KB 总量 → 后续被截停
        var value = "y".repeat(2 * 1024);
        var request = new HttpHeaders();
        for (int i = 0; i < 20; i++) {
            request.put("x-h" + i, List.of(value));
        }
        var names = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < 20; i++) {
            names.add("x-h" + i);
        }

        var captured = ChatRequestHeaders.capture(request, names);

        // 16 × 2KB = 32KB 触顶截停（单值/头数上限均未先到）
        assertEquals(16, captured.headers().size(), "32KB 触顶截停: " + captured.headers().size());
        assertTrue(captured.headers().containsKey("x-h15"));
        assertFalse(captured.headers().containsKey("x-h16"));
    }

    // ===== 随行 =====

    @Test
    void toRuntimeContextShouldCarryTypedAttributeOnlyWhenNotEmpty() {
        var empty = ChatRequestHeaders.EMPTY.toRuntimeContext();
        assertNull(empty.get(ChatRequestHeaders.class));

        var carried = new ChatRequestHeaders(Map.of("authorization", "Bearer t-1")).toRuntimeContext();
        assertNotNull(carried.get(ChatRequestHeaders.class));
        assertEquals(Map.of("authorization", "Bearer t-1"),
            carried.get(ChatRequestHeaders.class).headers());
    }

    @Test
    void shouldStopAtHeaderCountCap() {
        // 33 个声明且全部携带：超过 MAX_HEADERS 截停（CR #8 补分支用例）
        var request = new HttpHeaders();
        var names = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < ChatRequestHeaders.MAX_HEADERS + 1; i++) {
            var name = "x-n" + i;
            request.put(name, List.of("v" + i));
            names.add(name);
        }

        var captured = ChatRequestHeaders.capture(request, names);

        assertEquals(ChatRequestHeaders.MAX_HEADERS, captured.headers().size());
        assertFalse(captured.headers().containsKey("x-n" + ChatRequestHeaders.MAX_HEADERS));
    }
}
