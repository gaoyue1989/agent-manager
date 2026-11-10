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

    /** 记录 delegate 收到的入参/meta 与调用链上可见的 McpTransportContext */
    private static class RecordingClient extends McpClientWrapper {
        final AtomicReference<Map<String, Object>> seenMeta = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> seenArguments = new AtomicReference<>();
        final AtomicReference<McpTransportContext> seenTransportContext = new AtomicReference<>();

        RecordingClient() {
            super("test-server");
        }

        /** 预置工具 schema（schema 门控测试用；放入基类 cachedTools，getCachedTool 可查） */
        void cacheTool(McpSchema.Tool tool) {
            cachedTools.put(tool.name(), tool);
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
            seenArguments.set(arguments);
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
        return new UserScopedMcpClientWrapper(delegate, new UserHeaderRule(headers, Map.of(), deny));
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
    void shouldStripReqHeaderKeysFromOutboundMetaWhenInjected() {
        // 透传值只走 HTTP header 单通道：注入的同时必须从转交 delegate 的 meta 里剥掉
        // reqHeader:*（否则随 _meta 下发到下游——McpMeta 是 per-turn 全 server 共享的）
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("X-Auth", "reqHeader:x-auth"), true);
        var meta = Map.<String, Object>of(
            "userId", "alice", "reqHeader:x-auth", "Bearer token-1", "traceId", "t-1");

        StepVerifier.create(scoped.callTool("echo", Map.of(), meta))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("userId", "alice", "traceId", "t-1"), delegate.seenMeta.get(),
            "reqHeader:* 不得进入 _meta 通道");
        var tc = delegate.seenTransportContext.get();
        assertNotNull(tc);
        assertEquals(Map.of("X-Auth", "Bearer token-1"), tc.get(UserScopedMcpClientWrapper.HEADER_KEY));
    }

    @Test
    void shouldStripReqHeaderKeysEvenWhenNotDeclared() {
        // A server 未声明该透传头也要剥离——防 B server 声明的值漏进 A 的 _meta
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("X-User-Id", "userId"), true);

        StepVerifier.create(scoped.callTool("echo", Map.of(),
                Map.of("userId", "alice", "reqHeader:x-auth", "Bearer token-1")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("userId", "alice"), delegate.seenMeta.get());
        // 本 server 自己声明的 X-User-Id 正常注入；x-auth 未声明故不注入
        var tc = delegate.seenTransportContext.get();
        assertNotNull(tc);
        assertEquals(Map.of("X-User-Id", "alice"), tc.get(UserScopedMcpClientWrapper.HEADER_KEY));
    }

    @Test
    void shouldFailClosedOnMissingReqHeaderValueUnderDeny() {
        // reqHeader: 取值源沿用 on-missing 语义：deny（缺省）下请求未带头 → 拒绝调用；
        // 文案剥前缀回显头名并指引 chat 请求携带——模型无法写 McpMeta，原指引不可行动
        var delegate = new RecordingClient();
        var scoped = wrapper(delegate, Map.of("Authorization", "reqHeader:authorization"), true);

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("userId", "alice")))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(McpUserHeaderException.class, e);
                assertTrue(e.getMessage().contains("authorization"), e.getMessage());
                assertTrue(e.getMessage().contains("test-server"), e.getMessage());
                assertFalse(e.getMessage().contains("reqHeader:"), e.getMessage());
                assertFalse(e.getMessage().contains("McpMeta"), e.getMessage());
            })
            .verify();
        assertNull(delegate.seenMeta.get(), "delegate 不应被调用");
    }

    @Test
    void shouldSplitDenyGuidanceBySourceWhenMixedKeysMissing() {
        // 混合缺失：reqHeader:* 剥前缀给 chat 请求头指引，其余维持 McpMeta 指引
        var scoped = wrapper(new RecordingClient(),
            Map.of("Authorization", "reqHeader:authorization", "X-User-Id", "userId"), true);

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of()))
            .expectErrorSatisfies(e -> {
                assertTrue(e.getMessage().contains("authorization"), e.getMessage());
                assertTrue(e.getMessage().contains("userId"), e.getMessage());
                assertTrue(e.getMessage().contains("McpMeta"), e.getMessage());
                assertFalse(e.getMessage().contains("reqHeader:"), e.getMessage());
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

    // ===== arguments 通道（mcp-header-arguments-injection-design）=====

    /** 构造带 inputSchema 的工具定义（schema 门控用） */
    private static McpSchema.Tool toolWithSchema(String name, Map<String, Object> properties,
                                                 Boolean additionalProperties) {
        return new McpSchema.Tool(name, null, "desc",
            new McpSchema.JsonSchema("object", properties, null, additionalProperties, null, null),
            null, null, null);
    }

    /** 仅声明 arguments 映射的 wrapper（参数通道无 fail-closed，deny/passthrough 无关） */
    private static UserScopedMcpClientWrapper argWrapper(McpClientWrapper delegate,
                                                         Map<String, String> arguments) {
        return new UserScopedMcpClientWrapper(delegate, new UserHeaderRule(Map.of(), arguments, false));
    }

    @Test
    void shouldInjectArgumentFromReqHeaderIntoToolCallArguments() {
        var delegate = new RecordingClient();
        var scoped = argWrapper(delegate, Map.of("taskId", "reqHeader:taskid"));
        var meta = Map.<String, Object>of("userId", "alice", "reqHeader:taskid", "1001-1757858");

        StepVerifier.create(scoped.callTool("echo", Map.of("q", "hello"), meta))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("q", "hello", "taskId", "1001-1757858"), delegate.seenArguments.get(),
            "请求头原值应合并进工具入参（含不可变入参的防御性拷贝）");
        // 透传值与 header 通道同款剥离：不随 _meta 下发
        assertEquals(Map.of("userId", "alice"), delegate.seenMeta.get());
        assertNull(delegate.seenTransportContext.get(), "arguments 通道不写 transport context");
    }

    @Test
    void shouldSkipArgumentInjectionWhenValueMissing() {
        // 参数通道无 fail-closed：缺值恒跳过，调用照常
        var delegate = new RecordingClient();
        var scoped = argWrapper(delegate, Map.of("taskId", "reqHeader:taskid"));

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("userId", "alice")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of(), delegate.seenArguments.get(), "缺值不注入，入参原样");
        assertNull(delegate.seenTransportContext.get());
    }

    @Test
    void shouldNotOverrideExplicitArguments() {
        // args-win：调用方/模型显式给出的参数优先，框架只补缺
        var delegate = new RecordingClient();
        var scoped = argWrapper(delegate, Map.of("taskId", "reqHeader:taskid"));

        StepVerifier.create(scoped.callTool("echo", Map.of("taskId", "model-decided"),
                Map.of("reqHeader:taskid", "1001-1757858")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("taskId", "model-decided"), delegate.seenArguments.get(),
            "显式参数不得被 header 值覆盖");
    }

    @Test
    void shouldGateInjectionByToolInputSchema() {
        // additionalProperties:false 的严格 schema：仅注入 properties 已声明的参数
        var delegate = new RecordingClient();
        delegate.cacheTool(toolWithSchema("echo",
            Map.of("taskId", Map.of("type", "string")), false));
        var scoped = argWrapper(delegate, Map.of(
            "taskId", "reqHeader:taskid",
            "extra", "reqHeader:extra"));

        StepVerifier.create(scoped.callTool("echo", Map.of(),
                Map.of("reqHeader:taskid", "1001-1757858", "reqHeader:extra", "x")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("taskId", "1001-1757858"), delegate.seenArguments.get(),
            "严格 schema 未声明的参数不得注入");
    }

    @Test
    void shouldInjectWhenSchemaAllowsAdditionalProperties() {
        // properties 未声明但 additionalProperties 缺省（null）——JSON Schema 缺省即允许追加
        var delegate = new RecordingClient();
        delegate.cacheTool(toolWithSchema("echo",
            Map.of("q", Map.of("type", "string")), null));
        var scoped = argWrapper(delegate, Map.of("taskId", "reqHeader:taskid"));

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("reqHeader:taskid", "1001-1757858")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("taskId", "1001-1757858"), delegate.seenArguments.get());
    }

    @Test
    void shouldProceedBestEffortWhenToolSchemaUnavailable() {
        // 工具未入缓存（schema 不可得）：best-effort 注入，兼容下游 schema 写漏的松散实现
        var delegate = new RecordingClient();
        var scoped = argWrapper(delegate, Map.of("taskId", "reqHeader:taskid"));

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("reqHeader:taskid", "1001-1757858")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("taskId", "1001-1757858"), delegate.seenArguments.get());
    }

    @Test
    void shouldInjectArgumentsFromUserIdSource() {
        // 取值源与 headers 节同款抽象：userId（内建 meta key）可用
        var delegate = new RecordingClient();
        var scoped = argWrapper(delegate, Map.of("tenantId", "userId"));

        StepVerifier.create(scoped.callTool("echo", Map.of(), Map.of("userId", "alice")))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("tenantId", "alice"), delegate.seenArguments.get());
    }

    @Test
    void shouldInjectBothChannelsTogether() {
        // headers + arguments 双通道同调用并发：HTTP header 与工具入参各自生效，_meta 恒剥离
        var delegate = new RecordingClient();
        var scoped = new UserScopedMcpClientWrapper(delegate, new UserHeaderRule(
            Map.of("X-Task-Id", "reqHeader:taskid"), Map.of("taskId", "reqHeader:taskid"), true));
        var meta = Map.<String, Object>of("userId", "alice", "reqHeader:taskid", "1001-1757858");

        StepVerifier.create(scoped.callTool("echo", Map.of(), meta))
            .expectNextMatches(r -> !r.isError())
            .verifyComplete();

        assertEquals(Map.of("taskId", "1001-1757858"), delegate.seenArguments.get());
        var tc = delegate.seenTransportContext.get();
        assertNotNull(tc);
        assertEquals(Map.of("X-Task-Id", "1001-1757858"), tc.get(UserScopedMcpClientWrapper.HEADER_KEY));
        assertEquals(Map.of("userId", "alice"), delegate.seenMeta.get());
    }
}
