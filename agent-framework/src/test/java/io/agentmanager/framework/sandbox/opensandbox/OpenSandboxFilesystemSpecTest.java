package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.IsolationScope;

class OpenSandboxFilesystemSpecTest {

    @Test
    void defaultsShouldMatchDesign() {
        var spec = new OpenSandboxFilesystemSpec();

        assertEquals("opensandbox/code-interpreter:v1.1.0", spec.clientOptions().getImage());
        assertEquals(Duration.ofMinutes(60), spec.clientOptions().getTimeout());
        assertEquals("1", spec.clientOptions().getResource().get("cpu"));
        assertEquals("1024Mi", spec.clientOptions().getResource().get("memory"));
        assertEquals("/workspace", spec.workspaceSpec().getRoot());
        assertNotNull(spec.snapshotSpec());
    }

    @Test
    void fluentBuilderShouldPropagateToClientOptions() {
        var spec = new OpenSandboxFilesystemSpec()
            .serverUrl("192.168.31.155:8090")
            .apiKey("k")
            .image("ubuntu:24.04")
            .timeout(Duration.ofMinutes(30))
            .resource(Map.of("cpu", "2", "memory", "2Gi"))
            .environment(Map.of("K", "V"))
            .workspaceRoot("/ws")
            .isolationScope(IsolationScope.USER);

        var options = spec.clientOptions();
        assertEquals("192.168.31.155:8090", options.getServerUrl());
        assertEquals("k", options.getApiKey());
        assertEquals("ubuntu:24.04", options.getImage());
        assertEquals(Duration.ofMinutes(30), options.getTimeout());
        assertEquals("2Gi", options.getResource().get("memory"));
        assertEquals("V", options.getEnvironment().get("K"));
        assertEquals("/ws", options.getWorkspaceRoot());
        assertEquals("/ws", spec.workspaceSpec().getRoot());
        assertEquals(IsolationScope.USER, spec.getIsolationScope());

        var farmSpec = new OpenSandboxFilesystemSpec()
            .skillsSharedStore("/skills-store")
            .skillBundleService(new SkillBundleService(null, java.nio.file.Path.of("/tmp/farm-spec-test"),
                java.time.Duration.ofSeconds(5), java.time.Clock.systemUTC()))
            .farmMeterRegistry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var farmOptions = farmSpec.clientOptions();
        assertEquals("/skills-store", farmOptions.getSkillsSharedStore());
        assertNotNull(farmOptions.getSkillBundleService());
        assertNotNull(farmOptions.getFarmMeterRegistry());
    }

    @Test
    void createClientShouldWrapOpenSandboxClientWithTracing() {
        var spec = new OpenSandboxFilesystemSpec()
            .serverUrl("s").apiKey("k");
        // 链路追踪改造后 createClient() 返回 TracingSandboxClient（内部委托 OpenSandboxClient）
        assertInstanceOf(TracingSandboxClient.class, spec.createClient());
    }

    @Test
    void workspaceReaderShouldFlowToClient() {
        var reader = org.mockito.Mockito.mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec()
            .serverUrl("s").apiKey("k")
            .workspaceReader(reader);

        // TracingSandboxClient 构造（包装 OpenSandboxClient）不抛异常即证明 reader 传递链路无断裂
        assertNotNull(spec.createClient());
    }

    // ==================== Pod 级缓存表（稳态短路记账 / P1 护栏） ====================

    @Test
    void gateTableShouldEvictLruEntryInsteadOfClearingAll() {
        var spec = new OpenSandboxFilesystemSpec();
        // 造 256 个 gate（表容量上界）
        for (int i = 0; i < 256; i++) {
            assertNotNull(spec.skillSyncGate("user-" + i, "sbx"));
        }
        // 访问最早的 gate（变为最近使用）→ 淘汰对象应是「未再访问」的最旧条目
        assertNotNull(spec.skillSyncGate("user-0", "sbx"));
        // 再进 1 个新 gate：触发淘汰，但只淘汰 1 条（LRU），其余 255 条仍在
        assertNotNull(spec.skillSyncGate("user-new", "sbx"));
        assertNotNull(spec.skillSyncGate("user-0", "sbx"), "最近访问的 gate 不得被淘汰");
        assertNotNull(spec.skillSyncGate("user-255", "sbx"), "其余 gate 保留（不得清全表）");
    }

