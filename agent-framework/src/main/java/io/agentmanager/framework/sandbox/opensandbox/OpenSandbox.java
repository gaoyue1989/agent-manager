package io.agentmanager.framework.sandbox.opensandbox;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;

import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;

/**
 * OpenSandbox 沙箱运行时：包装 SDK Sandbox 句柄，
 * 实现 AgentScope AbstractBaseSandbox 的文件/命令执行契约。
 *
 * KV 运行时文件（MEMORY.md/memory/）采用延迟注入：
 * 首次 exec（框架所有文件操作最终都走 exec）时用 ctx.userId 从 KV 读取注入，
 * 因为 SandboxClient.create() 无 RuntimeContext 参数、拿不到 userId。
 *
 * 回写同步在 stop()（框架每次 call 结束都会调用 SandboxManager.release → stop）：
 * doExec 记录 userId，stop 时从沙箱拉取 MEMORY.md/memory/ 写回 KV。
 *
 * 同时实现 SandboxFileTransfer：SDK SandboxBackedFilesystem.uploadFiles 对实现该
 * 接口的沙箱走单文件直传；未实现时每个单文件写入（sessions.json、memory、会话
 * jsonl 等框架文件）都退化成「单文件 tar → 整套 hydrate 通道」，技能目录较大时
 * 等于每写一个小文件就传一遍全量投影（实测 30M 技能下单容器重复 10 次全量 tar）。
 * 技能投影路径（.skills-cache）不受此通道影响——见 {@link #supportsFileTransfer}。
 */
