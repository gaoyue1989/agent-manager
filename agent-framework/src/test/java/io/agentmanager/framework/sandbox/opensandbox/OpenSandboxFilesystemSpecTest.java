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
    void skillSyncSwitchesShouldPropagateToClientOptions() {
        var spec = new OpenSandboxFilesystemSpec()
            .skillRevisionGateEnabled(false)
            .syncSnapshotDiffEnabled(false);

        assertFalse(spec.clientOptions().isSkillRevisionGateEnabled());
        assertFalse(spec.clientOptions().isSyncSnapshotDiffEnabled());
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

    // ==================== 注入换代索引（会话键，issue #413） ====================

    @Test
    void injectedGenerationIndexShouldKeyBySessionNotUser() {
        // 键=会话键（与 file_asset.session_id 同命名空间，issue #413）：同用户两个会话各自
        // 持有「已执行过 reset 的沙箱代」索引，互不翻转——会话 A 的换代状态不因会话 B
        // mark 而丢失，A 重入不误判换代（B-13b）
        var spec = new OpenSandboxFilesystemSpec();

        assertFalse(spec.isInjectedOnSandbox("sess-a", "sbx-a"), "未记录=未换代");
        spec.markInjectedOnSandbox("sess-a", "sbx-a");
        spec.markInjectedOnSandbox("sess-b", "sbx-b");

        assertTrue(spec.isInjectedOnSandbox("sess-a", "sbx-a"), "会话 A 的换代索引被会话 B 翻转（必须按会话键独立记账）");
        assertTrue(spec.isInjectedOnSandbox("sess-b", "sbx-b"));
        assertFalse(spec.isInjectedOnSandbox("sess-a", "sbx-b"), "同会话换新沙箱代=未换代");
        assertFalse(spec.isInjectedOnSandbox("sess-c", "sbx-a"), "第三会话独立");
    }

    @Test
    void sharedResetMarkHitShouldBackfillPodIndexAndSkipRecheck() {
        // 评审 g1（issue #413）：换代索引 Pod 级 miss（跨副本轮转/Pod 重启/LRU 淘汰）→
        // 共享层命中 = 该 (会话, 沙箱代) 已 reset → 判定换代，命中回填 Pod 级索引
        // （此后共享层不可用仍维持稳态判定，不再逐 turn 查 Redis）
        var shared = org.mockito.Mockito.mock(ResetMarkStore.class);
        org.mockito.Mockito.when(shared.marked("sess-a", "sbx-1")).thenReturn(true);
        var spec = new OpenSandboxFilesystemSpec().resetMarkStore(shared);

        assertTrue(spec.isInjectedOnSandbox("sess-a", "sbx-1"), "共享层命中=该代已 reset（跨副本/重启不丢）");
        assertTrue(spec.isInjectedOnSandbox("sess-a", "sbx-1"), "命中回填 Pod 级索引");
        org.mockito.Mockito.verify(shared, org.mockito.Mockito.times(1)).marked("sess-a", "sbx-1");
        assertFalse(spec.isInjectedOnSandbox("sess-b", "sbx-1"), "未记账对=未知");
        org.mockito.Mockito.verify(shared).marked("sess-b", "sbx-1");
    }

    @Test
    void markInjectedOnSandboxShouldRecordSharedMark() {
        // reset 决策落定即双写（Pod 级 + 共享层）：他副本/重启后凭共享层跳过同代重复 reset
        var shared = org.mockito.Mockito.mock(ResetMarkStore.class);
        var spec = new OpenSandboxFilesystemSpec().resetMarkStore(shared);

        spec.markInjectedOnSandbox("sess-a", "sbx-1");

        assertTrue(spec.isInjectedOnSandbox("sess-a", "sbx-1"));
        org.mockito.Mockito.verify(shared).mark("sess-a", "sbx-1");
    }

    @Test
    void noopResetMarkStoreKeepsPurePodLocalBehavior() {
        // 未装配共享层（无 Redis）：行为回到纯 Pod 级（与 ScopedFingerprintStore.NOOP 同姿态）
        var spec = new OpenSandboxFilesystemSpec();

        assertFalse(spec.isInjectedOnSandbox("sess-a", "sbx-a"));
        spec.markInjectedOnSandbox("sess-a", "sbx-a");
        assertTrue(spec.isInjectedOnSandbox("sess-a", "sbx-a"));
        assertFalse(spec.isInjectedOnSandbox("sess-b", "sbx-a"));
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
    void podLocalStaleHitShouldBeInvalidatedWhenSlotScopeDiffers() {
        // issue #393 评审 g1（Pod 级表向量）：稳态两操作数均为本 Pod 信念，他副本同代
        // prune+切 scope 不会失效它们——Pod 级命中也须过单槽 scope 交叉核验，槽现役 scope
        // 权威否定陈旧记录（清除两表返回 null 重收敛）
        var spec = new OpenSandboxFilesystemSpec();
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        spec.sharedFingerprintStore(shared);
        spec.recordScopedSkillCacheFingerprint("sbx-g1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-a");
        org.mockito.Mockito.when(shared.slotEntry("sbx-g1")).thenReturn(new ScopedFingerprintStore.SlotEntry("sess-B", "farm|fp-b"));

        assertNull(spec.scopedSkillCacheFingerprint("sbx-g1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "槽现役 scope=B ≠ 请求 scope=A：本 Pod 陈旧记录必须被权威否定");
        assertNull(spec.hydratedScopedScope("sbx-g1"), "陈旧已投放 scope 记录一并清除");
        assertNull(spec.scopedSkillCacheFingerprint("sbx-g1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "清除生效不得复活（miss 路径共享 get 无记录）");
    }

    @Test
    void podLocalHitShouldStayWhenSlotScopeMatchesOrUnknown() {
        var spec = new OpenSandboxFilesystemSpec();
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        spec.sharedFingerprintStore(shared);
        spec.recordScopedSkillCacheFingerprint("sbx-g2", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-a");
        org.mockito.Mockito.when(shared.slotEntry("sbx-g2")).thenReturn(new ScopedFingerprintStore.SlotEntry("sess-A", "farm|fp-a"));

        assertEquals("fp-a", spec.scopedSkillCacheFingerprint("sbx-g2", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "槽现役 scope 一致=本 Pod 记录仍可信，稳态短路保持");

        var specUnknown = new OpenSandboxFilesystemSpec();
        var sharedUnknown = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        specUnknown.sharedFingerprintStore(sharedUnknown);
        specUnknown.recordScopedSkillCacheFingerprint("sbx-g3", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-a");

        assertEquals("fp-a", specUnknown.scopedSkillCacheFingerprint("sbx-g3", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "槽缺失/Redis 不可达（slotScope=null）=未知，fail-open 维持本 Pod 信念不清本地");
    }

    @Test
    void noopStoreKeepsPurePodLocalBehavior() {
        var spec = new OpenSandboxFilesystemSpec(); // 未注入 → NOOP
        assertNull(spec.scopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "无共享层时未知=走收敛");
        spec.recordScopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-2");
        assertEquals("fp-2", spec.scopedSkillCacheFingerprint("sbx-z", "sess-C", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
    }

    // ==================== issue #481：ERR- prune 的跨副本失效广播 ====================

    /** 内存 map 假 Redis：真 ScopedFingerprintStore 落共享 HashMap（模拟跨副本共享槽） */
    @SuppressWarnings("unchecked")
    private static ScopedFingerprintStore sharedStoreOver(java.util.Map<String, String> data) {
        var sync = org.mockito.Mockito.mock(io.lettuce.core.cluster.api.sync.RedisClusterCommands.class);
        org.mockito.Mockito.when(sync.get(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> data.get((String) inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            data.put((String) inv.getArgument(0), (String) inv.getArgument(2));
            return "OK";
        }).when(sync).setex(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString());
        var facade = org.mockito.Mockito.mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        org.mockito.Mockito.when(facade.sync()).thenReturn(sync);
        org.mockito.Mockito.when(facade.key(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        return new ScopedFingerprintStore(facade);
    }

    @Test
    void pruneShouldBroadcastTombstoneEvenWhenExecutorHasNoLocalStaleEntries() {
        // issue #481（复现 01a/01b）：ERR- prune 执行副本（Pod2）从未收敛过被删 scope →
        // 本地 stale 表为空，共享槽残留记账副本（Pod1）写入的记录；修复前即便 remove DEL
        // 了槽，其他副本也把槽缺失 fail-open 混同为不可达而维持陈旧信念。修复 = markPruned
        // 无条件把单槽覆写为 keepScope 墓碑：现役 scope 段权威否定所有副本陈旧记录 + 墓碑
        // 模式段不匹配 farm 使 keepScope 自身也必须重收敛
        var data = new java.util.HashMap<String, String>();
        var shared = sharedStoreOver(data);
        var pod1 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(shared);
        pod1.recordScopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-a");
        pod1.markHydratedScopedScope("sbx-1", "sess-A");

        var pod2 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(shared);
        pod2.pruneScopedSkillCacheFingerprintsExcept("sbx-1", "sess-B");

        assertEquals("sess-B", shared.slotScope("sbx-1"),
            "非记账副本 prune 也必须把现役 scope 广播到共享槽（不依赖本 Pod 本地记账遍历）");
        assertNull(pod1.scopedSkillCacheFingerprint("sbx-1", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "墓碑权威否定记账副本陈旧信念（修复前返回 fp-a 稳态锁死）");
        assertNull(pod1.hydratedScopedScope("sbx-1"), "陈旧现役 scope 记录一并清除");
        assertNull(pod2.scopedSkillCacheFingerprint("sbx-1", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "keepScope 墓碑模式段不匹配 farm：端点恢复后 keepScope 自身也必须重收敛");
    }

    @Test
    void pruneShouldKeepKeptScopeLocalBookkeepingAndRouteThroughMarkPruned() {
        // 收敛周期内 record 先行写槽（SkillsFarm：record → pruneExcept）：执行副本自己收敛过
        // keepScope 时槽正常记账 = 容器实态（pruneCmd 保留 keepScope 目录），markPruned 保留
        // 不覆写——稳态短路不被墓碑击穿；本地 keepScope 记账与现役 scope 记录同步保留
        var shared = org.mockito.Mockito.mock(ScopedFingerprintStore.class);
        var pod = new OpenSandboxFilesystemSpec().sharedFingerprintStore(shared);
        pod.recordScopedSkillCacheFingerprint("sbx-k", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "fp-b");
        pod.markHydratedScopedScope("sbx-k", "sess-B");

        pod.pruneScopedSkillCacheFingerprintsExcept("sbx-k", "sess-B");

        org.mockito.Mockito.verify(shared).markPruned("sbx-k", "sess-B");
        assertEquals("fp-b", pod.scopedSkillCacheFingerprint("sbx-k", "sess-B", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "keepScope 本 Pod 记账保留");
        assertEquals("sess-B", pod.hydratedScopedScope("sbx-k"), "keepScope 现役 scope 记录保留");
    }
}
