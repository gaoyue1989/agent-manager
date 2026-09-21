package io.agentmanager.framework.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link UserScopedMcpClientWrapper} 单元测试：fail-closed 语义、非法值拒绝、
 * meta 原样下传（_meta 通道不受影响）、transport context 注入。
 */
class UserScopedMcpClientWrapperTest {

    private static final McpSchema.CallToolResult OK = McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent("ok")))
        .isError(false)
        .build();

    /** 记录 delegate 收到的 meta 与调用链上可见的 McpTransportContext */
    private static class RecordingClient extends McpClientWrapper {
        final AtomicReference<Map<String, Object>> seenMeta = new AtomicReference<>();
        final AtomicReference<McpTransportContext> seenTransportContext = new AtomicReference<>();

        RecordingClient() {
            super("test-server");
        }

        @Override
        public Mono<Void> initialize() {
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(List.of());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            return callTool(toolName, arguments, null);
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
            seenMeta.set(meta);
            return Mono.deferContextual(cv -> {
                seenTransportContext.set(cv.getOrDefault(McpTransportContext.KEY, null));
                return Mono.just(OK);
            });
        }

        @Override
        public void close() {
        }
    }

    private static UserScopedMcpClientWrapper wrapper(McpClientWrapper delegate, Map<String, String> headers, boolean deny) {
        return new UserScopedMcpClientWrapper(delegate, new UserHeaderRule(headers, deny));
    }

    @Test
    void shouldInjectHeaderFromMetaAndPassMetaThrough() {
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("X-User-Id", "userId"), true);
        var meta = Map.<String, Object>of("userId", "alice", "traceId", "t-1");

        StepVerifier.create(scoped.callTool("echo", Map.of(), meta))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        // meta 原样下传 → agentscope McpTool 的 _meta 通道不受影响
        assertEquals(meta, delegate.seenMeta.get());
        // header 集进入 transport context，供 customizer 落到 HTTP 请求
        var tc = delegate.seenTransportContext.get();
        assertNotNull(tc);
        assertEquals(Map.of("X-User-Id", "alice"), tc.get(UserScopedMcpClientWrapper.HEADER_KEY));
    }

    @Test
    void shouldFailClosedWhenMetaMissingUnderDeny() {
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("X-User-Id", "userId"), true);

        StepVerifier.create(scoped.callTool("echo", Map.of(), null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(McpUserHeaderException.class, e);
                assertTrue(e.getMessage().contains("userId"), e.getMessage());
                assertTrue(e.getMessage().contains("test-server"), e.getMessage());
            })
            .verify();

        assertNull(delegate.seenMeta.get(), "delegate 不应被调用");
    }

    @Test
    void shouldFailClosedWhenValueBlankUnderDeny() {
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("X-User-Id", "userId"), true);

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("userId", "   ")))
            .expectError(McpUserHeaderException.class)
            .verify();
        assertNull(delegate.seenMeta.get());
    }

    @Test
    void shouldPassThroughUnderPassthroughWhenMissing() {
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate,
            Map.of("X-User-Id", "userId", "Authorization", "user_token"), false);

        // 仅 userId 有值 → 只注入它；user_token 缺失被跳过
        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("userId", "alice")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        var tc = delegate.seenTransportContext.get();
        assertNotNull(tc);
        assertEquals(Map.of("X-User-Id", "alice"), tc.get(UserScopedMcpClientWrapper.HEADER_KEY));
    }

    @Test
    void shouldNotTouchTransportContextWhenAllMissingUnderPassthrough() {
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("X-User-Id", "userId"), false);

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of()))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertNull(delegate.seenTransportContext.get(), "无值时不应写入 transport context");
    }

    @Test
    void shouldAlwaysRejectIllegalHeaderValues() {
        // 换行 / 首尾空白 / 非 ASCII：无论 on-missing 均拒绝，且错误信息不含值
        record Case(String value, String fragment) {
        }
        var cases = List.of(
            new Case("evil\nvalue", "line break"),
            new Case("evil value\r", "line break"),
            new Case(" padded ", "whitespace"),
            new Case("中文值", "non-ASCII"),
            new Case("ctl\u0001char", "control characters"),
            new Case("del\u007fchar", "control characters"));

        for (var c : cases) {
            for (boolean deny : List.of(true, false)) {
                var scoped = wrapper(new RecordingClient(), Map.of("X-User-Id", "userId"), deny);
                var ex = assertThrows(Exception.class,
                    () -> scoped.callTool("echo", Map.of(), Map.of("userId", c.value())).block(),
                    "value=" + c.value() + " deny=" + deny);
                assertTrue(ex.getMessage().contains("userId"), ex.getMessage());
                assertTrue(ex.getMessage().contains(c.fragment()), ex.getMessage());
                assertFalse(ex.getMessage().contains(c.value()), "错误信息不得回显值: " + ex.getMessage());
            }
        }
    }

    @Test
    void shouldListAllMissingKeysInDenyError() {
        var scoped = wrapper(new RecordingClient(),
            Map.of("X-User-Id", "userId", "Authorization", "user_token"), true);

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of()))
            .expectErrorSatisfies(e -> {
                assertTrue(e.getMessage().contains("userId"), e.getMessage());
                assertTrue(e.getMessage().contains("user_token"), e.getMessage());
            })
            .verify();
    }

    @Test
    void shouldDelegateLifecycleMethods() {
        var delegate = mock(McpClientWrapper.class);
        when(delegate.getName()).thenReturn("srv");
        when(delegate.isInitialized()).thenReturn(true);
        when(delegate.listTools()).thenReturn(Mono.just(List.of()));
        when(delegate.initialize()).thenReturn(Mono.empty());

        var scoped = wrapper(delegate, Map.of("X-User-Id", "userId"), true);
        assertEquals("srv", scoped.getName());
        assertTrue(scoped.isInitialized());
        scoped.listTools().block();
        scoped.initialize().block();
        scoped.close();
        verify(delegate).listTools();
        verify(delegate).initialize();
        verify(delegate).close();
    }
}
