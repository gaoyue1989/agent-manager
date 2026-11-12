package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;

/**
 * OpenSandbox 文件系统配置：用于 HarnessAgent.Builder.filesystem()。
 * 由 AgentScopeConfig 在 SANDBOX_ENABLED=true 时装配。
 */
public class OpenSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private String serverUrl;
    private String apiKey;
    private String image = "opensandbox/code-interpreter:v1.1.0";
    private Duration timeout = Duration.ofMinutes(60);
    private List<String> entrypoint = List.of("/opt/code-interpreter/code-interpreter.sh");
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";
    private WorkspaceReader workspaceReader;
    private WorkspaceSyncService workspaceSyncService;
    /** 技能禁用判定（oa-1110-repro-fixes-design §2.1a）：scoped .skills-cache 投放过滤；null = 不过滤 */
    private SkillDisabledChecker skillDisabledChecker;
    /** 建箱/管理面 HTTP 请求超时（oa-1110-repro-fixes-design §2.3b）；null = 走 SDK 默认 */
    private java.time.Duration requestTimeout;
    private SandboxUserKeyMiddleware userKeyMiddleware;
    private io.agentmanager.framework.service.FileAssetStore fileAssetStore;
    private io.agentmanager.framework.service.storage.FileStorage fileStorage;
    /** 业务规范键解析器（OpenSandbox.resolveUserKey 与 middleware 同源反查真实用户，issue #52；可空=退化为旧行为） */
    private io.agentmanager.framework.service.SessionKeyResolver userKeyResolver;
    /** 软链农场开关（skills-syncd §6.4）：沙箱内共享库路径，空 = 农场关闭走现行为 */
    private String skillsSharedStore;
    /** 端点指纹服务（skills-syncd §7.2 版本硬校验数据源，D-4 短 TTL）；null = 端点不可达 */
    private SkillBundleService skillBundleService;
    /** 农场判定打点（D-5）；null = 不打点 */
    private volatile io.micrometer.core.instrument.MeterRegistry farmMeterRegistry;

    /** 请求级 userId 传递：SandboxUserKeyMiddleware.onAgent 设置，OpenSandboxClient.create/resume 读取 */
    private final ThreadLocal<String> pendingUserKey = new ThreadLocal<>();

    /**
     * 请求级会话/身份键传递（sandbox-pool-opensandbox-design §4.2）：守卫 {@code tryEnter}
     * 同线程覆盖写（唯一落在 acquire 正确线程的注入点，PR #276 injectPoolIdentity 同款验证）、
     * SandboxUserKeyMiddleware 尽力兜底，OpenSandboxClient.create/resume 读取后即清。
     * 语义：chat 链路 = 前端 sid（=turn_lease 键）；A2A/invoke 链路 = 真实用户
     * （非 turn_lease 键，busy 判定回退纯租约+updated_at 双事实源，正确性不受影响）。
     */
    private final ThreadLocal<String> pendingSessionId = new ThreadLocal<>();

    /** 请求级技能缓存 scope 传递：SandboxUserKeyMiddleware.onAgent 设置，OpenSandboxClient.create/resume 读取并绑定到沙箱实例 */
    private final ThreadLocal<String> pendingSkillScope = new ThreadLocal<>();

    /**
     * 沙箱生命周期管理器（§4.2/§4.9，lifecycle 开启时由 AgentScopeConfig 装配注入；
     * null = 生命周期旁路）。stop() 经此回调 per-call 收尾（refcount-1 + 终刷）。
     */
    private volatile SandboxLifecycleManager lifecycleManager;

    /** userKey → 已注入沙箱 id（reset 仅在新沙箱代执行，防多实例循环 reset/inject）；LRU 有界 */
    private final Map<String, String> userInjectedSandbox = lruMap(256);

    /**
     * 会话水合门控表（pod 本地缓存，真相在 KV/容器）：userKey|sandboxId → 已知同步状态。
     * ③ revision 门控（物化跳过依据）与 ②′ 快照差分（回写比对基准）共用——见
     * {@link SkillSyncGate}。LRU 有界（超限逐条淘汰最久未用项，<b>不清全表</b>——
     * 清全表会让全部活跃会话下一 call 回落全量物化/全量回读，多会话并发下形成风暴）。
     */
    private final Map<String, SkillSyncGate> skillSyncGates = lruMap(256);

    /**
     * scoped 技能缓存 stat 指纹（pod 本地，P0-1）：sandboxId|scope → 最近一次投放时的
     * 宿主 scope 目录 stat 指纹。跨 call / 跨 OpenSandbox 包装实例有效——指纹未变即跳过
     * build+prune+hydrate（原 lastSkillCacheHash 是实例字段，实例每 call 重建导致每轮全量重投）。
     * Pod 级 miss 时回落 {@link #sharedFingerprintStore}（跨副本共享，缺口④）。
     */
    private final Map<String, String> scopedSkillCacheFingerprints = lruMap(256);
    /** 指纹跨副本共享层（Redis，fail-open；NOOP=未装配，行为回到纯 Pod 级） */
    private volatile ScopedFingerprintStore sharedFingerprintStore = ScopedFingerprintStore.NOOP;

    /** 包内技能源目录（configDir/skills，生产=PVC 只读同挂载；跨副本指纹一致性的来源，缺口④） */
    private volatile java.nio.file.Path packageSkillsDir;

    /** 注入包内技能源目录（scoped 指纹源；null=未装配，指纹退化为仅 L4 revision） */
    public OpenSandboxFilesystemSpec packageSkillsDir(java.nio.file.Path dir) {
        this.packageSkillsDir = dir;
        return this;
    }

    /** 包内技能源目录（可空） */
    public java.nio.file.Path packageSkillsDir() {
        return packageSkillsDir;
    }

    /** 注入跨副本指纹共享层（AgentScopeConfig 装配时调用；null 归一 NOOP） */
    public OpenSandboxFilesystemSpec sharedFingerprintStore(ScopedFingerprintStore store) {
        this.sharedFingerprintStore = store != null ? store : ScopedFingerprintStore.NOOP;
        return this;
    }

    /**
     * sandboxId → 当前已投放进容器的 scope（P0-1）：同代容器换会话（池化借出复用/同用户
     * 双会话）时指纹命中但 scope 不同，必须走慢路径 prune 旧 scope（跨用户残留防护）再投放。
     */
    private final Map<String, String> hydratedScopedScopes = lruMap(256);

    /**
     * scoped 投放载荷缓存（pod 本地，P1-3）：sourceRoot|scope → {指纹, gz 字节}。
     * 慢路径（换沙箱代/换 scope）复用同内容 gz，省宿主 build+gzip（实测 66MB/2000f ≈1.8s）。
     * 字节有界：单载荷 ≤32MB 且总量 ≤64MB，超限不缓存（回落每次现算，正确性不变）。
     */
    private final Map<String, ScopedPayload> scopedPayloadCache = lruMap(8);
    private static final int MAX_SCOPED_PAYLOAD_BYTES = 32 * 1024 * 1024;
    private static final long MAX_SCOPED_PAYLOAD_TOTAL_BYTES = 64L * 1024 * 1024;

    /** scoped 载荷缓存条目 */
    record ScopedPayload(String statFingerprint, byte[] gzPayload) {}

    /** LRU 有界 map（accessOrder，超限逐条淘汰最久未访问项） */
    private static <K, V> Map<K, V> lruMap(int maxSize) {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        });
    }

    /** 读取（无则建空 gate）；键 = userKey + '|' + sandboxId */
    public SkillSyncGate skillSyncGate(String userKey, String sandboxId) {
        if (userKey == null || userKey.isBlank() || sandboxId == null || sandboxId.isBlank()) {
            return null;
        }
        var key = userKey + "|" + sandboxId;
        synchronized (skillSyncGates) {
            var gate = skillSyncGates.get(key);
            if (gate == null) {
                gate = new SkillSyncGate();
                skillSyncGates.put(key, gate);
            }
            return gate;
        }
    }

    /** 移除某会话的 gate（沙箱代销毁/测试重置用；null 安全） */
    public void removeSkillSyncGate(String userKey, String sandboxId) {
        if (userKey != null && sandboxId != null) {
            skillSyncGates.remove(userKey + "|" + sandboxId);
        }
    }

    /** scoped 缓存指纹读取（P0-1 短路比对值）：Pod 级 miss 时查跨副本共享层（缺口④），均无返回 null=走慢路径 */
    public String scopedSkillCacheFingerprint(String sandboxId, String scope) {
        if (sandboxId == null || sandboxId.isBlank() || scope == null || scope.isBlank()) {
            return null;
        }
        var podLocal = scopedSkillCacheFingerprints.get(sandboxId + "|" + scope);
        if (podLocal != null) {
            return podLocal;
        }
        // 跨副本：他副本可能已投放过同一沙箱代同 scope（同会话轮转）——共享层命中即免整套慢路径。
        // 命中同时回填 Pod 级两张表（指纹 + 已投放 scope）：稳态判定要求两表一致命中
        var shared = sharedFingerprintStore.get(sandboxId, scope);
        if (shared != null) {
            scopedSkillCacheFingerprints.put(sandboxId + "|" + scope, shared);
            hydratedScopedScopes.put(sandboxId, scope);
        }
        return shared;
    }

    /** scoped 缓存指纹回填（投放成功后记录）：Pod 级 + 共享层双写（共享层失败仅告警） */
    public void recordScopedSkillCacheFingerprint(String sandboxId, String scope, String statFingerprint) {
        if (sandboxId != null && scope != null && statFingerprint != null) {
            scopedSkillCacheFingerprints.put(sandboxId + "|" + scope, statFingerprint);
            sharedFingerprintStore.put(sandboxId, scope, statFingerprint);
        }
    }

    /** 本沙箱代当前已投放的 scope（null = 本代尚未投放过任何 scope；跨副本经共享指纹层兜底，无需共享 scope 表） */
    public String hydratedScopedScope(String sandboxId) {
        return sandboxId == null ? null : hydratedScopedScopes.get(sandboxId);
    }

    /** 记录本沙箱代已投放的 scope（投放成功后调用） */
    public void markHydratedScopedScope(String sandboxId, String scope) {
        if (sandboxId != null && scope != null) {
            hydratedScopedScopes.put(sandboxId, scope);
        }
    }

    /** scoped 载荷缓存读取（P1-3；指纹一致才可复用，由调用方比对） */
    public ScopedPayload scopedPayloadCache(String sourceRoot, String scope) {
        if (sourceRoot == null || sourceRoot.isBlank() || scope == null || scope.isBlank()) {
            return null;
        }
        return scopedPayloadCache.get(sourceRoot + "|" + scope);
    }

    /**
     * scoped 载荷回填（构建成功后调用）：单载荷超 {@link #MAX_SCOPED_PAYLOAD_BYTES} 或
     * 总量超 {@link #MAX_SCOPED_PAYLOAD_TOTAL_BYTES} 时不缓存（LRU 淘汰保证总量上界）。
     */
    public void recordScopedPayloadCache(String sourceRoot, String scope, String statFingerprint, byte[] gzPayload) {
        if (sourceRoot == null || sourceRoot.isBlank() || scope == null || scope.isBlank()
                || statFingerprint == null || gzPayload == null
                || gzPayload.length > MAX_SCOPED_PAYLOAD_BYTES) {
            return;
        }
        synchronized (scopedPayloadCache) {
            long total = scopedPayloadCache.values().stream()
                .mapToLong(p -> p.gzPayload().length).sum();
            while (!scopedPayloadCache.isEmpty()
                    && total + gzPayload.length > MAX_SCOPED_PAYLOAD_TOTAL_BYTES) {
                var eldest = scopedPayloadCache.keySet().iterator().next();
                total -= scopedPayloadCache.remove(eldest).gzPayload().length;
            }
            scopedPayloadCache.put(sourceRoot + "|" + scope, new ScopedPayload(statFingerprint, gzPayload));
        }
    }

    /** 该用户当前是否已在新沙箱代 reset+inject 过 */
    public boolean isInjectedOnSandbox(String userKey, String sandboxId) {
        return sandboxId != null && sandboxId.equals(userInjectedSandbox.get(userKey));
    }

    /** 记录该用户已注入的沙箱 id */
    public void markInjectedOnSandbox(String userKey, String sandboxId) {
        if (userKey != null && sandboxId != null) {
            userInjectedSandbox.put(userKey, sandboxId);
        }
    }

    /** 上传文件元数据存储（middleware 注入回滚状态用） */
    public io.agentmanager.framework.service.FileAssetStore getFileAssetStore() {
        return fileAssetStore;
    }

    @Override
    protected SandboxClient<?> createClient() {
        // 包装 TracingSandboxClient：沙箱 create/resume/delete 操作创建 OTel span
        // （sandbox 操作在 middleware 链外执行，OtelTracingMiddleware 无法覆盖）。
        // OTEL_TRACES_EXPORTER=none 时 GlobalOpenTelemetry 返回 no-op tracer，零开销。
        return new TracingSandboxClient(
            new OpenSandboxClient(clientOptions(), workspaceReader, workspaceSyncService, this,
                fileAssetStore, fileStorage));
    }

    /** userId 注入 middleware（AgentScopeConfig 注册到 HarnessAgent.Builder.middleware） */
    public SandboxUserKeyMiddleware getUserKeyMiddleware() {
        return userKeyMiddleware;
    }

    public OpenSandboxFilesystemSpec setUserKeyMiddleware(SandboxUserKeyMiddleware middleware) {
        this.userKeyMiddleware = middleware;
        return this;
    }

    /** 规范键解析器（Channel 链路 ctx.userId 是网关 peer，userKey 须经 session_user 反查真实用户） */
    public io.agentmanager.framework.service.SessionKeyResolver getSessionKeyResolver() {
        return userKeyResolver;
    }

    public OpenSandboxFilesystemSpec setSessionKeyResolver(io.agentmanager.framework.service.SessionKeyResolver resolver) {
        this.userKeyResolver = resolver;
        return this;
    }

    /**
     * 设置待绑定的用户 key（由 SandboxUserKeyMiddleware 在 agent 调用链上调用，
     * 与 acquire 在同一订阅线程顺序执行，ThreadLocal 天然按请求隔离）。
     */
    public void setPendingUserKey(String userKey) {
        pendingUserKey.set(userKey);
    }

    /** 读取并清除待绑定用户 key（OpenSandboxClient.create/resume 时调用） */
    public String takePendingUserKey() {
        var key = pendingUserKey.get();
        pendingUserKey.remove();
        return key;
    }

    /** 清除待绑定 key（SandboxUserKeyMiddleware 消费后调用；reactor 线程池复用，残留会污染下个 turn 的 create） */
    public void clearPendingUserKey() {
        pendingUserKey.remove();
    }

    /** 读取但不清除待绑定用户 key（注入兜底：doExec 时 middleware 已设置） */
    public String peekPendingUserKey() {
        var key = pendingUserKey.get();
        return (key == null || key.isBlank()) ? null : key;
    }

    /** 设置待绑定的技能缓存 scope（SandboxUserKeyMiddleware.onAgent，与 pendingUserKey 同链设置） */
    public void setPendingSkillScope(String scope) {
        pendingSkillScope.set(scope);
    }

    /** 读取并清除待绑定技能缓存 scope（OpenSandboxClient.create/resume 时调用） */
    public String takePendingSkillScope() {
        var scope = pendingSkillScope.get();
        pendingSkillScope.remove();
        return scope;
    }

    /** 清除待绑定 scope（SandboxUserKeyMiddleware.onAgent 消费后调用，防线程池复用残留污染下个 turn） */
    public void clearPendingSkillScope() {
        pendingSkillScope.remove();
    }

    /** 设置待记账会话键（守卫 tryEnter 同线程覆盖写 / SandboxUserKeyMiddleware 兜底） */
    public void setPendingSessionId(String sessionId) {
        pendingSessionId.set(sessionId);
    }

    /** 读取并清除待记账会话键（OpenSandboxClient.create/resume 时调用，用后即清） */
    public String takePendingSessionId() {
        var key = pendingSessionId.get();
        pendingSessionId.remove();
        return key;
    }

    /** 读取但不清除待记账会话键（测试断言/兜底路径用） */
    public String peekPendingSessionId() {
        var key = pendingSessionId.get();
        return key == null || key.isBlank() ? null : key;
    }

    /** 清除待记账会话键（middleware 消费后调用；reactor 线程池复用，残留会污染下个 turn） */
    public void clearPendingSessionId() {
        pendingSessionId.remove();
    }

    /** 生命周期管理器（lifecycle 开启时注入；null = 旁路） */
    public SandboxLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public void setLifecycleManager(SandboxLifecycleManager manager) {
        this.lifecycleManager = manager;
    }

    @Override
    protected OpenSandboxClientOptions clientOptions() {
        return new OpenSandboxClientOptions()
            .serverUrl(serverUrl)
            .apiKey(apiKey)
            .image(image)
            .timeout(timeout)
            .entrypoint(entrypoint)
            .resource(resource)
            .environment(environment)
            .workspaceRoot(workspaceRoot)
            .skillDisabledChecker(skillDisabledChecker)
            .requestTimeout(requestTimeout)
            .skillsSharedStore(skillsSharedStore)
            .skillBundleService(skillBundleService)
            .farmMeterRegistry(farmMeterRegistry);
    }

    @Override
    public OpenSandboxFilesystemSpec isolationScope(io.agentscope.harness.agent.IsolationScope scope) {
        super.isolationScope(scope);
        return this;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return getSnapshotSpecOverride() != null ? getSnapshotSpecOverride() : new NoopSnapshotSpec();
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        var spec = new WorkspaceSpec();
        spec.setRoot(workspaceRoot);
        return spec;
    }

    // ---- fluent builder methods ----
    public OpenSandboxFilesystemSpec serverUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }
    public OpenSandboxFilesystemSpec apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    public OpenSandboxFilesystemSpec image(String image) { this.image = image; return this; }
    public OpenSandboxFilesystemSpec timeout(Duration timeout) { this.timeout = timeout; return this; }
    public OpenSandboxFilesystemSpec entrypoint(List<String> entrypoint) { this.entrypoint = entrypoint; return this; }
    public OpenSandboxFilesystemSpec resource(Map<String, String> resource) { this.resource = resource; return this; }
    public OpenSandboxFilesystemSpec environment(Map<String, String> env) { this.environment = env; return this; }
    public OpenSandboxFilesystemSpec workspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; return this; }
    public OpenSandboxFilesystemSpec skillDisabledChecker(SkillDisabledChecker checker) {
        this.skillDisabledChecker = checker;
        return this;
    }
    public OpenSandboxFilesystemSpec requestTimeout(java.time.Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }
    public OpenSandboxFilesystemSpec workspaceReader(WorkspaceReader workspaceReader) {
        this.workspaceReader = workspaceReader;
        return this;
    }

    public OpenSandboxFilesystemSpec workspaceSyncService(WorkspaceSyncService workspaceSyncService) {
        this.workspaceSyncService = workspaceSyncService;
        return this;
    }

    public OpenSandboxFilesystemSpec skillsSharedStore(String store) {
        this.skillsSharedStore = store;
        return this;
    }

    public OpenSandboxFilesystemSpec skillBundleService(SkillBundleService service) {
        this.skillBundleService = service;
        return this;
    }

    public OpenSandboxFilesystemSpec farmMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) {
        this.farmMeterRegistry = registry;
        return this;
    }

    /** 上传文件元数据存储（沙箱 pending 注入状态机；可空=不启用上传注入） */
    public OpenSandboxFilesystemSpec fileAssetStore(io.agentmanager.framework.service.FileAssetStore fileAssetStore) {
        this.fileAssetStore = fileAssetStore;
        return this;
    }

    /** 文件存储后端（沙箱注入读字节；可空=不启用上传注入） */
    public OpenSandboxFilesystemSpec fileStorage(io.agentmanager.framework.service.storage.FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        return this;
    }
}