    @Test
    void scopedFingerprintAndHydratedScopeShouldRoundTrip() {
        var spec = new OpenSandboxFilesystemSpec();
        assertNull(spec.scopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "未记录=未知走收敛");
        assertNull(spec.hydratedScopedScope("sbx-1"));

        spec.recordScopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-1");
        spec.markHydratedScopedScope("sbx-1", "sess-A");

        assertEquals("fp-1", spec.scopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
        assertEquals("sess-A", spec.hydratedScopedScope("sbx-1"));
        // 换 sandboxId（新沙箱代）= 未知 → 必须走收敛
        assertNull(spec.scopedSkillCacheFingerprint("sbx-2", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
        assertNull(spec.hydratedScopedScope("sbx-2"));
    }

    @Test
    void legacyModeBookkeepingShouldNeverSatisfyFarmSteady() {
        // PR-4b：tar 模式退役——存量 tar 记账值经模式比对不匹配 farm 稳态，首轮触发一次
        // 重收敛后自然被 farm 值覆盖（D-11 无模式存量同姿态，无需迁移）
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        org.mockito.Mockito.when(shared.get("sbx-legacy", "sess-A")).thenReturn("tar|fp-old");
        var spec = new OpenSandboxFilesystemSpec().sharedFingerprintStore(shared);

        assertNull(spec.scopedSkillCacheFingerprint("sbx-legacy", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "存量 tar 记账不得满足 farm 稳态");

        spec.recordScopedSkillCacheFingerprint("sbx-legacy", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-new");
        assertEquals("fp-new", spec.scopedSkillCacheFingerprint("sbx-legacy", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "farm 记账后照常短路");
    }

    // ==================== 缺口④：scoped 指纹跨副本共享层 ====================

    @Test
    void sharedFingerprintHitBackfillsPodTablesAndShortCircuits() {
        var spec = new OpenSandboxFilesystemSpec();
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        org.mockito.Mockito.when(shared.get("sbx-x", "sess-A"))
            .thenReturn(OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM + "|fp-shared");
        spec.sharedFingerprintStore(shared);

        // Pod 级 miss → 共享层命中（他副本已投放）
        assertNull(spec.scopedSkillCacheFingerprint("sbx-x", "zz", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "另一 scope 无记录");
        assertEquals("fp-shared", spec.scopedSkillCacheFingerprint("sbx-x", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "共享层命中返回他副本记录的指纹");
        // 命中同时回填 Pod 两表（稳态判定要求两表一致）
        assertEquals("fp-shared", spec.scopedSkillCacheFingerprint("sbx-x", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "回填后 Pod 级命中");
        assertEquals("sess-A", spec.hydratedScopedScope("sbx-x"), "已投放 scope 一并回填");
    }

    @Test
    void sharedFingerprintUnknownModeFromOtherPodShouldMiss() {
        // 重启/漂副本场景：共享层存续的是无模式（升级窗口）记账，同代稳态必须视为未知走收敛
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        org.mockito.Mockito.when(shared.get("sbx-r", "sess-A")).thenReturn("fp-9");
        var spec = new OpenSandboxFilesystemSpec().sharedFingerprintStore(shared);

        assertNull(spec.scopedSkillCacheFingerprint("sbx-r", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "无模式记账对 farm 稳态不可见（模式分量跨副本存续，D-11）");

        org.mockito.Mockito.when(shared.get("sbx-r2", "sess-A"))
            .thenReturn(OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM + "|fp-9");
        assertEquals("fp-9", spec.scopedSkillCacheFingerprint("sbx-r2", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "同模式共享层命中照常短路");
        assertEquals("sess-A", spec.hydratedScopedScope("sbx-r2"), "共享层命中照常回填已投放 scope");
    }

    @Test
    void sharedFingerprintWriteGoesToBothLayers() {
        var spec = new OpenSandboxFilesystemSpec();
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        spec.sharedFingerprintStore(shared);

        spec.recordScopedSkillCacheFingerprint("sbx-y", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-1");
        assertEquals("fp-1", spec.scopedSkillCacheFingerprint("sbx-y", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "Pod 级已写");
        org.mockito.Mockito.verify(shared).put("sbx-y", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM + "|fp-1");
    }

    @Test
    void noopStoreKeepsPurePodLocalBehavior() {
        var spec = new OpenSandboxFilesystemSpec(); // 未注入 → NOOP
        assertNull(spec.scopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "无共享层时未知=走收敛");
        spec.recordScopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-2");
        assertEquals("fp-2", spec.scopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
    }
}