public class OpenSandbox extends AbstractBaseSandbox
        implements io.agentscope.harness.agent.sandbox.SandboxFileTransfer {

    private static final Logger log = LoggerFactory.getLogger(OpenSandbox.class);

    private final OpenSandboxState state;
    private final com.alibaba.opensandbox.sandbox.Sandbox osbSandbox;
    private final OpenSandboxClientOptions options;
    private final WorkspaceReader workspaceReader;
    private final WorkspaceSyncService workspaceSyncService;
    private final AtomicBoolean runtimeInjected = new AtomicBoolean(false);
    private final AtomicBoolean uploadsInjected = new AtomicBoolean(false);
    /** 本 call 已查过且无 pending 上传（call 粒度短路：实例存活期=单 call，防每 doExec 查 file_asset） */
    private final AtomicBoolean pendingEmptyChecked = new AtomicBoolean(false);
    private final AtomicBoolean userSkillsMaterialized = new AtomicBoolean(false);
    /** L4 物化 revision 门控开关（SANDBOX_SKILL_REVISION_GATE，默认 true；测试构造可关） */
    private final boolean skillRevisionGateEnabled;
    /** 回写快照差分开关（SANDBOX_SYNC_SNAPSHOT_DIFF，默认 true；测试构造可关） */
    private final boolean syncSnapshotDiffEnabled;
    /**
     * 记忆混合读穿开关（first-reasoning D1，SANDBOX_RUNTIME_READ_THROUGH，默认 false；
     * 测试构造可开）：true 时跳过预热注入，记忆探测由翻译层 KV 回落应答，真实 exec
     * 访问记忆路径时补注入（语义与预热注入等价，见 first-reasoning-memory-readthrough-design §3.1）。
     */
    private final boolean runtimeReadThroughEnabled;
    /** 记忆读穿 env 默认值（生产 8 参构造器入口；测试经长构造器显式传参覆盖） */
    private static final boolean RUNTIME_READ_THROUGH_DEFAULT =
        "true".equalsIgnoreCase(System.getenv("SANDBOX_RUNTIME_READ_THROUGH"));
    /**
     * stop 收尾 scoped .skills-cache 补投开关（first-reasoning D6，
     * SANDBOX_SKILL_CACHE_STOP_BACKFILL，默认 true = 旧行为）：false 时缓存只在命令
     * 首次真正访问时投放——代价是 turn 终态容器不含 .skills-cache，X18 探针/快照差分
     * 基准需一并调整（first-reasoning-memory-readthrough-design §3.6）。
     */
    private final boolean skillCacheStopBackfill;
    /** stop 收尾补投 env 默认值（生产 8 参构造器入口） */
    private static final boolean SKILL_CACHE_STOP_BACKFILL_DEFAULT =
        !"false".equalsIgnoreCase(System.getenv("SANDBOX_SKILL_CACHE_STOP_BACKFILL"));
    /**
     * 探针直供缓存（first-reasoning D4）：物化写入的内容回填（容器绝对路径 → 内容），
     * 探测窗口内 translateRead/translateGlob 直接应答，免重复 execd 往返。失效从严：
     * 任何真实 exec / 投影实际重水合 / stop() 立即失效并清空（§3.4 失效不变量）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> probeContentCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean probeCacheValid = new AtomicBoolean(false);
    /** 读穿模式下「真实记忆访问补注入」是否已完成（与 runtimeInjected 分离，失败可重试） */
    private final AtomicBoolean lazyRuntimeInjected = new AtomicBoolean(false);
    /**
     * exec 命令打点开关（{@code SANDBOX_EXEC_TRACE}，默认 true；{@code false} 不打命令文本）。
     * env 直读而非经 SandboxConfig——打点是纯日志行为，不参与配置绑定语义；
     * 静态而非实例字段——所有实例同一开关（全局诊断工具）。
     */
    private static final boolean EXEC_TRACE_ENABLED =
        !"false".equalsIgnoreCase(System.getenv("SANDBOX_EXEC_TRACE"));
    private volatile String userKey;
    /**
     * 本 call 生效的技能缓存 scope 目录名（.skills-cache/&lt;scope&gt;，对齐 SDK
     * HarnessSkillMiddleware.scopeKeyFor + MarketplaceStager.scopeSegment）。由
     * SandboxUserKeyMiddleware 绑定；投影据此只投放当前会话的 scope 目录，
     * 避免把宿主上所有历史 scope 都灌进容器。
     */
    private volatile String skillCacheScope;
    /** 每 call 一次 scoped .skills-cache 投放（stop 复位，下轮首 exec 重查） */
    private final AtomicBoolean skillCacheRefreshed = new AtomicBoolean(false);
    private final OpenSandboxFilesystemSpec filesystemSpec;
    private final FileAssetStore fileAssetStore;
    private final FileStorage fileStorage;
    /** 软链农场子系统（判定 + 收敛协作者，skills-syncd §6.2） */
    private final SkillsFarm skillsFarm;

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService, null, null, null);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService, filesystemSpec, null, null);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec,
                       FileAssetStore fileAssetStore,
                       FileStorage fileStorage) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage, true, true);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec,
                       FileAssetStore fileAssetStore,
                       FileStorage fileStorage,
                       boolean skillRevisionGateEnabled) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage,
            skillRevisionGateEnabled, true);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec,
                       FileAssetStore fileAssetStore,
                       FileStorage fileStorage,
                       boolean skillRevisionGateEnabled,
                       boolean syncSnapshotDiffEnabled) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage,
            skillRevisionGateEnabled, syncSnapshotDiffEnabled,
            RUNTIME_READ_THROUGH_DEFAULT, SKILL_CACHE_STOP_BACKFILL_DEFAULT);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec,
                       FileAssetStore fileAssetStore,
                       FileStorage fileStorage,
                       boolean skillRevisionGateEnabled,
                       boolean syncSnapshotDiffEnabled,
                       boolean runtimeReadThroughEnabled,
                       boolean skillCacheStopBackfill) {
        super(state);
        this.state = state;
        this.osbSandbox = osbSandbox;
        this.options = options;
        this.workspaceReader = workspaceReader;
        this.workspaceSyncService = workspaceSyncService;
        this.filesystemSpec = filesystemSpec;
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.skillRevisionGateEnabled = skillRevisionGateEnabled;
        this.syncSnapshotDiffEnabled = syncSnapshotDiffEnabled;
        this.runtimeReadThroughEnabled = runtimeReadThroughEnabled;
        this.skillCacheStopBackfill = skillCacheStopBackfill;
        this.skillsFarm = new SkillsFarm(options, osbSandbox, workspaceReader, filesystemSpec,
            () -> userKey, () -> state != null ? state.getSandboxId() : null);
    }

    /** 绑定用户 key（SandboxUserKeyMiddleware 经 OpenSandboxClient 注入） */
    public void setUserKey(String userKey) {
        this.userKey = userKey;
        log.info("[sandbox-open] userKey bound: {}", userKey);
    }

    /** 绑定技能缓存 scope（SandboxUserKeyMiddleware 经 OpenSandboxClient 注入；见 {@link SkillCacheScope}） */
    public void setSkillCacheScope(String skillCacheScope) {
        this.skillCacheScope = skillCacheScope;
        log.info("[sandbox-open] skillCacheScope bound: {}", skillCacheScope);
    }

    // ---- SandboxFileTransfer：单文件直传 execd，替代「单文件 tar → 全量 hydrate」退化 ----

    /**
     * SDK uploadFiles 的直传判定：框架文件（sessions.json/memory/会话 jsonl/outputs 等）
     * 返回 true 走单文件直传（写 4KB 只传 4KB）。
     *
     * <p>技能投影路径（.skills-cache）必须排除：技能目录的进容器语义由受控链路负责
     * （start 投影 + scoped 投放），若直传绕过，
     * stage 时序竞态（E-SKILL-4）会重新出现且无 hash 兜底。命中排除即回退 SDK 的
     * archive→hydrate 通道，由 {@link #doHydrateWorkspace} 传输。
     */
    @Override
    public boolean supportsFileTransfer(String path) {
        return path != null && !path.contains(".skills-cache");
    }

    /** 单文件直传：经 execd files API 原样落盘（byte[]，不做 base64 文本转换，见 §6.2.3） */
    @Override
    public void uploadFile(String path, byte[] data) throws Exception {
        // 无前导斜杠 → 相对 /workspace；SDK 传入的 path 已是工作区相对路径
        osbSandbox.files().write(List.of(
            com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                .path(path)
                .data(data)
                .mode(644)
                .build()));
    }

    @Override
    public byte[] downloadFile(String path) throws Exception {
        // scoped 投放延迟触发（first-llm-latency）：SDK SandboxFileTransfer 下载路径命中
        // .skills-cache 时同步投放（CAS 每 call 一次）——与 doExec/readWorkspaceFile 同口径，
        // 否则直读未投放目录会 404
        if (path != null && path.contains(SkillCacheScope.CACHE_DIR)) {
            hydrateScopedSkillCache();
        }
        return osbSandbox.files().readByteArray(path);
    }

    /** 已绑定的用户 key（OpenSandboxClient.delete/create 回滚注入状态用） */
    public String getUserKey() {
        return userKey;
    }

    @Override
    public void stop() throws Exception {
        // 每次 call 结束回写 KV（不阻塞：失败仅告警，沙箱内数据保留）
        // 并发场景 ThreadLocal 注入可能竞态失败（onAgent 与 acquire 线程切换），
        // 兜底：从 filesystemSpec 的 pendingUserKey 再读一次
        if (userKey == null && filesystemSpec != null) {
            var pending = filesystemSpec.takePendingUserKey();
            if (pending != null && !pending.isBlank()) {
                userKey = pending;
            }
        }
        log.info("[sandbox-open] stop() called, userKey={}", userKey);
        // scoped .skills-cache 投放同样按 call 粒度复位（下轮 scope 可能变化或新 stage）；
        // 本 call 从未投放过则收尾补投一次（turn 终态容器必含文件——X18 探针/下轮快照差分
        // 基准依赖它；CAS 保证「延迟投放」整 call 至多一次）
        var scopedRanThisCall = skillCacheRefreshed.get();
        skillCacheRefreshed.set(false);
        if (!scopedRanThisCall && skillCacheStopBackfill) {
            // 补投开关（first-reasoning D6，SANDBOX_SKILL_CACHE_STOP_BACKFILL，默认 true =
            // 旧行为）：false 时缓存只在命令首次真正访问时投放，turn 终态容器不含
            // .skills-cache（X18 探针/下轮快照差分基准需一并调整后再关）
            hydrateScopedSkillCache();
        }
        // 探针直供窗口按 call 生命周期关闭（first-reasoning D4）：下一 call 由物化重建
        invalidateProbeCache();
        if (workspaceSyncService != null && userKey != null) {
            try {
                // 快照差分（SANDBOX_SYNC_SNAPSHOT_DIFF）：把本会话 gate 传给回写侧，
                // 只读与快照不一致的容器文件（null gate = 全量读，开关关闭时）
                var gate = filesystemSpec != null && syncSnapshotDiffEnabled
                    ? filesystemSpec.skillSyncGate(userKey, state != null ? state.getSandboxId() : null)
                    : null;
                workspaceSyncService.syncBack(userKey, osbSandbox, gate);
            } catch (Exception e) {
                log.warn("Workspace sync back failed: {}", e.getMessage());
            }
        }
        // 沙箱生命周期 per-call 收尾（§4.4 T2/T3，终审 R2 统一口径）：call 引用计数 -1 +
        // 顺手终刷（renew + binding.expires_at 回填）——stop() 是 SDK per-call 通用收尾路径
        // （chat/A2A/AG-UI/background spawn 全链路必经），A2A 等不经 TurnFinalizer 链路的
        // expires_at 回填全靠它；不承担 turn 注销（一个 turn 可能多 call，注销只挂终态）。
        // 失败不阻塞收尾（KV 回写已完成，续期由守护线程兜底）。
        if (filesystemSpec != null && filesystemSpec.getLifecycleManager() != null
                && state != null && state.getSandboxId() != null) {
            try {
                filesystemSpec.getLifecycleManager().onCallStop(state.getSandboxId());
            } catch (Exception e) {
                log.warn("[sandbox-open] lifecycle stop flush failed (sandbox={}): {}",
                    state.getSandboxId(), e.getMessage());
            }
        }
        super.stop();
    }

    @Override
    protected ExecResult doExec(RuntimeContext ctx, String command, int timeoutSeconds) throws Exception {
        // 命令打点（P0-12 诊断前置）：exec 固定 ≈1s（server proxy）/130-200ms（execd 短命令），
        // 稳态 turn 的 11-15 次 doExec 是首推理延迟与 turn 时长的主要组成——不打命令文本无法归因。
        // 截断 160 字符（只做归因，不落全量载荷）；开关 SANDBOX_EXEC_TRACE（默认 true，关闭=仅保留旧行）
        if (EXEC_TRACE_ENABLED) {
            log.info("[sandbox-open] doExec cmd: {}", command.length() > 160
                ? command.substring(0, 160) + "…(" + command.length() + "ch)" : command);
        }
        log.info("[sandbox-open] doExec invoked: userKey={}, ctx={}", userKey, ctx != null ? ctx.getUserId() : null);
        // ④ 会话水合协调器：userKey 一次解析 + 四步幂等注入的唯一 doExec 侧入口
        hydrateForCall(ctx);
        // scoped 投放延迟触发（first-llm-latency）：命令真正访问 .skills-cache（技能脚本执行）
        // 时同步投放（CAS 每 call 一次）；首推理前的探测命令不含 .skills-cache，天然跳过
        if (command.contains(SkillCacheScope.CACHE_DIR)) {
            hydrateScopedSkillCache();
            // 投放改变容器内容 → 探针直供缓存失效（first-reasoning D4 失效不变量）
            invalidateProbeCache();
        }
        // P0-12：SDK 探测/读取类命令翻译为 execd files API 直读（免 exec 固定时延与轮询节拍）。
        // 直读容器实时状态 = 与 shell 命令同源，无缓存无失效问题；模板不匹配/翻译异常一律回落真 exec
        if (EXEC_TRANSLATE_ENABLED) {
            ExecResult translated = tryTranslateExec(command);
            if (translated != null) {
                return translated;
            }
        }
        // 真实 exec 前置（first-reasoning D1/D4）：直供缓存失效 + 读穿模式下真实命令访问
        // 记忆路径时补注入。翻译命中的探测命令不触发——首推理探测全是模板命令，注入成本保持为零
        onRealExecBeforeRun(command);
        var execution = runCommandGuarded(command);
        var exitCode = execution.getExitCode() != null ? execution.getExitCode() : -1;
        var stdout = execution.getLogs() != null
            ? execution.getLogs().getStdout().stream()
                .map(msg -> msg.getText())
                .collect(Collectors.joining("\n"))
            : "";
        var stderr = execution.getLogs() != null
            ? execution.getLogs().getStderr().stream()
                .map(msg -> msg.getText())
                .collect(Collectors.joining("\n"))
            : "";
        return new ExecResult(exitCode, stdout, stderr, false);
    }

    /** exec 数据面连续失败阈值（oa-1110-repro-fixes-design §2.3d；0 = 关闭） */
    private static final int EXEC_DATAPLANE_FAIL_LIMIT = resolveExecDataplaneFailLimit();

    private static int resolveExecDataplaneFailLimit() {
        try {
            String v = System.getenv("SANDBOX_EXEC_DATAPLANE_FAIL_LIMIT");
            return v == null || v.isBlank() ? 3 : Math.max(0, Integer.parseInt(v.trim()));
        } catch (Exception e) {
            return 3;
        }
    }

    /** exec 数据面连续失败计数（实例随沙箱代创建；成功即清零） */
    private final java.util.concurrent.atomic.AtomicInteger dataplaneFailStreak =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 执行命令并做数据面故障感知（oa-1110-repro-fixes-design §2.3d）：连接类失败
     * （502/503/504 网关不可达 / 连接异常 / 超时）按沙箱实例计<b>连续</b>失败数——
     * 达到 {@code SANDBOX_EXEC_DATAPLANE_FAIL_LIMIT}（默认 3）时抛出可读化异常：
     * harness 会把异常转为 "Internal sandbox error: <msg>" 工具文本回喂 LLM，可读文案
     * 引导模型停止重试风暴并告知用户（复现中 118 次 502 重试的根治手段）；同时 ERROR
     * 日志归因。阈值内保持原异常透传（容忍瞬时抖动）；任何情况下不改变 turn 控制流。
     * 非连接类失败（业务命令非零退出等）不计数、成功即清零。
     */
    private com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution
            runCommandGuarded(String command) throws Exception {
        com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution execution;
        try {
            execution = osbSandbox.commands().run(command);
        } catch (Exception e) {
            var kind = SandboxFailureClassifier.kindOf(e);
            if (kind == SandboxFailureClassifier.Kind.NONE
                    || EXEC_DATAPLANE_FAIL_LIMIT <= 0) {
                throw e;
            }
            int streak = dataplaneFailStreak.incrementAndGet();
            if (streak < EXEC_DATAPLANE_FAIL_LIMIT) {
                throw e;
            }
            log.error("[sandbox-open] sandbox dataplane unreachable after {} consecutive failures "
                    + "(sandbox={}, endpoint={}, kind={}): {}",
                streak, state != null ? state.getSandboxId() : "?",
                state != null ? state.getSandboxEndpoint() : "?", kind, e.getMessage());
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "exec",
                SandboxFailureClassifier.readableMessage(kind)
                    + "（当前会话沙箱已连续 " + streak + " 次命令失败，请停止重试本操作，"
                    + "并告知用户该会话沙箱已故障、需要重新发起会话。原始错误: "
                    + e.getMessage() + "）", e);
        }
        dataplaneFailStreak.set(0);
        return execution;
    }

    /**
     * 会话水合协调器（设计 ④，纯重构无行为变化）：三步幂等注入统一入口——middleware 主路径
     * （{@link SandboxUserKeyMiddleware#onAgent}）与 doExec 兜底路径共用。各步自带幂等标志
     * （runtimeInjected/uploadsInjected/userSkillsMaterialized），协调器本身
     * 无状态；任一步失败不影响后续步骤（各步内部 fail-soft）。
     *
     * <p>userKey 解析保持旧行为：ctx 非空即按当前 ctx 重新解析（userId 优先、session_user 反查、
     * 空则 sessionId）——同实例先后出现的不同 ctx 必须能更新绑定（Channel 降级链路依赖）；
     * ctx 无果时保持实例已绑定值（middleware 先行绑定的真实用户）不被覆盖（issue #52）。
     */
    public void hydrateForCall(RuntimeContext ctx) {
        if (ctx != null) {
            var key = resolveUserKey(ctx);
            if (key != null && !key.isBlank()) {
                userKey = key;
            }
            // 技能缓存 scope 兜底：优先用 middleware.onAgent 绑定的 turn 级 scope（与 SDK
            // HarnessSkillMiddleware.onSystemPrompt 同源），仅在未绑定时才从本 ctx 推断——
            // 避免 RemoteUserIdMiddleware 的 onActing 作用域写回（remote spawn 轮次）污染
            if (skillCacheScope == null || skillCacheScope.isBlank()) {
                var scope = SkillCacheScope.scopeSegment(ctx);
                if (scope != null && !scope.isBlank()) {
                    skillCacheScope = scope;
                }
            }
        }
        injectRuntimeFilesIfNeeded(ctx);
        injectPendingUploads();
        materializeUserSkills();
        // scoped .skills-cache 投放【不再在此处跑】（first-llm-latency 首推理关键路径优化）：
        // 50MB/2000f 实测 build+gzip+hydrate ≈4-6s 挡在首个 main LLM 请求之前。改为「延迟到
        // 首次真正需要」：①doExec/readWorkspaceFile 命令路径命中 .skills-cache（技能脚本执行/
        // 资源读取）时同步投放；②stop() 收尾若本 call 从未投放则补投（turn 终态容器必含文件，
        // X18 断言与下轮快照差分基准不受影响）。首推理前的探测命令不含 .skills-cache，天然跳过。
        // 同步执行、无线程并发（异步版在 SDK 沙箱句柄上并发访问会挂起，实测 300s 超时）。
    }

    // ==================== exec→execd 翻译器（P0-12，skill-sync-turn-perf 续篇） ====================
    // SDK BaseSandboxFilesystem 的 ls/read/glob 默认实现是 shell 命令模板，经 doExec 执行——
    // 每次探测付 exec 固定时延（server proxy ≈1s / execd 直连 130-200ms）。首推理前有 7 次
    // 探测（AGENTS.md/MEMORY/knowledge/skills 等 workspace 模板读取），稳态 turn 共 11-15 次，
    // 串行占满整个 turn。本翻译器把确定性模板命令改写为 execd files API 直读（readByteArray/
    // listDirectory 递归），读的是容器同一份实时状态——无缓存无失效问题，正确性不依赖 revision。
    // 模板不匹配/翻译异常一律回落真 exec（SDK 版本升级改模板时安全降级）。
    // 翻译中非 404 异常同样回落（2026-11 issue #296：此前 translate* 内部吞异常致回落对 API 错误从不生效）。
    // 开关 SANDBOX_EXEC_TRANSLATE（默认 true）。

    private static final boolean EXEC_TRANSLATE_ENABLED =
        !"false".equalsIgnoreCase(System.getenv("SANDBOX_EXEC_TRANSLATE"));

    /** read 探测：if [ ! -f 'P' ]; then echo '__NOT_FOUND__'; elif [ ! -s 'P' ]...sed -n '1,Np' 'P'; fi */
    private static final java.util.regex.Pattern P_READ = java.util.regex.Pattern.compile(
        "^if \\[ ! -f '([^']+)' \\]; then echo '__NOT_FOUND__'; elif \\[ ! -s '\\1' \\]; then"
            + " echo '__EMPTY__'; else sed -n '1,(\\d+)p' '\\1'; fi$");
    /** glob：find 'ROOT' -type f -name 'PAT' 2>/dev/null | sort | while...printf '%s\t%s\t%s'... */
    private static final java.util.regex.Pattern P_FIND = java.util.regex.Pattern.compile(
        "^find '([^']+)' -type f -name '([^']+)' 2>/dev/null \\| sort \\| while IFS= read -r f; do"
            + "\\s+size=\\$\\(stat -c '%s' \"\\$f\" 2>/dev/null \\|\\| echo 0\\);"
            + "\\s+mtime=\\$\\(stat -c '%Y' \"\\$f\" 2>/dev/null \\|\\| echo 0\\);"
            + "\\s+printf '%s\\\\t%s\\\\t%s\\\\n' \"\\$f\" \"\\$size\" \"\\$mtime\"; done$");
    /** ls 目录：if [ ! -e 'D' ]...for f in 'D'/*; do...printf 'DIR:/FILE:...'...done; fi */
    private static final java.util.regex.Pattern P_LS = java.util.regex.Pattern.compile(
        "^if \\[ ! -e '([^']+)' \\]; then echo '__NOT_EXISTS__'; elif \\[ ! -d '\\1' \\]; then"
            + " echo '__NOT_A_DIR__'; else for f in '\\1'/\\*; do\\s+if \\[ -d \"\\$f\" \\]; then\\s+"
            + "mtime=\\$\\(stat -c '%Y' \"\\$f\" 2>/dev/null \\|\\| echo 0\\);"
            + "\\s+printf 'DIR:%s\\\\t%s\\\\n' \"\\$f\" \"\\$mtime\";\\s+elif \\[ -f \"\\$f\" \\]; then\\s+"
            + "size=\\$\\(stat -c '%s' \"\\$f\" 2>/dev/null \\|\\| echo 0\\);"
            + "\\s+mtime=\\$\\(stat -c '%Y' \"\\$f\" 2>/dev/null \\|\\| echo 0\\);"
            + "\\s+printf 'FILE:%s\\\\t%s\\\\t%s\\\\n' \"\\$f\" \"\\$size\" \"\\$mtime\";\\s+fi; done; fi$");
    /** bus 轮询：test -e 'P' && echo yes || echo no */
    private static final java.util.regex.Pattern P_TEST_E = java.util.regex.Pattern.compile(
        "^test -e '([^']+)' && echo yes \\|\\| echo no$");

    /**
     * 尝试把探测/读取类命令翻译为 execd files API 直读。
     *
     * @return null = 模板不匹配（调用方回落真 exec）
     */
    private ExecResult tryTranslateExec(String command) {
        try {
            var mRead = P_READ.matcher(command);
            if (mRead.matches()) {
                return translateRead(mRead.group(1), Integer.parseInt(mRead.group(2)));
            }
            var mFind = P_FIND.matcher(command);
            if (mFind.matches()) {
                return translateGlob(mFind.group(1), mFind.group(2));
            }
            var mLs = P_LS.matcher(command);
            if (mLs.matches()) {
                return translateLs(mLs.group(1));
            }
            var mTestE = P_TEST_E.matcher(command);
            if (mTestE.matches()) {
                return new ExecResult(0, translateExists(mTestE.group(1)) ? "yes" : "no", "", false);
            }
            // 工作区根探测快路径（issue #290 收窄为精确等值）：harness
            // AbstractBaseSandbox.probeWorkspaceRootForPreservedResume 经 doExec 下发的
            // 唯一合法形态就是 "test -d " + getWorkspaceRoot()（10s 超时，javap 实证）；
            // 其余 test -d（含 $VAR 展开/相对路径/任意字面路径）一律回落真 exec，
            // 不再把语义无关的目录存在性判断误判成工作区根探测。
            // first-reasoning D5：存在性判定改一次 listDirectory 直查，不经 readByteArray
            // 对目录的 500 异常路径
            if (command.equals("test -d " + getWorkspaceRoot())) {
                return new ExecResult(directoryExists(getWorkspaceRoot()) ? 0 : 1, "", "", false);
            }
            // mkdir 不再翻译（issue #290）：doSetupWorkspace 走 osbSandbox.commands().run
            // 直连 execd、不经 doExec；harness BaseSandboxFilesystem 仅有的两个 mkdir 形态中
            // write 模板（if [ -e … 前缀）不匹配锚定正则、move 模板
            // （mkdir -p $(dirname 'x') && mv 'x' 'x'）此前正落入宽匹配被误翻译成
            // createDirectories——mv 永不执行、目录名成垃圾字面量、还虚报 exit 0。
            // 删除后 "mkdir -p ..." 一律回落真 exec，恢复 shell 原生语义
        } catch (Exception e) {
            // 翻译层仅将 execd files API 404 判定为缺失（isPathMissing）；其余异常
            // （瞬时故障/连接失败/超时）回落真 exec = 既定 fail-soft——issue #296 修复后
            // 本 catch 从「永不触发的死路径」变为真实异常回落路径，WARN 便于归因
            log.warn("[sandbox-open] exec translate failed, falling back to real exec ({}: kind={}, msg={}): {}",
                e.getClass().getSimpleName(), SandboxFailureClassifier.kindOf(e), e.getMessage(),
                command.length() > 80 ? command.substring(0, 80) : command);
            return null;
        }
        return null;
    }

    /**
     * execd files API「路径缺失」判据（2026-11 issue #296；评审 g1 修正拓扑表述）：
     * HTTP 404 且响应体非 sandbox-not-found 形态才映射为缺失哨兵
     * （__NOT_FOUND__/__NOT_EXISTS__），其余异常向上抛、交 {@link #tryTranslateExec}
     * 外层 catch 回落真 exec。评选依据：
     * <ul>
     *   <li>① files 请求的实际拓扑：客户端 useServerProxy(true)（OpenSandboxClient.java
     *       :116-117），经 server 代理转发（/v1/sandboxes/{id}/proxy/{n}/...，e2e mock
     *       server.mjs:322-326 对齐录制件形态），并非 execd 容器直连。因此 404 有两个来源，
     *       需按响应体区分：(a) 代理转发至 execd 的路径判定（活沙箱路径缺失）→ 判缺失；
     *       (b) 代理自身死沙箱分支（TTL 过期/故障态）→ <b>不判缺失</b>，向上抛回落真
     *       exec——否则死沙箱的探测被吞成假缺失，且与同 turn 真 exec 回落路径的
     *       「沙箱不可达」报错矛盾；回落后由 runCommandGuarded 连续失败熔断收口为一致的
     *       故障信号；</li>
     *   <li>② 两类 404 的响应体形态（2026-11-11 本地双运行时真实 server 探针实证，
     *       非 mock 推断）：execd 路径缺失 body 为
     *       {@code {"code":"FILE_NOT_FOUND","message":"file not found. open …"}}；
     *       server 代理死沙箱 body 的 code 字段带 {@code SANDBOX_NOT_FOUND}——k8s runtime
     *       （batchsandbox + Pool，业务目标架构）为
     *       {@code {"code":"KUBERNETES::SANDBOX_NOT_FOUND","message":"Sandbox '<id>' not found"}}，
     *       docker runtime 为
     *       {@code {"code":"DOCKER::SANDBOX_NOT_FOUND","message":"Sandbox <id> not found."}}。
     *       因此判据用 code 字段的 {@code sandbox_not_found}（下划线形）匹配两个真实运行时，
     *       并保留空格形 {@code sandbox not found} 兼容 e2e mock 旧形态
     *       （server.mjs:325-326）；mock 对路径缺失返回<b>空 body</b> 404
     *       （server.mjs:356/:363）→ 空 body 不含任何字样 → 判缺失，e2e 语义不变；</li>
     *   <li>③ 仍不采用 SDK ExceptionConverterKt.isFileNotFound（error.code==FILE_NOT_FOUND，
     *       依赖响应体解析）——真实 server 路径缺失虽带 FILE_NOT_FOUND code，但 mock 空 body
     *       下 code 为 UNEXPECTED_RESPONSE、该判据失配会令 X 组路径缺失探测回落真 exec，
     *       而 read 探测模板 {@code if [ ! -f ...} 不在 mock EXEC_WHITELIST
     *       （server.mjs:47）→ CommandExecError 126，e2e 回归；反向仅按 FILE_NOT_FOUND
     *       正向判缺失也无法排除死沙箱（两者都靠 body 区分，正反判据等价，取排除式）;</li>
     *   <li>④ 与 SandboxEnvFile.notFound 同型（sandbox 生命周期 404/410 惯例）——其中的 410
     *       是控制面 sandbox 资源态，不纳入。</li>
     * </ul>
     *
     * <p>失配方向的失败模式均安全：若未来真实死沙箱 body 变体不含上述字样，判据退化为
     * 「404 即缺失」= 评审前行为（影响有界：下轮 probeAlive 404/410 → DEAD 重建，
     * OpenSandboxClient.java:312-314）；反向误伤（路径缺失 body 恰含该字样）仅导致多回落
     * 一次真 exec（活沙箱上结果仍正确，只多付一次 exec 时延）。
     */
    private static boolean isPathMissing(Throwable e) {
        if (!(e instanceof SandboxApiException api)) {
            return false;
        }
        Integer status = api.getStatusCode();
        if (status == null || status != 404) {
            return false;
        }
        // server 代理层死沙箱 404（body code 含 SANDBOX_NOT_FOUND，两运行时统一）不是路径缺失：
        // 交调用方向上抛回落真 exec（runCommandGuarded 熔断给出一致故障信号）
        return !isSandboxNotFoundBody(api.getResponseBody());
    }

    /**
     * 404 响应体是否为 server 代理死沙箱形态（大小写不敏感）：code 字段下划线形
     * {@code SANDBOX_NOT_FOUND}（docker/k8s 两真实运行时统一）+ 空格形
     * {@code sandbox not found}（e2e mock 兼容）；空 body 视为 execd 路径缺失
     * （mock files/download 与 directories/list 的缺失形态）→ 返回 false
     */
    private static boolean isSandboxNotFoundBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        String lower = responseBody.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("sandbox_not_found") || lower.contains("sandbox not found");
    }

    /**
     * read 翻译：缺文件 → __NOT_FOUND__；空文件 → __EMPTY__；否则按 sed '1,Np' 语义取行。
     * 容器缺失时记忆路径回落 KV（first-reasoning D1 读穿，开关关闭时行为不变）；
     * 探针直供缓存命中（D4）时直接应答，免 execd 往返。
     */
    private ExecResult translateRead(String relPath, int maxLines) throws Exception {
        if (probeCacheValid.get()) {
            String content = probeContentCache.get(relPath);
            if (content != null) {
                return content.isEmpty()
                    ? new ExecResult(0, "__EMPTY__", "", false)
                    : renderSedRead(content, maxLines);
            }
        }
        byte[] bytes;
        try {
            bytes = osbSandbox.files().readByteArray(relPath);
        } catch (SandboxApiException e) {
            // 仅 404 判定为确定缺失（issue #296）；读穿模式下记忆路径回落 KV 点查，
            // 非记忆路径维持 __NOT_FOUND__；其余异常向上抛，交外层回落真 exec（fail-soft）
            if (isPathMissing(e)) {
                return readThroughMemoryRead(relPath, maxLines);
            }
            throw e;
        }
        if (bytes == null || bytes.length == 0) {
            // 文件存在但为空（模型清空过）≠ 缺失：必须答 __EMPTY__，不得回落 KV 读到旧内容
            return new ExecResult(0, "__EMPTY__", "", false);
        }
        return renderSedRead(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), maxLines);
    }

    /**
     * sed '1,Np' 语义渲染（容器读与 KV/缓存回落共用，保证探测应答逐字一致）：
     * 先剥掉一个尾随 \n（对齐 execd 按行切分 stdout 后 joining("\n") 重组的语义），
     * 再输出前 N 行（每行含换行；末行无换行则原样）。
     */
    private static ExecResult renderSedRead(String raw, int maxLines) {
        String content = raw;
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        if (maxLines >= Integer.MAX_VALUE / 2) {
            return new ExecResult(0, content, "", false);
        }
        var sb = new StringBuilder();
        int idx = 0, printed = 0;
        while (printed < maxLines && idx < content.length()) {
            int nl = content.indexOf('\n', idx);
            if (nl < 0) {
                sb.append(content, idx, content.length());
                break;
            }
            sb.append(content, idx, nl + 1);
            idx = nl + 1;
            printed++;
        }
        return new ExecResult(0, sb.toString(), "", false);
    }

    /**
     * D1 读穿回落：容器缺失的记忆文件从 KV 直读（点查）。仅读穿开启 + 记忆路径 + 用户已绑定时生效；
     * 其余情况维持 __NOT_FOUND__（与不读穿一致）。
     */
    private ExecResult readThroughMemoryRead(String absPath, int maxLines) {
        var content = readThroughMemoryContent(absPath);
        if (content == null) {
            return new ExecResult(0, "__NOT_FOUND__", "", false);
        }
        if (content.isEmpty()) {
            return new ExecResult(0, "__EMPTY__", "", false);
        }
        return renderSedRead(content, maxLines);
    }

    /** D1 记忆 KV 内容查找（null = 不适用/不存在）：记忆总开关与用户绑定一并短路 */
    private String readThroughMemoryContent(String absPath) {
        if (!runtimeReadThroughEnabled || workspaceReader == null || userKey == null || userKey.isBlank()) {
            return null;
        }
        var rel = normalizeRel(absPath);
        if (!isMemoryRelPath(rel)) {
            return null;
        }
        return workspaceReader.readMemoryFile(userKey, rel);
    }

    /** 记忆路径判定：{@code MEMORY.md} 精确 / {@code memory} 目录 / {@code memory/**} */
    private static boolean isMemoryRelPath(String rel) {
        return WorkspaceReader.MEMORY_FILE.equals(rel)
            || WorkspaceReader.MEMORY_DIR.equals(rel)
            || (rel != null && rel.startsWith(WorkspaceReader.MEMORY_DIR + "/"));
    }

    /** D4 探针直供缓存失效（任何容器变更路径都必须调用；失效只损失性能不损失正确性） */
    private void invalidateProbeCache() {
        if (probeCacheValid.compareAndSet(true, false)) {
            probeContentCache.clear();
        }
    }

    /**
     * 真实 exec 前置（doExec 翻译不命中后调用）：①D4 直供缓存失效——真实命令可能改容器；
     * ②D1 混合触发器——读穿模式下真实命令访问记忆路径时补注入（CAS 一次性，失败可重试），
     * 此后容器与预热注入形态一致，bash 读/edit 追加/turn 回写全部回到注入语义。
     */
    private void onRealExecBeforeRun(String command) {
        invalidateProbeCache();
        if (runtimeReadThroughEnabled && looksLikeMemoryAccess(command)
                && lazyRuntimeInjected.compareAndSet(false, true)) {
            injectRuntimeFilesLazily();
        }
    }

    /**
     * D1 触发判定：命令文本涉及记忆文件/目录（bash cat/ls、SDK write heredoc 等真实访问形态）。
     * {@code python3 -c} 前缀单独覆盖：SDK edit_file 模板把路径/old/new 编进 base64 载荷
     * （字母表不含 {@code .}，contains(MEMORY.md) 永不命中），edit 追加记忆是核心语义，
     * 宽松触发可接受（补注入一次性、fail-soft、空 KV 零成本）。
     */
    private static boolean looksLikeMemoryAccess(String command) {
        if (command == null) {
            return false;
        }
        return command.contains(WorkspaceReader.MEMORY_FILE)
            || command.contains(WorkspaceReader.MEMORY_DIR)
            || command.startsWith("python3 -c");
    }

    /** D1 补注入：读 KV 运行时文件写入容器（与预热注入同一数据面；失败可重试、不阻塞命令） */
    private void injectRuntimeFilesLazily() {
        if (workspaceReader == null || userKey == null || userKey.isBlank()) {
            lazyRuntimeInjected.set(false); // 用户未绑定（无身份无从注入），保持触发器待命
            return;
        }
        try {
            var runtimeFiles = workspaceReader.readRuntimeFiles(userKey);
            workspaceReader.injectToSandbox(osbSandbox, runtimeFiles);
            log.info("[sandbox-open] runtime files lazily injected on real memory access (user={}, files={})",
                userKey, runtimeFiles.size());
        } catch (Exception e) {
            // 补注入失败：容器仍无记忆文件，下一真实记忆命令重试；本条命令按缺失继续（回落旧行为）
            lazyRuntimeInjected.set(false);
            log.warn("[sandbox-open] lazy runtime injection failed (user={}): {}", userKey, e.getMessage());
        }
    }

    /** glob 翻译：递归列 ROOT 下匹配 PAT 的 regular file，按路径排序，输出 path\tsize\tmtime 行 */
    private ExecResult translateGlob(String root, String pattern) throws Exception {
        var files = new java.util.TreeSet<FileEntry>((a, b) -> a.rel().compareTo(b.rel()));
        var truncated = new AtomicBoolean(false);
        var memoryRoot = memoryRelUnder(root);
        if (memoryRoot != null) {
            // D1 读穿：memory/ 目录容器为空，从 KV 清单应答（path/size，mtime=0）
            fillGlobFromMemoryKv(memoryRoot, pattern, files);
        } else {
            // D4：SKILL.md 探针模式下缓存已覆盖的技能目录跳过容器遍历、合并缓存条目；
            // 其余模式照常遍历容器（保持「容器实时状态」语义——物化只写不删，历史残留仍可见）
            var skipDirs = probeCacheCoveredDirs(root, pattern);
            walkFilesSkippingDirs(root, pattern, files, 0, truncated, skipDirs);
            fillGlobFromProbeCache(root, pattern, files);
        }
        // 截断可观测性（issue #296）：每次 translateGlob 仅一条 WARN，避免深树逐分支刷屏
        if (truncated.get()) {
        }
        var sb = new StringBuilder();
        for (FileEntry e : files) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(e.rel()).append('\t').append(e.size()).append('\t').append(e.mtime());
        }
        return new ExecResult(0, sb.toString(), "", false);
    }

    /** root 归一化后落在 memory/ 子树内则返回其相对形态（如 {@code memory} / {@code memory/sub}），否则 null */
    private String memoryRelUnder(String root) {
        var rel = normalizeRel(root);
        if (!runtimeReadThroughEnabled) {
            return null;
        }
        return isMemoryRelPath(rel) ? rel : null;
    }

    /** D1 读穿 glob：KV 运行时清单里 root 子树内匹配 pattern 的文件（size=KV 字节数，mtime=0） */
    private void fillGlobFromMemoryKv(String memoryRoot, String pattern, java.util.TreeSet<FileEntry> out) {
        if (workspaceReader == null || userKey == null || userKey.isBlank() || out.size() > 5000) {
            return;
        }
        try {
            for (var entry : workspaceReader.listRuntimeFiles(userKey).entrySet()) {
                var rel = entry.getKey();
                if (rel.startsWith(memoryRoot + "/") && globMatch(pattern, leaf(rel))) {
                    out.add(new FileEntry(rel, entry.getValue() == null ? 0 : entry.getValue(), 0));
                }
            }
        } catch (Exception e) {
            log.debug("[sandbox-open] read-through memory glob failed: {}", e.getMessage());
        }
    }

    /**
     * D4 缓存覆盖目录集：仅当缓存有效、模式恰为 SKILL.md（系统提示探针模式）且缓存非空时，
     * 返回缓存键（容器绝对路径）中位于 root 子树内的技能一级目录（如 {@code skills/<name>}）——
     * walkFiles 跳过这些目录的 execd 遍历，由 fillGlobFromProbeCache 以缓存条目补齐。
     */
    private java.util.Set<String> probeCacheCoveredDirs(String root, String pattern) {
        if (!probeCacheValid.get() || probeContentCache.isEmpty() || !WorkspaceReader.SKILL_FILE.equals(pattern)) {
            return java.util.Set.of();
        }
        var rootRel = normalizeRel(root);
        var dirs = new java.util.HashSet<String>();
        for (var absPath : probeContentCache.keySet()) {
            var rel = normalizeRel(absPath);
            if (!rel.startsWith(rootRel + "/") && !(rootRel.isEmpty() || ".".equals(rootRel))) {
                continue;
            }
            int slash = rel.indexOf('/', rootRel.length() + 1);
            var topDir = slash < 0 ? null : rel.substring(0, slash);
            if (topDir != null) {
                dirs.add(topDir);
            }
        }
        return dirs;
    }

    /** D4 缓存条目补齐 glob 结果（root 子树内 + 模式命中；size=缓存内容字节数，mtime=0） */
    private void fillGlobFromProbeCache(String root, String pattern, java.util.TreeSet<FileEntry> out) {
        if (!probeCacheValid.get() || probeContentCache.isEmpty()) {
            return;
        }
        var rootRel = normalizeRel(root);
        for (var e : probeContentCache.entrySet()) {
            var rel = normalizeRel(e.getKey());
            boolean underRoot = rootRel.isEmpty() || ".".equals(rootRel)
                || rel.equals(rootRel) || rel.startsWith(rootRel + "/");
            if (underRoot && globMatch(pattern, leaf(rel))) {
                var size = e.getValue() == null ? 0 : e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                out.add(new FileEntry(rel, size, 0));
            }
        }
    }

    /**
     * ls 目录翻译：__NOT_EXISTS__/__NOT_A_DIR__/DIR:path\tmtime + FILE:path\tsize\tmtime。
     * 容器缺失的 memory/ 目录在读穿模式下从 KV 清单应答（first-reasoning D1，mtime=0）。
     */
    private ExecResult translateLs(String dir) throws Exception {
        List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo> entries;
        try {
            entries = osbSandbox.files().listDirectory(dir);
        } catch (SandboxApiException e) {
            // 仅 404 判定为确定缺失（issue #296）；读穿模式下 memory/ 目录回落 KV 清单，
            // 其余维持 __NOT_EXISTS__；异常向上抛交外层回落真 exec（fail-soft）
            if (isPathMissing(e)) {
                return readThroughMemoryLs(dir);
            }
            throw e;
        }
        if (entries == null) {
            return readThroughMemoryLs(dir);
        }
        var sb = new StringBuilder();
        for (var e : entries) {
            String p = e.getPath();
            if (p == null) {
                continue;
            }
            p = normalizeRel(p);
            boolean isDir = "directory".equalsIgnoreCase(e.getType()) || "dir".equalsIgnoreCase(e.getType());
            long mtime = epochSeconds(e);
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            if (isDir) {
                sb.append("DIR:").append(p).append('\t').append(mtime);
            } else {
                sb.append("FILE:").append(p).append('\t').append(Math.max(e.getSize(), 0)).append('\t').append(mtime);
            }
        }
        return new ExecResult(0, sb.toString(), "", false);
    }

    /**
     * D1 读穿 ls 回落：容器缺失（404/null）且目录为 memory/ 子树时，用 KV 运行时清单
     * 拼装与容器同构的 FILE: 行（path\tsize\tmtime=0）；其余情况维持 __NOT_EXISTS__。
     */
    private ExecResult readThroughMemoryLs(String dir) throws Exception {
        var rel = normalizeRel(dir);
        if (!runtimeReadThroughEnabled || workspaceReader == null || userKey == null || userKey.isBlank()
                || !WorkspaceReader.MEMORY_DIR.equals(rel)) {
            return new ExecResult(0, "__NOT_EXISTS__", "", false);
        }
        var sb = new StringBuilder();
        try {
            for (var entry : workspaceReader.listRuntimeFiles(userKey).entrySet()) {
                if (!entry.getKey().startsWith(WorkspaceReader.MEMORY_DIR + "/")) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("FILE:").append(entry.getKey()).append('\t')
                    .append(entry.getValue() == null ? 0 : entry.getValue()).append('\t').append(0);
            }
        } catch (Exception e) {
            log.debug("[sandbox-open] read-through memory ls failed: {}", e.getMessage());
            return new ExecResult(0, "__NOT_EXISTS__", "", false);
        }
        return new ExecResult(0, sb.toString(), "", false);
    }

    /**
     * test -e 翻译（first-reasoning D5）：一次<b>父目录列举</b>判存在（文件/目录通吃）——
     * 历史实现「先 readByteArray 再 listDirectory」对目录必付一次 500 异常路径、对不存在
     * 路径付两次 404；父目录列举异常时回落旧两步实现（边角语义不回退）。
     * 记忆路径在读穿模式下先查 KV（容器缺失时父列举会误判不存在）。
     */
    private boolean translateExists(String relPath) throws Exception {
        if (runtimeReadThroughEnabled && workspaceReader != null && userKey != null && !userKey.isBlank()) {
            var rel = normalizeRel(relPath);
            if (isMemoryRelPath(rel) && WorkspaceReader.MEMORY_DIR.equals(rel)) {
                return workspaceReader.listRuntimeFiles(userKey).keySet().stream()
                    .anyMatch(k -> k.startsWith(WorkspaceReader.MEMORY_DIR + "/"));
            }
            if (rel != null && rel.startsWith(WorkspaceReader.MEMORY_DIR + "/")
                    && rel.endsWith(".md") && rel.indexOf('/', WorkspaceReader.MEMORY_DIR.length() + 1) < 0) {
                // memory/ 一级 .md 文件：容器缺失（未注入）时父列举必误判，先查 KV 点查
                return workspaceReader.readMemoryFile(userKey, rel) != null;
            }
        }
        var normalized = normalizeRel(relPath);
        // 父目录列举仅对工作区相对路径/工作区内绝对路径生效：非工作区绝对路径（/etc/hosts 等）
        // 不得改写成 /workspace 镜像路径判存在，直接回落旧两步实现
        var workspaceScoped = !relPath.startsWith("/") || relPath.startsWith(getWorkspaceRoot() + "/");
        int slash = normalized.lastIndexOf('/');
        var parent = slash <= 0 ? "" : normalized.substring(0, slash);
        var leafPath = leaf(normalized);
        if (workspaceScoped && !parent.isEmpty()) {
            try {
                var entries = osbSandbox.files().listDirectory(getWorkspaceRoot() + "/" + parent);
                if (entries != null) {
                    for (var e : entries) {
                        if (e.getPath() != null && leaf(normalizeRel(e.getPath())).equals(leafPath)) {
                            return true;
                        }
                    }
                    return false;
                }
            } catch (Exception ignoreParent) {
                // 父目录列举失败（适配器差异等）→ 回落旧两步实现
            }
        }
        try {
            osbSandbox.files().readByteArray(relPath);
            return true;
        } catch (SandboxApiException e) {
            // 404 = 确定不是文件 → 继续目录探测；其余异常向上抛，交外层回落真 exec（issue #296）
            if (!isPathMissing(e)) {
                throw e;
            }
        }
        try {
            var entries = osbSandbox.files().listDirectory(relPath);
            return entries != null;
        } catch (SandboxApiException e) {
            // 两层均 404 = 确定缺失（false）；其余异常向上抛回落真 exec（test -e / test -d）
            if (isPathMissing(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * test -d 翻译（first-reasoning D5）：一次 listDirectory 直查目录存在性——不再经
     * readByteArray 对目录的 500 异常路径；不做 /workspace 特判短路（避免容器根被删后仍答 0）。
     * 异常分型与 translateExists 同口径（issue #296）：仅 execd 404 判「目录不存在」，
     * 其余异常向上抛、交 tryTranslateExec 回落真 exec——瞬时故障不得被翻译层吞成 exit 1。
     */
    private boolean directoryExists(String path) throws Exception {
        try {
            var entries = osbSandbox.files().listDirectory(path);
            return entries != null;
        } catch (SandboxApiException e) {
            if (isPathMissing(e)) {
                return false;
            }
            throw e;
        }
    }

    /** 条目 mtime 秒（对齐 SDK 模板 stat -c '%Y' 口径；entry 自带 modifiedAt，缺失置 0） */
    private static long epochSeconds(com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo e) {
        var at = e.getModifiedAt();
        return at == null ? 0 : at.toEpochSecond();
    }

    /**
     * 递归收集 relPath 子树内匹配 pattern 的 regular file（含 size/mtime；深度/条目上界防御）。
     * 截断经 {@code truncated} 出参上报，由 translateGlob 统一告警。
     *
     * <p>异常语义（issue #296）：listDirectory 404 = 子树在遍历期间不存在，视作空目录
     * （正常边界，与真 find 对缺失目录的「静默空输出」一致）；其余异常向上抛、交
     * tryTranslateExec 回落真 exec——瞬时故障下不得让 glob_files 拿到假空结果
     * （真 find 无上界，故障轮回落恰好消解「翻译上界 vs find 无上界」的静默不一致）。
     */
    private void walkFiles(String relDir, String pattern, java.util.TreeSet<FileEntry> out, int depth,
                           AtomicBoolean truncated) throws Exception {
        walkFilesSkippingDirs(relDir, pattern, out, depth, truncated, java.util.Set.of());
    }

    /**
     * 同 {@link #walkFiles}，但跳过 skipDirs 中目录的容器遍历（first-reasoning D4）：
     * 这些目录的内容由探针直供缓存补齐（fillGlobFromProbeCache），跳过免重复 execd 往返。
     * 跳过目录的隐含约束：其内容与缓存逐字一致（SKILL.md 模式 + 物化后窗口内），由失效不变量保证。
     */
    private void walkFilesSkippingDirs(String relDir, String pattern, java.util.TreeSet<FileEntry> out, int depth,
                                       AtomicBoolean truncated, java.util.Set<String> skipDirs) throws Exception {
        if (depth > 8 || out.size() > 5000) {
            truncated.set(true);
            return;
        }
        List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo> entries;
        try {
            entries = osbSandbox.files().listDirectory(relDir);
        } catch (SandboxApiException e) {
            if (isPathMissing(e)) {
                return;
            }
            throw e;
        }
        if (entries == null) {
            return;
        }
        for (var e : entries) {
            String p = e.getPath();
            if (p == null || p.isBlank()) {
                continue;
            }
            String rel = normalizeRel(p);
            if (rel.isEmpty()) {
                continue;
            }
            if ("directory".equalsIgnoreCase(e.getType()) || "dir".equalsIgnoreCase(e.getType())) {
                if (skipDirs.contains(rel)) {
                    continue; // 缓存已覆盖：容器遍历跳过（缓存条目另行补齐）
                }
                walkFilesSkippingDirs(rel, pattern, out, depth + 1, truncated, skipDirs);
            } else if (globMatch(pattern, leaf(rel))) {
                out.add(new FileEntry(rel, Math.max(e.getSize(), 0), epochSeconds(e)));
            }
        }
    }

    /** glob 命中条目（rel/size/mtime 三元组，排序按 rel） */
    private record FileEntry(String rel, long size, long mtime) {}

    /** 归一为工作区相对路径（剥 /workspace/ 前缀与前导斜杠） */
    private String normalizeRel(String p) {
        String root = getWorkspaceRoot();
        if (p.startsWith(root + "/")) {
            p = p.substring(root.length() + 1);
        } else if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /**
     * D4 探针直供回填（WorkspaceReader.materializeUserSkills 的 contentSink）：
     * 仅窗口有效期内写入；容器绝对路径作为缓存键（与翻译层 relPath 原样对齐）。
     */
    private void fillProbeCache(String absPath, String content) {
        if (probeCacheValid.get() && absPath != null && content != null) {
            probeContentCache.put(absPath, content);
        }
    }

    /** 探针入口（基准/契约对照专用，生产不调用）：直通翻译器 */
    public ExecResult translateForProbe(String command) {
        return tryTranslateExec(command);
    }

    static String leaf(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** shell glob（* ?）→ 精确/后缀/通配匹配 */
    private static boolean globMatch(String pattern, String name) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.startsWith("*") && pattern.indexOf('*', 1) < 0) {
            return name.endsWith(pattern.substring(1));
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return name.equals(pattern);
        }
        var rx = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> rx.append(".*");
                case '?' -> rx.append('.');
                default -> {
                    if ("\\.^$|+()[]{}".indexOf(c) >= 0) {
                        rx.append('\\');
                    }
                    rx.append(c);
                }
            }
        }
        return name.matches(rx.toString());
    }

    /**
     * 解析 KV 隔离 key：经 SessionKeyResolver 反查真实用户（与 SandboxUserKeyMiddleware
     * 同源）。Channel 链路 ctx.userId 是网关 peer（=前端 sessionId）——middleware 已把
     * 真实用户绑定到实例，此处若原样取 ctx 值会把 userKey 覆盖退化回 per-session 命名空间
     * （issue #52）。反查不可用/未命中时保持旧行为：userId 优先，为空降级 sessionId
     * （与框架 IsolationScope.USER → SESSION 降级语义一致，debug 页面等
     * Channel 路径 userId 为空，实测 state_data.user_id=""），
     * 保证注入/回写使用同一命名空间。
     */
    private String resolveUserKey(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        var resolver = filesystemSpec != null ? filesystemSpec.getSessionKeyResolver() : null;
        if (resolver != null) {
            var real = resolver.realUserId(ctx);
            if (real != null && !real.isBlank()) {
                return real;
            }
        }
        var userId = ctx.getUserId();
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        var sessionId = ctx.getSessionId();
        return sessionId != null && !sessionId.isBlank() ? sessionId : null;
    }

    /**
     * 上传文件注入（file-upload-download-plan §6.2.3）：
     * 将 userKey 名下 status=pending 的上传文件经 execd files API 写入沙箱
     * /workspace/uploads/{workspace_path}，成功后置 injected。
     *
     * <p>调用时机：OpenSandboxClient.create()/resume() 后立即执行（沙箱文件操作
     * 走 execd 原生 API，不一定经过 doExec——实测 read_file/execute 均不触发）。
     * fail-soft：注入失败仅告警不阻塞（该批文件本轮不可见，下 turn 重试）。
     * 容器销毁重建时状态由 OpenSandboxClient.delete/create 回滚（§6.2.4 双保险）。
     */
    public void injectPendingUploads() {
        if (fileAssetStore == null || fileStorage == null || uploadsInjected.get()) {
            return;
        }
        // 本 call 内已查过且无 pending → 跳过（P2-16）：hydrateForCall 每 doExec 都跑，实例
        // 存活期=单 call，15 次 doExec 原本查 15 次 file_asset（实测 106 次/6 turn）。实例即
        // call 粒度——下一 call 新实例重查，有上传仍下 call 注入，语义不变
        if (pendingEmptyChecked.get()) {
            return;
        }
        var userId = userKey;
        if ((userId == null || userId.isBlank()) && filesystemSpec != null) {
            // acquire 先于 middleware（实测）：实例未绑定 userKey 时从 spec ThreadLocal 兜底
            // （middleware.onAgent 已设置但 bindUserKey 过早消费/未执行）
            userId = filesystemSpec.peekPendingUserKey();
        }
        if (userId == null || userId.isBlank()) {
            return;
        }
        synchronized (uploadsInjected) {
            if (uploadsInjected.get()) {
                return;
            }
            try {
                // userKey 绑定的即会话隔离键（Channel peer = sessionId）：同值查 user_key 与 session_id 双维度
                var pending = fileAssetStore.listPending(userId, userId);
                if (pending.isEmpty()) {
                    // 不设 uploadsInjected=true（上传可能尚未完成）；但本 call 内置「已查无 pending」
                    // 短路，同 call 后续 doExec 不再查——下一 call 新实例自然重查
                    pendingEmptyChecked.set(true);
                    log.debug("[sandbox-open] no pending uploads yet for user {}", userId);
                    return;
                }
                var injectedIds = new java.util.ArrayList<String>();
                // 已占用的工作区路径（防同名覆盖）：来自 file_asset 历史注入记录，
                // 沙箱容器可能在远端主机，无法像非沙箱路径那样 ls 本地目录
                var taken = new java.util.HashSet<>(fileAssetStore.listWorkspacePaths(userId, userId));
                for (var f : pending) {
                    // workspace_path 是路径单一真相源：chat 请求时已预留（UploadWorkspaceInjector
                    // #ensureWorkspacePath，先行于提示构造），此处按预留路径原样写入——提示路径
                    // =注入路径，撞名不得在注入时刻改名（提示文本请求时已冻结，改名即漂移，
                    // agent 按提示读文件必 file_not_found）。null 仅为遗留行兜底（上传后从未
                    // 发起对话，无提示引用，此时分配无漂移风险）
                    var wsPath = f.workspacePath() != null
                        ? f.workspacePath()
                        : UploadWorkspaceInjector.allocateWorkspacePath(taken, f.fileName());
                    try (var in = fileStorage.read(f.storageKey())) {
                        osbSandbox.files().write(List.of(
                            com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                                // execd files API 路径基准 = /workspace（实测：无前导斜杠 → 相对 /workspace；
                                // 带前导斜杠 → 容器绝对路径，会写错位）。上传文件统一在 /workspace/uploads/
                                .path(wsPath)
                                // execd 按提交字节原样落盘（byte[] → octet-stream），不做 base64 解码——
                                // 传 String 会被当作文本原样写入，二进制文件全部损坏（实测钉死，见 §6.2.3）
                                .data(in.readAllBytes())
                                .mode(644)
                                .build()));
                    }
                    // 注入成功后才回写 workspace_path（预留/兜底分配同值幂等，保证路径与沙箱内实际一致）
                    fileAssetStore.updateWorkspacePath(f.id(), wsPath);
                    taken.add(wsPath);
                    injectedIds.add(f.id());
                }
                fileAssetStore.markInjected(injectedIds);
                uploadsInjected.set(true);
                log.info("[sandbox-open] injected {} pending upload(s) for user {}", injectedIds.size(), userId);
            } catch (Exception e) {
                // fail-soft：注入失败不阻塞；状态保持 pending，下 turn 重试
                log.warn("[sandbox-open] failed to inject pending uploads: {}", e.getMessage());
            }
        }
    }

    /**
     * 首次文件操作前注入 KV 运行时文件（MEMORY.md/memory/）。
     * 静态模板（AGENTS.md/skills/ 等）由框架投影在 hydrateWorkspace 时注入。
     *
     * <p>注入粒度从「每 call」收敛到「每沙箱代」（P1 收尾）：runtimeInjected 是实例标志，
     * 实例每 call 重建 → 原实现每 call 重注入；而同代内 turn 结束的回写已把容器记忆同步进
     * KV，重注入是等值幂等，只白付 memory/ 的 KV 搜索成本（L4 大时 memory 命名空间扫描
     * 实测 ≈1.5s/call）。代级记忆挂 {@link SkillSyncGate}（userKey|sandboxId，Pod 级）——
     * 代边界 gate 换新，首 turn 必注入。
     */
    private void injectRuntimeFilesIfNeeded(RuntimeContext ctx) {
        if (workspaceReader == null || runtimeInjected.get()) {
            return;
        }
        var userId = resolveUserKey(ctx);
        if (userId == null) {
            return;
        }
        var sandboxId = state != null ? state.getSandboxId() : null;
        var gate = filesystemSpec != null ? filesystemSpec.skillSyncGate(userId, sandboxId) : null;
        if (gate != null && gate.lastRuntimeInjected()) {
            runtimeInjected.set(true);
            return;
        }
        if (runtimeReadThroughEnabled) {
            // 混合读穿（first-reasoning D1）：跳过预热注入（≈24MB KV 读 + 容器写挡在首推理前）。
            // 记忆探测由翻译层 KV 回落应答；真实 exec 访问记忆路径时经
            // injectRuntimeFilesLazily 补注入——此后与预热注入形态一致，读/改/回写语义不变
            runtimeInjected.set(true);
            if (gate != null) {
                gate.setLastRuntimeInjected(true);
            }
            log.info("[sandbox-open] runtime files read-through enabled, skip eager injection (user={})", userId);
            return;
        }
        synchronized (runtimeInjected) {
            if (runtimeInjected.get()) {
                return;
            }
            try {
                var runtimeFiles = workspaceReader.readRuntimeFiles(userId);
                workspaceReader.injectToSandbox(osbSandbox, runtimeFiles);
                runtimeInjected.set(true);
                if (gate != null) {
                    gate.setLastRuntimeInjected(true);
                }
            } catch (Exception e) {
                // 注入失败不阻塞：沙箱内无历史记忆时 agent 仍可运行，下次 exec 重试
                log.warn("Failed to inject runtime files into sandbox: {}", e.getMessage());
            }
        }
    }

    /**
     * 沙箱档「会话开始物化 L4」（幂等，每实例一次）：把该用户 KV 里的个人技能
     * （{@code agents/{agent}/users/{uid}/skills}）投影进容器 {@code /workspace/skills}，
     * 使管理面 PUT / 从包内下发在同代容器内对会话生效。
     *
     * <p>调用点：{@link SandboxUserKeyMiddleware#onAgent}（主路径，acquire 之后、agent 执行前）
     * 与 {@link #doExec}（兜底，首次命令执行前）。KV 为权威：admin-override 栅栏技能照写、
     * tombstone 技能跳过；只写不删。失败仅告警不阻塞会话。
     */
    public void materializeUserSkills() {
        if (workspaceReader == null || userSkillsMaterialized.get()) {
            return;
        }
        var uid = userKey;
        if ((uid == null || uid.isBlank()) && filesystemSpec != null) {
            // acquire 先于 middleware 时实例未绑定 userKey，从 spec ThreadLocal 兜底
            uid = filesystemSpec.peekPendingUserKey();
        }
        if (uid == null || uid.isBlank()) {
            return;
        }
        synchronized (userSkillsMaterialized) {
            if (userSkillsMaterialized.get()) {
                return;
            }
            try {
                var sandboxId = state != null ? state.getSandboxId() : null;
                var gate = filesystemSpec != null ? filesystemSpec.skillSyncGate(uid, sandboxId) : null;
                // revision 门控（SANDBOX_SKILL_REVISION_GATE，默认 true）：本沙箱代已物化过
                // 且 KV revision 未变 → KV 技能集未变 → 跳过全量物化（1 次 get 替代全量 list+read）
                if (skillRevisionGateEnabled && gate != null && gate.lastMaterialized()) {
                    var current = workspaceReader.readUserSkillRevision(uid);
                    if (current != null && current.equals(gate.revision())) {
                        log.debug("[sandbox-open] L4 skills revision unchanged for user {}, skipping materialize", uid);
                        userSkillsMaterialized.set(true);
                        return;
                    }
                    if (current == null) {
                        // revision 读不到（KV 异常/从未写入）：保守走全量物化（现行为）
                        log.debug("[sandbox-open] L4 revision unknown for user {}, falling back to full materialize", uid);
                    }
                }
                int count;
                try {
                    // 探针直供窗口开启（first-reasoning D4）：物化写入经 sink 回填缓存，
                    // 同 call 的系统提示探测（sed SKILL.md / find skills -name SKILL.md）
                    // 直接命中，免 5 次 execd 往返；窗口由失效不变量严格关闭（真实 exec /
                    // 投影重水合 / .skills-cache 投放 / stop()）
                    probeContentCache.clear();
                    probeCacheValid.set(true);
                    count = workspaceReader.materializeUserSkills(osbSandbox, uid, this::fillProbeCache);
                } catch (Exception materializeError) {
                    // 物化异常时窗口作废（容器内容未知，缓存不可信）
                    invalidateProbeCache();
                    throw materializeError;
                }
                userSkillsMaterialized.set(true);
                if (gate != null) {
                    // 回填门控：revision 用物化前后各读一次的新值（物化写入已 bump revision）；
                    // 物化失败（异常）不会走到这里，gate 保持未置位下轮重试
                    gate.setRevision(workspaceReader.readUserSkillRevision(uid));
                    gate.setLastMaterialized(true);
                }
                if (count > 0) {
                    log.info("[sandbox-open] materialized {} L4 skill(s) into container for user {}",
                        count, uid);
                }
            } catch (Exception e) {
                // fail-soft：容器内保留 L2 基线/上一代副本，下次 acquire 重试
                log.warn("[sandbox-open] materialize L4 skills failed for user {}: {}", uid, e.getMessage());
            }
        }
    }

    /**
     * scoped 技能缓存投放（每 call 一次，PR-4b 起仅软链农场一路）：
     *
     * <p>背景：SDK 默认投影根含整棵 {@code .skills-cache}，其中按 scope 分片
     * （{@code .skills-cache/<scopeSegment>/<source-ns>/<skill>/}，scope = IsolationScope.USER 档
     * 的 {@code ctx.userId}，Channel 链路即会话 id）。宿主工作区跨会话/跨用户共享，历史 scope
     * 目录永不回收（MarketplaceStager 孤儿 GC 只清当前 scope 内），若整棵投影则把别的会话/用户
     * 的缓存全灌进容器。本框架改为：SDK 投影根排除 {@code .skills-cache}（见 AgentScopeConfig），
     * 由本方法只把<b>当前 scope</b>投放进容器，并清理容器内其它 scope 残留。
     *
     * <p>投放内容仅供 shell 执行技能脚本使用（{@code load_skill_through_path} 走内存资源/
     * lazy 仓储，不读容器），因此 doExec（首个命令前）投放即可覆盖消费路径。
     *
     * <p><b>farm 语义（PR-4b：tar 物化路径已退役）</b>：农场未启用（{@code
     * SANDBOX_SKILLS_SHARED_STORE} 空）或本会话判定降级 → 本 call <b>无包技能投放</b>，
     * 不再回落 tar（回滚 = 回滚整个 release）；判定/收敛/稳态记账见 {@link SkillsFarm}
     * （D-10 稳态短路 + D-11 模式分量）。收敛 exec 失败经 CAS 复位，下次触发重试。
     *
     * <p>包级可见（非 private）：供测试 spy 验证 D6 补投开关行为。
     */
    void hydrateScopedSkillCache() {
        if (state == null || !skillCacheRefreshed.compareAndSet(false, true)) {
            return;
        }
        try {
            var spec = state.getWorkspaceSpec();
            String sourceRoot = projectionSourceRoot(spec);
            if (sourceRoot == null) {
                // 投影未启用/未装配：无可投放
                return;
            }
            String scope = skillCacheScope;
            if (scope == null || scope.isBlank()) {
                // 身份未知（middleware/exec ctx 均未提供）：跳过，下次 exec 重试
                skillCacheRefreshed.set(false);
                return;
            }
            // 包技能仅 farm 一路（端点 manifest 制，宿主 stage 目录不再参与）：farm 未启用/
            // 判定降级 = 本 call 无包技能投放（返回 false 即止，无 tar 回落）；收敛 exec 失败
            // 原样上抛，由下方 catch 复位 CAS 重试
            skillsFarm.tryHydrate(scope);
        } catch (Exception e) {
            skillCacheRefreshed.set(false);
            log.warn("[sandbox-open] scoped .skills-cache hydrate failed: {}", e.getMessage());
        }
    }

    /** 取投影 entry 的宿主机工作区根（sourceRoot）；无投影 entry 返回 null。 */
    private static String projectionSourceRoot(WorkspaceSpec spec) {
        if (spec == null || spec.getEntries() == null) {
            return null;
        }
        for (var entryObj : spec.getEntries().values()) {
            if (entryObj instanceof WorkspaceProjectionEntry entry
                    && entry.getSourceRoot() != null && !entry.getSourceRoot().isBlank()) {
                return entry.getSourceRoot();
            }
        }
        return null;
    }

    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        // tar 打包 workspace 并 base64 编码传输（镜像约束已验证 tar/base64 可用）
        var execution = osbSandbox.commands().run("tar cf - -C " + getWorkspaceRoot() + " . | base64");
        if (execution.getExitCode() != null && execution.getExitCode() != 0) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                "persistWorkspace failed with exit " + execution.getExitCode());
        }
        var base64 = execution.getLogs() != null
            ? execution.getLogs().getStdout().stream()
                .map(msg -> msg.getText())
                .collect(Collectors.joining())
            : "";
        return new ByteArrayInputStream(Base64.getDecoder().decode(base64));
    }

    /**
     * 投影归档传输：gzip 压缩 + 唯一临时文件 + 容器内解包。
     *
     * <p>gzip：投影根含技能目录（.skills-cache，可达数十 MB）时压缩传输量 30%+；
     * 纯随机二进制收益有限，但技能资产（模板/文本/图片）普遍可压。
     *
     * <p>唯一临时文件：旧实现所有 hydrate 共用 /tmp/workspace.tar.b64——单文件写入
     * （SDK uploadFiles 退化路径）与投影刷新并发时，A 刚 rm 临时文件、B 的
     * files/upload 落在中间 → execd chmod 报 no such file → 写入整体失败，
     * 下一轮重复传全量投影（实测一个容器生命周期重复 10 次全量 tar 传输）。
     * 唯一化后各 hydrate 互不干扰。
     */
    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        // 点号分隔（workspace.{uuid}.tar.gz）：真实 execd 按字面执行无歧义；
        // e2e mock 的路径映射对 "/workspace" 有 (?![.\w]) 负向前瞻，'-' 分隔会把
        // workspace-{uuid} 里的 /workspace 也二次映射（'.' 则被前瞻排除，两侧行为一致）
        var tmpPath = "/tmp/workspace." + UUID.randomUUID() + ".tar.gz";
        var bytes = archive.readAllBytes();
        var compressed = new java.io.ByteArrayOutputStream(Math.max(64, bytes.length / 2));
        try (var gz = new java.util.zip.GZIPOutputStream(compressed)) {
            gz.write(bytes);
        }
        var base64 = Base64.getEncoder().encodeToString(compressed.toByteArray());

        // 写入临时文件后解压到 workspace（分两步避免单条命令参数超限）
        osbSandbox.files().write(List.of(
            com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                .path(tmpPath)
                .data(base64)
                .mode(644)
                .build()
        ));
        var execution = osbSandbox.commands().run(
            "base64 -d " + tmpPath + " | tar xzf - -C " + getWorkspaceRoot()
                + " && rm " + tmpPath);
        if (execution.getExitCode() != null && execution.getExitCode() != 0) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                "hydrateWorkspace failed with exit " + execution.getExitCode());
        }
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        osbSandbox.commands().run("mkdir -p " + getWorkspaceRoot());
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        // 沙箱实例生命周期由 OpenSandboxClient.delete()/Server 管理，此处无额外清理
    }

    @Override
    protected String getWorkspaceRoot() {
        return options.getWorkspaceRoot();
    }

    public com.alibaba.opensandbox.sandbox.Sandbox getOsbSandbox() {
        return osbSandbox;
    }

    /**
     * 经 execd files API 读取沙箱工作区文件字节（供 present_file 等工具在
     * 沙箱模式下直读文件，避免 Agent 复述大段 base64）。
     *
     * <p>路径语义与注入一致：无前导斜杠 → 相对 /workspace；带前导斜杠 → 容器绝对路径。
     * 统一按相对路径处理（file_path 已由调用方 normalize）。
     *
     * @param relPath 工作区相对路径（如 outputs/xxx.zip）
     * @return 文件字节；文件不存在或读取失败返回 null
     */
    public byte[] readWorkspaceFile(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return null;
        }
        // scoped 投放延迟触发：技能资源读取路径命中 .skills-cache 时同步投放（CAS 每 call 一次）
        if (relPath.contains(SkillCacheScope.CACHE_DIR)) {
            hydrateScopedSkillCache();
        }
        try {
            return osbSandbox.files().readByteArray(relPath);
        } catch (Exception e) {
            log.debug("[sandbox-open] read workspace file failed ({}): {}", relPath, e.getMessage());
            return null;
        }
    }

    public OpenSandboxState getOsbState() {
        return state;
    }
}
