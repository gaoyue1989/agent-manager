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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * 共享连接缓存断联恢复单测（issue #348）：注册失败句柄毒化留缓存原地换芯（issue #417
 * 修订驱逐语义，不驱逐不 close）、rebuildSharedWrapper 原地换芯（句柄实例稳定，持有方
 * 免重注册）、无句柄拒绝换芯、定向驱逐关闭。
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
    void registerOneFailureShouldPoisonHandleForInPlaceRebuild() {
        // issue #417：注册失败的驱逐改为毒化标记——句柄实例留缓存（看门狗持续管理、存量
        // 引用方免脱管），下一会话 sharedClient 见毒化原地换芯复用同一实例
        registrar.bases.add(deadBase());

        assertNull(registrar.registerOne(toolkit, mcp));
        var handle = registrar.sharedWrapper(mcp.server());
        assertNotNull(handle, "毒化句柄必须留缓存（驱逐脱管会让存量会话失去看门狗自愈）");
        assertTrue(((ReconnectableMcpClientWrapper) handle).poisoned(), "注册失败句柄必须标记毒化");

        // 下一会话：毒化句柄原地换芯（新 base swap 进同一实例）→ 注册成功
        var freshBase = healthyBase("fresh");
        registrar.bases.add(freshBase);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        assertSame(handle, registrar.sharedWrapper(mcp.server()),
            "原地换芯：句柄实例稳定，持有方免重注册");
        assertSame(freshBase, ((ReconnectableMcpClientWrapper) handle).delegate());
        assertFalse(((ReconnectableMcpClientWrapper) handle).poisoned(), "换芯成功毒化清除");
    }

    @Test
    void reusedHandleRegistrationFailureShouldNotEvictNorCloseSharedHandle() {
        // issue #417 根因 1：复用句柄（freshOwner=false）注册失败被 CAS 驱逐出缓存——存量
        // 会话句柄脱管（看门狗不再探测，恢复后丢工具），后续 registeredWrappers.put 的
        // closeQuietly(prev) 还会把存活句柄强杀。修复：复用句柄失败只毒化，不驱逐不关闭
        var base = healthyBase("shared");
        registrar.bases.add(base);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var handle = registrar.sharedWrapper(mcp.server());

        // 故障期第二会话：复用同一句柄 + 注册环节失败（ActiveMCP 过滤路径内 listTools 抛）
        when(base.listTools()).thenThrow(new RuntimeException("server restarted"));
        registrar.activeMcpOverride = Map.of("t1", true);
        assertNull(registrar.registerOne(new Toolkit(), mcp));

        assertSame(handle, registrar.sharedWrapper(mcp.server()),
            "复用句柄注册失败不得驱逐出缓存（脱管即自愈断链）");
        verify(base, never()).close();
        assertTrue(((ReconnectableMcpClientWrapper) handle).poisoned());

        // 恢复期第三会话：毒化句柄原地换芯成功。旧死连接由 swap 装新关旧关闭（times(1)
        // 即换芯的正规关闭路径，非注册失败路径误杀）；句柄实例本身从未 close（closed 会
        // 拒绝后续 swap，等价强杀）
        // doReturn 形式重设：when(base.listTools()) 语法会实际调用抛异常的 stub 立刻炸
        doReturn(Mono.just(List.<McpSchema.Tool>of())).when(base).listTools();
        var freshBase = healthyBase("recovered");
        registrar.bases.add(freshBase);
        assertEquals(mcp.server(), registrar.registerOne(new Toolkit(), mcp));
        assertSame(handle, registrar.sharedWrapper(mcp.server()));
        assertSame(freshBase, ((ReconnectableMcpClientWrapper) handle).delegate());
        verify(base, times(1)).close();
        assertFalse(((ReconnectableMcpClientWrapper) handle).isClosed());
    }

    @Test
    void staleFailureAfterWatchdogSwapShouldNotRepoisonFreshDelegate() {
        // issue #493 缺陷①：会话 B 持旧连接（base1）注册期间，看门狗已对句柄换芯健康
        // base2 并清毒化；B 的旧连接随后失败进入 catch——无版本 markPoisoned 会把刚换芯
        // 的健康 delegate 重新毒化，触发下一次会话不必要的销毁重建
        var base1 = mock(McpClientWrapper.class);
        when(base1.getName()).thenReturn("base1");
        when(base1.initialize()).thenReturn(Mono.empty());
        when(base1.listTools()).thenReturn(Mono.just(List.<McpSchema.Tool>of()));
        registrar.bases.add(base1);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var handle = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server());

        // 会话 B：registerFiltered 路径 listTools 触发时，看门狗换芯 base2 后旧连接才失败
        var base2 = healthyBase("base2");
        when(base1.listTools()).thenAnswer(inv -> {
            registrar.bases.add(base2);
            assertTrue(registrar.rebuildSharedWrapper(mcp.server(), mcp));
            throw new RuntimeException("stale base1 connection dropped");
        });
        registrar.activeMcpOverride = Map.of("t1", true);
        assertNull(registrar.registerOne(new Toolkit(), mcp));

        assertFalse(handle.poisoned(), "换芯后的健康 delegate 不得被旧连接的失败毒化");
        assertSame(base2, handle.delegate());
        verify(base2, never()).close();
    }

    @Test
    void poisonedHandleReusedWithSuccessfulRegistrationShouldClearPoison() {
        // issue #493 伴随语义：毒化句柄 rebuild 失败（队列空）后复用注册成功——注册过程
        // 即验证当前 delegate 可用，毒化标记必须失效，否则残留触发后续会话无谓换芯
        var base = healthyBase("b1");
        registrar.bases.add(base);
        doReturn(Mono.error(new RuntimeException("server flapping"))).when(base).initialize();
        assertNull(registrar.registerOne(toolkit, mcp));
        var handle = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server());
        assertTrue(handle.poisoned());

        doReturn(Mono.empty()).when(base).initialize();
        assertEquals(mcp.server(), registrar.registerOne(new Toolkit(), mcp));
        assertFalse(handle.poisoned(), "注册成功 = 当前 delegate 已验证，毒化失效");
        assertSame(base, handle.delegate());
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
        // 缓存无句柄（从未注册成功/已被定向驱逐）：换芯无目标，走 compensate 补建
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

    // ---------- 注册失败毒化处置（issue #417 修订 #353 驱逐即 close） ----------
    // fresh / 复用句柄注册失败一律毒化留缓存：驱逐会让句柄脱管（看门狗自愈断链、后续
    // registeredWrappers.put 强杀存活句柄），驱逐即 close 会让 fresh 句柄成无持有者孤儿
    // （#353：stdio 泄漏子进程、HTTP 泄漏 HttpClient）。毒化后由 sharedClient/看门狗对
    // 句柄原地换芯，脱管与连接泄漏随驱逐语义一同消失。

    @Test
    void registerOneFreshHandleFailureShouldPoisonAndStayUnclosed() {
        var base = deadBase();
        registrar.bases.add(base);

        assertNull(registrar.registerOne(toolkit, mcp));
        var handle = registrar.sharedWrapper(mcp.server());
        assertNotNull(handle, "fresh 失败句柄必须留缓存（驱逐/close 即孤儿或脱管）");
        assertTrue(((ReconnectableMcpClientWrapper) handle).poisoned());
        verify(base, never()).close();
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
        var handle = registrar.sharedWrapper(mcp.server());

        assertNull(registrar.registerOne(new Toolkit(), mcp));
        assertSame(handle, registrar.sharedWrapper(mcp.server()),
            "失败句柄留缓存（驱逐即存量会话脱管，issue #417）");
        assertTrue(((ReconnectableMcpClientWrapper) handle).poisoned());
        // 复用句柄可能被其他 toolkit 引用（首注册 toolkit 在持），毒化处置同样不 close
        verify(base, never()).close();
    }

    @Test
    void syntheticRegistrationFailureShouldPoisonHandleWithoutClose() {
        var base = deadBase();
        registrar.bases.add(base);
        var synthetic = syntheticConfig();

        assertNull(registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        var handle = registrar.sharedWrapper(synthetic.server());
        assertNotNull(handle, "注册失败句柄毒化留缓存（驱逐即脱管，issue #417）");
        assertTrue(((ReconnectableMcpClientWrapper) handle).poisoned());
        verify(base, never()).close();
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

    /** 合成 server 声明（生产唯一构造点 HarnessAgentFactory.syntheticOpenvikingServerConfig） */
    private static OafConfig.McpServerConfig syntheticConfig() {
        return HarnessAgentFactory.syntheticOpenvikingServerConfig();
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
    void syntheticRegistrationFailureShouldPoisonHandleAndReviveByInPlaceSwap() {
        var base = deadBase();
        registrar.bases.add(base);
        var synthetic = syntheticConfig();

        assertNull(registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        var handle = registrar.sharedWrapper(synthetic.server());
        assertNotNull(handle, "失败句柄毒化留缓存（驱逐即脱管，issue #417）");
        assertTrue(((ReconnectableMcpClientWrapper) handle).poisoned());
        verify(base, never()).close();
        // 工厂保留在注册表：看门狗下周期可补偿复活（agent!=null 补注册 / agent==null 入缓存）
        var registry = registrar.syntheticServers();
        assertTrue(registry.containsKey(synthetic.server()));

        // 复活（issue #417 新会话换芯，issue #493 语义对称化）：毒化句柄经 rebuildSharedWrapper
        // 换芯（工厂产出经握手后 swap）→ 注册成功、句柄实例稳定；工厂被调用两次（本地构建
        // 被拒关闭 + rebuild 构建换入）
        registrar.bases.add(healthyBase("revived-local"));
        registrar.bases.add(healthyBase("revived"));
        assertEquals(synthetic.server(),
            registrar.registerSyntheticClient(new Toolkit(), synthetic,
                registry.get(synthetic.server()).factory()));
        assertSame(handle, registrar.sharedWrapper(synthetic.server()), "换芯复用：句柄实例稳定");
        assertFalse(((ReconnectableMcpClientWrapper) handle).poisoned(), "换芯成功毒化清除");
        var delegate = ((ReconnectableMcpClientWrapper) handle).delegate();
        assertInstanceOf(io.agentmanager.framework.mcp.UserScopedMcpClientWrapper.class, delegate);
        assertEquals("revived", delegate.getName());
    }

    @Test
    void syntheticPoisonedHandleRebuildMustHandshakeBeforeSwap() {
        // issue #493 缺陷②：毒化合成句柄的换芯不得绕过握手与单飞直接 swap——未握手连接
        // 换入会覆盖看门狗刚换入的健康新芯。统一走 rebuildSharedWrapper：握手失败不换芯
        // （坏连接关闭防泄漏），复用句柄注册成功即清毒化
        var good = healthyBase("ov-good");
        registrar.bases.add(good);
        var synthetic = syntheticConfig();
        assertEquals(synthetic.server(), registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        var handle = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(synthetic.server());

        // 故障期注册失败毒化句柄（工厂产物为本地构建的被拒 wrapper，注册走 good 失败）
        doReturn(Mono.error(new RuntimeException("MCP session closed"))).when(good).initialize();
        registrar.bases.add(healthyBase("rejected-local"));
        assertNull(registrar.registerSyntheticClient(new Toolkit(), synthetic, scopedFactory()));
        assertTrue(handle.poisoned(), "毒化预备：复用句柄注册失败毒化");

        // 恢复期：good 已健康但毒化未清；工厂产两个握手失败坏连接（本地构建被拒 + rebuild
        // 握手失败）→ 修复后换芯经 rebuildSharedWrapper，握手失败不换芯、good 保持现役
        doReturn(Mono.empty()).when(good).initialize();
        var badLocal = mock(McpClientWrapper.class);
        when(badLocal.getName()).thenReturn("bad-local");
        when(badLocal.initialize()).thenReturn(Mono.error(new RuntimeException("handshake failed 1")));
        var badRebuild = mock(McpClientWrapper.class);
        when(badRebuild.getName()).thenReturn("bad-rebuild");
        when(badRebuild.initialize()).thenReturn(Mono.error(new RuntimeException("handshake failed 2")));
        registrar.bases.add(badLocal);
        registrar.bases.add(badRebuild);

        assertEquals(synthetic.server(),
            registrar.registerSyntheticClient(new Toolkit(), synthetic, scopedFactory()));

        var delegate = handle.delegate();
        assertInstanceOf(io.agentmanager.framework.mcp.UserScopedMcpClientWrapper.class, delegate);
        assertEquals("ov-good", delegate.getName(), "握手失败的坏连接不得换入句柄");
        verify(badRebuild, times(1)).close();
        assertFalse(handle.poisoned(), "复用句柄注册成功 = 当前 delegate 已验证，毒化失效");
        assertFalse(handle.isClosed());
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
        assertTrue(handle.swap(healthyBase("base3")), "共享句柄不得被 clearServer 置死");
    }

    /** 标准路径可注册的连接桩（listTools 返回 1 个工具，供真实 Toolkit 登记 mcpClients 条目） */
    private McpClientWrapper registrableBase(String marker) {
        var tool = new McpSchema.Tool("get_time", "", "desc", null, null, null, null);
        var base = mock(McpClientWrapper.class);
        when(base.getName()).thenReturn(marker);
        when(base.initialize()).thenReturn(Mono.empty());
        when(base.listTools()).thenReturn(Mono.just(List.of(tool)));
        return base;
    }

    @Test
    void reloadAfterFailedRegisterOneShouldNotKillRebuiltSharedHandle() {
        registrar.bases.add(registrableBase("base1"));
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var handle = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server());

        // Reload #1：换芯成功 → clearServer（SDK mcpClients 条目滞留指向句柄）→ registerOne 瞬时失败（毒化、registeredWrappers 无条目）
        var base2 = registrableBase("base2");
        registrar.bases.add(base2);
        assertTrue(registrar.rebuildSharedWrapper(mcp.server(), mcp));
        assertTrue(registrar.clearServer(toolkit, mcp.server()));
        doReturn(Mono.error(new RuntimeException("server flapping"))).when(base2).initialize();
        assertNull(registrar.registerOne(toolkit, mcp), "Reload #1 re-registration fails transiently");
        assertTrue(handle.poisoned());
        assertFalse(handle.isClosed());
        assertNull(registrar.getRegisteredWrapper(mcp.server()),
            "失败滞留态：registeredWrappers 无条目而 SDK 条目仍指向缓存句柄");

        // Reload #2（服务恢复）：换芯清毒化 → clearServer 不得因 oldWrapper==null 透传 SDK removeMcpClient 误杀刚换芯的句柄
        var base3 = registrableBase("base3");
        registrar.bases.add(base3);
        assertTrue(registrar.rebuildSharedWrapper(mcp.server(), mcp));
        assertSame(base3, handle.delegate());
        assertFalse(handle.poisoned());

        assertFalse(registrar.clearServer(toolkit, mcp.server()), "滞留态无 tracked 条目，返回 false");
        assertFalse(handle.isClosed(), "clearServer 不得误杀刚换芯的共享句柄（SDK 条目滞留经 removeMcpClient close）");
        verify(base3, never()).close();

        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp), "重注册必须复用换芯后的健康句柄");
        assertSame(handle, registrar.sharedWrapper(mcp.server()));
        assertSame(base3, handle.delegate());
        assertFalse(((ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server())).poisoned());
    }

    @Test
    void sharedClientShouldEvictClosedNonPoisonedHandle() {
        var base1 = healthyBase("b1");
        registrar.bases.add(base1);
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        var dead = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(mcp.server());
        dead.close();

        var fresh = healthyBase("fresh");
        registrar.bases.add(fresh);
        var next = registrar.sharedClient(mcp);
        assertNotSame(dead, next, "closed 未毒化句柄不得作为活连接复用（守卫不得嵌套在 poisoned 内）");
        var handle = registrar.sharedWrapper(mcp.server());
        assertNotSame(dead, handle);
        assertInstanceOf(ReconnectableMcpClientWrapper.class, handle);
        assertSame(fresh, ((ReconnectableMcpClientWrapper) handle).delegate());
        assertFalse(((ReconnectableMcpClientWrapper) handle).poisoned());
    }

    @Test
    void syntheticPrevClosedShouldReplaceWithFreshBuild() {
        var synthetic = syntheticConfig();
        registrar.bases.add(healthyBase("ov1"));
        assertEquals(synthetic.server(), registrar.registerSyntheticClient(toolkit, synthetic, scopedFactory()));
        var dead = (ReconnectableMcpClientWrapper) registrar.sharedWrapper(synthetic.server());
        dead.close();

        var fresh = healthyBase("ov2");
        registrar.bases.add(fresh);
        assertEquals(synthetic.server(),
            registrar.registerSyntheticClient(new Toolkit(), synthetic, scopedFactory()),
            "closed 死句柄必须被新建连接顶替，不得复用");
        var handle = registrar.sharedWrapper(synthetic.server());
        assertNotSame(dead, handle, "缓存现任必须是新建句柄而非死 prev");
        var delegate = ((ReconnectableMcpClientWrapper) handle).delegate();
        assertInstanceOf(io.agentmanager.framework.mcp.UserScopedMcpClientWrapper.class, delegate);
        assertEquals("ov2", delegate.getName());
        verify(fresh, never()).close();
    }

    private void writeServerConfig(String content) throws Exception {
        var dir = tempDir.resolve(mcp.server());
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("config.yaml"), content);
    }

    private McpClientWrapper healthyBaseServing(String... toolNames) {
        var base = healthyBase("serving");
        var tools = java.util.Arrays.stream(toolNames)
            .map(name -> new McpSchema.Tool(name, "", "desc", null, null, null, null))
            .toList();
        when(base.listTools()).thenReturn(Mono.just(tools));
        return base;
    }

    private static final String RENDER_CARD = "mcp__platform-publisher__render_confirm_card";
    private static final String LOOKUP_ORDER = "mcp__platform-publisher__lookup_order";

    @Test
    void registerOneShouldTakeFilteredPathWhenDirectReturnDeclared() throws Exception {
        writeServerConfig("""
            direct_return:
              tools:
                - render_confirm_card
            """);
        registrar.bases.add(healthyBaseServing("render_confirm_card", "lookup_order"));

        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));

        assertTrue(toolkit.getTool(RENDER_CARD).isReturnDirect());
        assertFalse(toolkit.getTool(LOOKUP_ORDER).isReturnDirect());
        assertTrue(registrar.isDirectReturn(RENDER_CARD));
        assertFalse(registrar.isDirectReturn(LOOKUP_ORDER));
    }

    @Test
    void registerOneShouldKeepStandardPathWhenDirectReturnAbsent() throws Exception {
        writeServerConfig("""
            permissions:
              tools:
                render_confirm_card: allow
            """);
        registrar.bases.add(healthyBaseServing("render_confirm_card", "lookup_order"));

        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));

        assertFalse(toolkit.getTool(RENDER_CARD).isReturnDirect());
        assertFalse(registrar.isDirectReturn(RENDER_CARD));
    }

    @Test
    void reloadShouldFollowDirectReturnDeclarationChanges() throws Exception {
        writeServerConfig("""
            direct_return:
              tools:
                - render_confirm_card
            """);
        registrar.bases.add(healthyBaseServing("render_confirm_card"));
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));
        assertTrue(toolkit.getTool(RENDER_CARD).isReturnDirect());

        assertTrue(registrar.clearServer(toolkit, mcp.server()));
        writeServerConfig("permissions: {}\n");
        assertEquals(mcp.server(), registrar.registerOne(toolkit, mcp));

        assertFalse(toolkit.getTool(RENDER_CARD).isReturnDirect());
        assertFalse(registrar.isDirectReturn(RENDER_CARD));
    }
}
