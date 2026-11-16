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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    // ---------- clearServer 共享句柄不 close 契约（issue #348 防回归核心） ----------
    // clearServer ③ 的守卫（sharedWrappers.get(server) == oldWrapper 时不 close）保护
    // A 路径 reload「摘旧注册→重注册」复用同一句柄，不误杀全部存量会话的连接；
    // 句柄的下线由定向驱逐（evictSharedWrapper）负责。删掉守卫现有套件全绿。

    /**
     * clearServer 契约隔离桩：registration() 走真实 SDK 生命周期（一次性 toolkit 承接注册），
     * removeMcpClient 打空 Mono——真实 Toolkit 的 ② 步命中 mcpClients 登记会经 SDK 关闭共享句柄
     * （Mono.fromRunnable(wrapper::close)），隔离后才能钉住 ③ 守卫自身的 close/no-close 契约。
     */
    private Toolkit contractToolkit() {
        var toolkit = mock(Toolkit.class);
        when(toolkit.registration()).thenAnswer(inv -> new Toolkit().registration());
        when(toolkit.removeMcpClient(anyString())).thenReturn(Mono.empty());
        return toolkit;
    }

    @Test
    void clearServerShouldKeepSharedHandleAliveForAPathReload() {
        var base = healthyBase("b1");
        registrar.bases.add(base);
        var toolkit = contractToolkit();
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var handleBefore = registrar.sharedWrapper(mcp.server());
        assertInstanceOf(ReconnectableMcpClientWrapper.class, handleBefore);

        assertTrue(registrar.clearServer(toolkit, mcp.server()));
        // 守卫：缓存中仍是该句柄 → 不 close（误杀即全部存量会话连接被 reload 关闭）
        assertSame(handleBefore, registrar.sharedWrapper(mcp.server()));
        verify(base, never()).close();

        // A 路径 reload 收尾：bases 队列已空 → 重注册命中缓存复用同一句柄
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        assertSame(handleBefore, registrar.sharedWrapper(mcp.server()));
        assertSame(handleBefore, registrar.getRegisteredWrapper(mcp.server()));
        verify(base, never()).close();
    }

    @Test
    void clearServerShouldCloseWrapperNoLongerInSharedCache() {
        var base = healthyBase("b1");
        registrar.bases.add(base);
        var toolkit = contractToolkit();
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var handle = registrar.sharedWrapper(mcp.server());

        // 定向驱逐（声明已移除）：缓存摘除 + 显式关闭（第 1 次 close）
        assertTrue(registrar.evictSharedWrapper(mcp.server()));
        verify(base, times(1)).close();

        // clearServer：缓存已无该句柄（tracked ≠ 缓存）→ else 分支必须兜底 close（第 2 次），
        // 防手动路径旧专属连接/被驱逐句柄的连接泄漏；守卫被「简化」成永不 close 则此用例红
        assertTrue(registrar.clearServer(toolkit, mcp.server()));
        verify(base, times(2)).close();
        assertNull(registrar.sharedWrapper(mcp.server()), "clearServer 不回填共享缓存");
    }
}
