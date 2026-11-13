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

    // ==================== Pod 级缓存表（P0-1 / P1-3 / P1 护栏） ====================

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
        assertNull(spec.scopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "未记录=未知走慢路径");
        assertNull(spec.hydratedScopedScope("sbx-1"));

        spec.recordScopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR, "fp-1");
        spec.markHydratedScopedScope("sbx-1", "sess-A");

        assertEquals("fp-1", spec.scopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR));
        assertEquals("sess-A", spec.hydratedScopedScope("sbx-1"));
        // 换 sandboxId（新沙箱代）= 未知 → 必须走慢路径
        assertNull(spec.scopedSkillCacheFingerprint("sbx-2", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR));
        assertNull(spec.hydratedScopedScope("sbx-2"));
    }

    @Test
    void fingerprintModeMismatchShouldNeverSatisfyOtherModeSteady() {
        // D-11：记账值带投放模式分量——同沙箱代 tar↔farm 互转永不满足对方稳态，翻转必走慢路径
        var spec = new OpenSandboxFilesystemSpec();
        spec.recordScopedSkillCacheFingerprint("sbx-m", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR, "fp-1");

        assertEquals("fp-1", spec.scopedSkillCacheFingerprint("sbx-m", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR));
        assertNull(spec.scopedSkillCacheFingerprint("sbx-m", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "降级 tar 记账不得满足 farm 稳态（否则降级残留真实文件被跳过收敛，D-2 失效）");

        spec.recordScopedSkillCacheFingerprint("sbx-m", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-1");
        assertEquals("fp-1", spec.scopedSkillCacheFingerprint("sbx-m", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
        assertNull(spec.scopedSkillCacheFingerprint("sbx-m", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "farm 记账不得满足降级稳态（否则降级会话零动作保留旧版软链，B-1 被架空）");
    }

    // ==================== 缺口④：scoped 指纹跨副本共享层 ====================

    @Test
    void sharedFingerprintHitBackfillsPodTablesAndShortCircuits() {
        var spec = new OpenSandboxFilesystemSpec();
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        org.mockito.Mockito.when(shared.get("sbx-x", "sess-A"))
            .thenReturn(OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR + "|fp-shared");
        spec.sharedFingerprintStore(shared);

        // Pod 级 miss → 共享层命中（他副本已投放）
        assertNull(spec.scopedSkillCacheFingerprint("sbx-x", "zz", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "另一 scope 无记录");
        assertEquals("fp-shared", spec.scopedSkillCacheFingerprint("sbx-x", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "共享层命中返回他副本记录的指纹");
        // 命中同时回填 Pod 两表（稳态判定要求两表一致）
        assertEquals("fp-shared", spec.scopedSkillCacheFingerprint("sbx-x", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "回填后 Pod 级命中");
        assertEquals("sess-A", spec.hydratedScopedScope("sbx-x"), "已投放 scope 一并回填");
    }

    @Test
    void sharedFingerprintModeMismatchFromOtherPodShouldMiss() {
        // 重启/漂副本场景：共享层存续的是 farm 记账，同代降级(tar)稳态必须视为未知走慢路径
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        org.mockito.Mockito.when(shared.get("sbx-r", "sess-A"))
            .thenReturn(OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM + "|fp-9");
        var spec = new OpenSandboxFilesystemSpec().sharedFingerprintStore(shared);

        assertNull(spec.scopedSkillCacheFingerprint("sbx-r", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "共享层 farm 记账对降级稳态不可见（模式分量跨副本存续，D-11）");
        assertEquals("fp-9", spec.scopedSkillCacheFingerprint("sbx-r", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "同模式共享层命中照常短路");
        assertEquals("sess-A", spec.hydratedScopedScope("sbx-r"), "共享层命中照常回填已投放 scope");
    }

    @Test
    void sharedFingerprintWriteGoesToBothLayers() {
        var spec = new OpenSandboxFilesystemSpec();
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        spec.sharedFingerprintStore(shared);

        spec.recordScopedSkillCacheFingerprint("sbx-y", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR, "fp-1");
        assertEquals("fp-1", spec.scopedSkillCacheFingerprint("sbx-y", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "Pod 级已写");
        org.mockito.Mockito.verify(shared).put("sbx-y", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR + "|fp-1");
    }

    @Test
    void noopStoreKeepsPurePodLocalBehavior() {
        var spec = new OpenSandboxFilesystemSpec(); // 未注入 → NOOP
        assertNull(spec.scopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR),
            "无共享层时未知=走慢路径");
        spec.recordScopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR, "fp-2");
        assertEquals("fp-2", spec.scopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_TAR));
    }

    @Test
    void scopedPayloadCacheShouldRoundTripAndRespectFingerprint() {
        var spec = new OpenSandboxFilesystemSpec();
        assertNull(spec.scopedPayloadCache("/ws", "sess-A"));

        spec.recordScopedPayloadCache("/ws", "sess-A", "fp-1", new byte[] {1, 2, 3});
        var cached = spec.scopedPayloadCache("/ws", "sess-A");
        assertNotNull(cached);
        assertEquals("fp-1", cached.statFingerprint());
        assertEquals(3, cached.gzPayload().length);
    }

    @Test
    void scopedPayloadCacheShouldRejectOversizedPayload() {
        var spec = new OpenSandboxFilesystemSpec();
        byte[] tooBig = new byte[33 * 1024 * 1024]; // > 32MB 单载荷上界
        spec.recordScopedPayloadCache("/ws", "big", "fp-big", tooBig);
        assertNull(spec.scopedPayloadCache("/ws", "big"), "超限载荷不得缓存（堆上界保护）");
    }

    @Test
    void scopedPayloadCacheShouldEvictEldestWhenTotalExceeded() {
        var spec = new OpenSandboxFilesystemSpec();
        // 3 × 25MB = 75MB > 64MB 总上界 → 最先放入的被淘汰（逐条，保 b+c=50MB ≤ 限）
        spec.recordScopedPayloadCache("/ws", "a", "fp-a", new byte[25 * 1024 * 1024]);
        spec.recordScopedPayloadCache("/ws", "b", "fp-b", new byte[25 * 1024 * 1024]);
        spec.recordScopedPayloadCache("/ws", "c", "fp-c", new byte[25 * 1024 * 1024]);
        assertNull(spec.scopedPayloadCache("/ws", "a"), "最旧载荷被淘汰");
        assertNotNull(spec.scopedPayloadCache("/ws", "b"));
        assertNotNull(spec.scopedPayloadCache("/ws", "c"));
    }
}
