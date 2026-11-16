package io.agentmanager.framework.service;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Mono;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.mcp.ReconnectableMcpClientWrapper;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共享连接缓存断联恢复单测（issue #348）：注册失败 CAS 驱逐死句柄（P0 新会话自愈）、
 * rebuildSharedWrapper 原地换芯（句柄实例稳定，持有方免重注册）、无句柄拒绝换芯、
 * 定向驱逐关闭。
 */
class McpToolRegistrarSharedClientTest {

    @TempDir
    Path tempDir;

    /** buildBaseClient 桩化：按队列吐出预置 base，不发起真实连接 */
    private static final class StubRegistrar extends McpToolRegistrar {
        final Queue<McpClientWrapper> bases = new ArrayDeque<>();

        StubRegistrar(AgentManagerProperties props) {
            super(props);
        }

        @Override
        McpClientWrapper buildBaseClient(OafConfig.McpServerConfig mcp) {
            return bases.poll();
        }
    }

    private StubRegistrar registrar;
    private Toolkit toolkit;
    private OafConfig.McpServerConfig mcp;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults()
        );
        registrar = new StubRegistrar(props);
        toolkit = new Toolkit();
        mcp = new OafConfig.McpServerConfig("vendor", "platform-publisher", "1.0.0", "platform-publisher", false);
    }

    /** 可用连接桩：initialize 空成功、listTools 空清单（标准注册路径零工具注册） */
    private McpClientWrapper healthyBase(String marker) {
        var base = mock(McpClientWrapper.class);
        when(base.getName()).thenReturn(marker);
        when(base.initialize()).thenReturn(Mono.empty());
        when(base.listTools()).thenReturn(Mono.just(List.<McpSchema.Tool>of()));
        return base;
    }

    /** 死连接桩：initialize 即失败（服务端重启后缓存死会话的注册现场） */
    private McpClientWrapper deadBase() {
        var base = mock(McpClientWrapper.class);
        when(base.getName()).thenReturn("dead");
        when(base.initialize()).thenReturn(Mono.error(new RuntimeException("MCP session closed")));
        return base;
    }

    @Test
    void registerOneFailureShouldEvictSharedClientSoNextSessionRebuilds() {
        registrar.bases.add(deadBase());

        assertNull(registrar.registerOne(toolkit, mcp));
        assertNull(registrar.sharedWrapper(mcp.server()), "死句柄必须被驱逐，否则后续会话复用同一死连接");

        // 下一会话重建：新 base 可用 → 注册成功且缓存装入全新句柄
        var freshBase = healthyBase("fresh");
        registrar.bases.add(freshBase);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var handle = registrar.sharedWrapper(mcp.server());
        assertNotNull(handle);
        assertSame(freshBase, ((ReconnectableMcpClientWrapper) handle).delegate());
    }

    @Test
    void registerOneSuccessShouldCacheHandleAndReuseAcrossSessions() {
        var base = healthyBase("b1");
        registrar.bases.add(base);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));

        var handle = registrar.sharedWrapper(mcp.server());
        assertNotNull(handle);
        assertInstanceOf(ReconnectableMcpClientWrapper.class, handle);
        assertSame(base, ((ReconnectableMcpClientWrapper) handle).delegate());

        // 第二个会话注册：命中缓存复用同一句柄（一次物理连接服务 N 个会话）
        registrar.bases.clear();
        assertEquals(mcp.server(), registrar.registerOne(new Toolkit(), mcp));
        assertSame(handle, registrar.sharedWrapper(mcp.server()));
    }

    @Test
    void rebuildShouldSwapHandleInPlaceKeepingInstanceStable() {
        registrar.bases.add(healthyBase("old"));
        registrar.registerOne(toolkit, mcp);
        var handleBefore = registrar.sharedWrapper(mcp.server());

        var newBase = healthyBase("new");
        registrar.bases.add(newBase);
        assertTrue(registrar.rebuildSharedWrapper(mcp.server(), mcp));

        // 句柄实例不变（全部 toolkit 持有方免重注册即自愈），delegate 已指向新连接
        assertSame(handleBefore, registrar.sharedWrapper(mcp.server()));
        assertSame(newBase, ((ReconnectableMcpClientWrapper) handleBefore).delegate());
        verify(newBase).initialize();
    }

    @Test
    void rebuildShouldKeepOldHandleWhenBuildOrHandshakeFails() {
        registrar.bases.add(healthyBase("old"));
        registrar.registerOne(toolkit, mcp);
        var handleBefore = registrar.sharedWrapper(mcp.server());

        // build 失败（桩队列空 → buildBaseClient 返回 null）
        assertFalse(registrar.rebuildSharedWrapper(mcp.server(), mcp));
        assertSame(handleBefore, registrar.sharedWrapper(mcp.server()));
        assertSame("old", ((ReconnectableMcpClientWrapper) handleBefore).delegate().getName());

        // 握手失败：新 base initialize 报错 → 不换芯、新连接关闭防泄漏
        var brokenBase = mock(McpClientWrapper.class);
        when(brokenBase.getName()).thenReturn("broken");
        when(brokenBase.initialize()).thenReturn(Mono.error(new RuntimeException("conn refused")));
        registrar.bases.add(brokenBase);
        assertFalse(registrar.rebuildSharedWrapper(mcp.server(), mcp));
        assertSame(handleBefore, registrar.sharedWrapper(mcp.server()));
        verify(brokenBase).close();
    }

    @Test
    void rebuildShouldRefuseWhenNoSharedClient() {
        // 缓存无句柄（从未注册成功/已被 P0 驱逐）：换芯无目标，走 compensate 补建
        assertFalse(registrar.rebuildSharedWrapper(mcp.server(), mcp));
        assertTrue(registrar.bases.isEmpty());
    }

    @Test
    void evictSharedWrapperShouldRemoveAndCloseHandle() {
        var base = healthyBase("b1");
        registrar.bases.add(base);
        registrar.registerOne(toolkit, mcp);

        assertTrue(registrar.evictSharedWrapper(mcp.server()));
        assertNull(registrar.sharedWrapper(mcp.server()));
        verify(base).close();
        assertFalse(registrar.evictSharedWrapper(mcp.server()), "重复驱逐幂等返回 false");
    }
}
