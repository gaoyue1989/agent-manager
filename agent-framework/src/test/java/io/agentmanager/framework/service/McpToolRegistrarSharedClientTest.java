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
        /** 非空时强制 registerFiltered 路径（SDK 未介入，失败可归因 registrar 自身行为） */
        Map<String, Boolean> activeMcpOverride;

        StubRegistrar(AgentManagerProperties props) {
            super(props);
        }

        @Override
        McpClientWrapper buildBaseClient(OafConfig.McpServerConfig mcp) {
            return bases.poll();
        }

        @Override
        Map<String, Boolean> loadActiveMcpConfig(OafConfig.McpServerConfig mcp) {
            return activeMcpOverride != null ? activeMcpOverride : super.loadActiveMcpConfig(mcp);
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

    // ---------- 注册失败驱逐即关闭 fresh 句柄（issue #353 防泄漏） ----------
    // fresh 句柄（本次新建且刚 CAS 入缓存，注册失败发生在交付任何 toolkit 之前）被 CAS
    // 驱逐后即脱离缓存，evictSharedWrapper/reload 收尾/toolkit 清理全部不可达——不 close
    // 即无持有者孤儿，server 持续宕机时看门狗每 30s 补偿一次泄漏一个连接（stdio 泄漏
    // 子进程、HTTP 泄漏 HttpClient）。

    @Test
    void registerOneFailureShouldCloseFreshlyBuiltHandle() {
        var base = deadBase();
        registrar.bases.add(base);

        assertNull(registrar.registerOne(toolkit, mcp));
        assertNull(registrar.sharedWrapper(mcp.server()), "死句柄仍须驱逐（自愈语义不变）");
        verify(base, times(1)).close();
    }

    @Test
    void registerOneFailureShouldNotCloseReusedHandle() {
        // activeMcpOverride 强制 registerFiltered（initialize→listTools，SDK 未介入），
        // 失败可归因 registrar 自身；标准路径 SDK apply() 失败会自关 client
        // （McpClientManager lambda:373 在案），无法隔离 registrar 的 close 契约
        var base = healthyBase("b1");
        when(base.listTools()).thenReturn(Mono.just(List.<McpSchema.Tool>of()),
            Mono.error(new RuntimeException("tools listing failed")));
        registrar.activeMcpOverride = Map.of("tool-x", true);
        registrar.bases.add(base);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));

        assertNull(registrar.registerOne(new Toolkit(), mcp));
        assertNull(registrar.sharedWrapper(mcp.server()), "失败句柄仍须驱逐");
        // 复用句柄可能被其他 toolkit 引用（首注册 toolkit 在持），生命周期归缓存统一管理
        verify(base, never()).close();
    }

    @Test
    void syntheticRegistrationFailureShouldCloseFreshWrapper() {
        var base = deadBase();
        registrar.bases.add(base);
        var synthetic = syntheticConfig();

        assertNull(registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        assertNull(registrar.sharedWrapper(synthetic.server()), "注册失败死句柄必须被驱逐");
        verify(base, times(1)).close();
    }

    @Test
    void syntheticRejectedWrapperShouldBeClosedImmediately() {
        var base = healthyBase("b1");
        registrar.bases.add(base);
        var synthetic = syntheticConfig();
        // 看门狗 agent==null 分支先入缓存：openviking 已有可换芯句柄
        assertTrue(registrar.cacheSyntheticClient(synthetic, scopedFactory()));
        var cached = registrar.sharedWrapper(synthetic.server());

        // 新会话 build：工厂照常产出（build 先于 putIfAbsent），被拒 wrapper 从未入缓存
        // 即无任何持有者，即刻关闭（sharedClient 竞态败者 closeQuietly 先例），
        // 注册走缓存句柄照常成功
        var rejected = deadBase();
        registrar.bases.add(rejected);
        assertEquals(synthetic.server(), registrar.registerSyntheticClient(new Toolkit(), synthetic, scopedFactory()));
        verify(rejected, times(1)).close();
        verify(base, never()).close();
        assertSame(cached, registrar.sharedWrapper(synthetic.server()));
    }

    // ---------- 合成 server 生命周期（issue #415） ----------
    // 三处同根因缺陷的句柄面：① 句柄必须 Reconnectable(UserScoped(base)) 形态（与
    // registerOne 的 buildClient 产物对称——UserScoped 承载 _meta reqHeader:* 剥离卫生，
    // Reconnectable 承载看门狗换芯）；② rebuildSharedWrapper 对合成 server 走注册表工厂
    // 换芯（形态对称，防装饰丢失回归）；③ 注册表（config+factory）是看门狗探测/补偿与
    // A 路径 reload 豁免的单一真相源，evictSharedWrappers 不清它（B 路径 build 失败
    // 回滚窗口内旧 agent 的合成句柄仍可自愈）。

    /** 合成 server 声明（HarnessAgentFactory.registerVikingSharedContextMcp 同款形态） */
    private static OafConfig.McpServerConfig syntheticConfig() {
        return new OafConfig.McpServerConfig("openviking", "openviking", "1.0.0", "", false);
    }

    /** 合成 base 工厂桩：从队列取桩连接并按真实工厂形态包 UserScoped（评审 P1 形态对称） */
    private java.util.function.Supplier<McpClientWrapper> scopedFactory() {
        return () -> {
            var base = registrar.bases.poll();
            return base == null ? null
                : new io.agentmanager.framework.mcp.UserScopedMcpClientWrapper(
                    base, io.agentmanager.framework.mcp.UserHeaderRule.EMPTY);
        };
    }

    @Test
    void syntheticRegistrationShouldWrapReconnectableWithUserScopedDelegate() {
        var base = healthyBase("ov-base");
        registrar.bases.add(base);
        var synthetic = syntheticConfig();

        assertEquals(synthetic.server(), registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));

        var handle = registrar.sharedWrapper(synthetic.server());
        assertInstanceOf(ReconnectableMcpClientWrapper.class, handle);
        var delegate = ((ReconnectableMcpClientWrapper) handle).delegate();
        assertInstanceOf(io.agentmanager.framework.mcp.UserScopedMcpClientWrapper.class, delegate);
        assertEquals("ov-base", delegate.getName());
    }

    @Test
    void syntheticRebuildShouldSwapViaFactoryKeepingHandleAndShape() {
        registrar.bases.add(healthyBase("old"));
        var synthetic = syntheticConfig();
        assertEquals(synthetic.server(), registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        var handleBefore = registrar.sharedWrapper(synthetic.server());

        // 换芯经注册表工厂（不走 buildBaseClient——合成 server 无 config.yaml connection 节）：
        // 句柄实例不变，delegate 仍是 UserScoped 形态（评审 P1 形态对称断言）
        registrar.bases.add(healthyBase("new"));
        assertTrue(registrar.rebuildSharedWrapper(synthetic.server(), synthetic));

        assertSame(handleBefore, registrar.sharedWrapper(synthetic.server()));
        var delegate = ((ReconnectableMcpClientWrapper) handleBefore).delegate();
        assertInstanceOf(io.agentmanager.framework.mcp.UserScopedMcpClientWrapper.class, delegate);
        assertEquals("new", delegate.getName());
    }

    @Test
    void syntheticRebuildFailureShouldKeepOldHandle() {
        registrar.bases.add(healthyBase("old"));
        var synthetic = syntheticConfig();
        registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory());
        var handleBefore = registrar.sharedWrapper(synthetic.server());

        // 工厂产物为 null（build 失败）→ 保旧句柄
        assertFalse(registrar.rebuildSharedWrapper(synthetic.server(), synthetic));
        assertSame(handleBefore, registrar.sharedWrapper(synthetic.server()));

        // 握手失败 → 保旧句柄、新连接关闭防泄漏
        var broken = mock(McpClientWrapper.class);
        when(broken.getName()).thenReturn("broken");
        when(broken.initialize()).thenReturn(Mono.error(new RuntimeException("handshake failed")));
        registrar.bases.add(broken);
        assertFalse(registrar.rebuildSharedWrapper(synthetic.server(), synthetic));
        assertSame(handleBefore, registrar.sharedWrapper(synthetic.server()));
        verify(broken).close();
    }

    @Test
    void syntheticRegistrationFailureShouldEvictHandleButKeepFactoryForCompensation() {
        var base = deadBase();
        registrar.bases.add(base);
        var synthetic = syntheticConfig();

        assertNull(registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        assertNull(registrar.sharedWrapper(synthetic.server()));
        verify(base, times(1)).close();
        // 工厂保留在注册表：看门狗下周期可补偿复活（agent!=null 补注册 / agent==null 入缓存）
        var registry = registrar.syntheticServers();
        assertTrue(registry.containsKey(synthetic.server()));

        // 复活：同一工厂产出健康连接 → 补注册成功且句柄入缓存
        registrar.bases.add(healthyBase("revived"));
        assertEquals(synthetic.server(),
            registrar.registerSyntheticClient(new Toolkit(), synthetic,
                registry.get(synthetic.server()).factory()));
        assertNotNull(registrar.sharedWrapper(synthetic.server()));
    }

    @Test
    void cacheSyntheticClientShouldCacheHandleWithoutRegisteringTools() {
        var synthetic = syntheticConfig();
        registrar.bases.add(healthyBase("ov"));

        // 看门狗 compensate 的 agent==null 分支：仅把可换芯句柄入缓存，不注册工具
        assertTrue(registrar.cacheSyntheticClient(synthetic, scopedFactory()));
        var handle = registrar.sharedWrapper(synthetic.server());
        assertInstanceOf(ReconnectableMcpClientWrapper.class, handle);
        assertTrue(registrar.getToolsByServer(synthetic.server()).isEmpty());
        assertFalse(registrar.getRegisteredServerNames().contains(synthetic.server()));

        // 新会话 build 经 registerSyntheticClient 的 putIfAbsent 复用该句柄：
        // 工厂照常产出（build 先于 putIfAbsent），被拒新句柄即刻关闭，注册走缓存句柄
        var rejected = healthyBase("rejected-b2");
        registrar.bases.add(rejected);
        assertEquals(synthetic.server(), registrar.registerSyntheticClient(new Toolkit(), synthetic, scopedFactory()));
        assertSame(handle, registrar.sharedWrapper(synthetic.server()));
        verify(rejected, times(1)).close();
    }

    @Test
    void cacheSyntheticClientShouldFailSoftWhenBuildReturnsNull() {
        var synthetic = syntheticConfig();
        assertFalse(registrar.cacheSyntheticClient(synthetic, scopedFactory()), "队列空=build 返回 null");
        assertNull(registrar.sharedWrapper(synthetic.server()));
    }

    @Test
    void evictSharedWrappersShouldKeepSyntheticRegistryForRollbackWindow() {
        registrar.bases.add(healthyBase("ov"));
        var synthetic = syntheticConfig();
        registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory());

        // B 路径 build 前 evict：清共享缓存但不清合成注册表——build 失败回滚窗口内
        // 旧 agent 的合成句柄仍可被看门狗探测/换芯/补偿（评审 P2；工厂闭包 env 派生、
        // 同名未声明时世代间等价，残留无害）
        registrar.evictSharedWrappers();
        assertNull(registrar.sharedWrapper(synthetic.server()));
        assertTrue(registrar.syntheticServers().containsKey(synthetic.server()));
    }

    @Test
    void removeSyntheticServersShouldDropResidualEntriesWithoutTouchingSharedCache() {
        registrar.bases.add(healthyBase("ov"));
        var synthetic = syntheticConfig();
        registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory());
        assertNotNull(registrar.sharedWrapper(synthetic.server()));

        // 代际收口（reloadAgent swap 成功后）：同名已转 frontmatter 声明 → 残留项移除，
        // 该名此后完全归属 declared 链路（rebuild/补偿/探测不再走 viking 工厂）；
        // 共享缓存不触碰——清理点缓存已是新代 declared 句柄（gen1 合成句柄由旧代收尾关闭）
        assertEquals(List.of("openviking"), registrar.removeSyntheticServers(List.of("openviking", "other")));
        assertFalse(registrar.syntheticServers().containsKey(synthetic.server()));
        assertNotNull(registrar.sharedWrapper(synthetic.server()));

        // 幂等：已移除名再清理为 no-op
        assertTrue(registrar.removeSyntheticServers(List.of("openviking")).isEmpty());
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

    // ---------- 原地 reload 换芯后 clearServer 不得关闭共享句柄（issue #410 回归） ----------
    // A 路径 reload 的 clearServer ② toolkit.removeMcpClient 会命中 SDK mcpClients 登记
    // （标准路径注册的正是共享句柄）并经 Mono.fromRunnable(wrapper::close) 关闭它——
    // ReconnectableMcpClientWrapper.close() 永久置 closed，刚换芯的活连接被误杀、句柄
    // 拒绝后续 swap。共享句柄是全体 toolkit（单例+存量会话）的活连接，②③ 都不得触碰；
    // 工具摘除由既有 ①（按注册名逐个 removeTool）完成。

    @Test
    void clearServerShouldKeepRebuiltSharedHandleAliveForInPlaceReload() {
        var displayName = "mcp__" + mcp.server() + "__get_time";
        var tool = new McpSchema.Tool("get_time", "", "desc", null, null, null, null);
        // 标准路径注册进真实 Toolkit（listTools 返回 1 个工具）
        var base1 = mock(McpClientWrapper.class);
        when(base1.getName()).thenReturn("base1");
        when(base1.initialize()).thenReturn(Mono.empty());
        when(base1.listTools()).thenReturn(Mono.just(List.of(tool)));
        registrar.bases.add(base1);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        assertTrue(toolkit.getToolNames().contains(displayName));

        // 看门狗原地换芯：句柄 delegate → base2（实例不变，换芯后新连接上报同一工具面）
        var base2 = mock(McpClientWrapper.class);
        when(base2.getName()).thenReturn("base2");
        when(base2.initialize()).thenReturn(Mono.empty());
        when(base2.listTools()).thenReturn(Mono.just(List.of(tool)));
        registrar.bases.add(base2);
        assertTrue(registrar.rebuildSharedWrapper(mcp.server(), mcp));
        var handle = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server());
        assertSame(base2, handle.delegate());

        // A 路径 reload 原地 clearServer：共享句柄不透传 SDK removeMcpClient 的 close
        assertTrue(registrar.clearServer(toolkit, mcp.server()));
        verify(base2, never()).close();

        // 重注册：bases 队列空 → 命中缓存复用同一句柄（delegate 仍 base2），工具面恢复
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        assertSame(handle, registrar.sharedWrapper(mcp.server()));
        assertSame(base2, ((ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server())).delegate());
        assertTrue(toolkit.getToolNames().contains(displayName), "clearServer 后重注册必须恢复工具面");

        // 句柄未被 close 置死：换芯语义保持可用
        assertTrue(handle.swap(healthyBase("base3")), "共享句柄不得被 clearServer 置 closed");
    }
}
