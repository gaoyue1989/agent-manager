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
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionApplier;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;

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
    private final AtomicBoolean userSkillsMaterialized = new AtomicBoolean(false);
    private final AtomicBoolean projectionRefreshed = new AtomicBoolean(false);
    /** 投影 stat 指纹缓存开关（SANDBOX_PROJECTION_FINGERPRINT_CACHE，默认 true；测试构造可关） */
    private final boolean projectionFingerprintCacheEnabled;
    /** L4 物化 revision 门控开关（SANDBOX_SKILL_REVISION_GATE，默认 true；测试构造可关） */
    private final boolean skillRevisionGateEnabled;
    /** 回写快照差分开关（SANDBOX_SYNC_SNAPSHOT_DIFF，默认 true；测试构造可关） */
    private final boolean syncSnapshotDiffEnabled;
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
    /** 上次投放的 scoped .skills-cache 内容 hash（跨 call 保留，未变则跳过重投） */
    private final java.util.concurrent.atomic.AtomicReference<String> lastSkillCacheHash =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final OpenSandboxFilesystemSpec filesystemSpec;
    private final FileAssetStore fileAssetStore;
    private final FileStorage fileStorage;

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
                       boolean projectionFingerprintCacheEnabled) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage, projectionFingerprintCacheEnabled, true, true);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec,
                       FileAssetStore fileAssetStore,
                       FileStorage fileStorage,
                       boolean projectionFingerprintCacheEnabled,
                       boolean skillRevisionGateEnabled) {
        this(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage,
            projectionFingerprintCacheEnabled, skillRevisionGateEnabled, true);
    }

    public OpenSandbox(OpenSandboxState state,
                       com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                       OpenSandboxClientOptions options,
                       WorkspaceReader workspaceReader,
                       WorkspaceSyncService workspaceSyncService,
                       OpenSandboxFilesystemSpec filesystemSpec,
                       FileAssetStore fileAssetStore,
                       FileStorage fileStorage,
                       boolean projectionFingerprintCacheEnabled,
                       boolean skillRevisionGateEnabled,
                       boolean syncSnapshotDiffEnabled) {
        super(state);
        this.state = state;
        this.osbSandbox = osbSandbox;
        this.options = options;
        this.workspaceReader = workspaceReader;
        this.workspaceSyncService = workspaceSyncService;
        this.filesystemSpec = filesystemSpec;
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.projectionFingerprintCacheEnabled = projectionFingerprintCacheEnabled;
        this.skillRevisionGateEnabled = skillRevisionGateEnabled;
        this.syncSnapshotDiffEnabled = syncSnapshotDiffEnabled;
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
     * （start 投影 + {@link #refreshProjectionIfStale} 的 hash 一致性检查），若直传绕过，
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
        // 投影新鲜度检查按 call 粒度执行：下轮 call 的 onSystemPrompt 可能再 stage 出新
        // 技能目录（scopeKey 每轮变化），stop 复位后下轮首次 exec 重新比对
        projectionRefreshed.set(false);
        // scoped .skills-cache 投放同样按 call 粒度复位（下轮 scope 可能变化或新 stage）
        skillCacheRefreshed.set(false);
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
        super.stop();
    }

    @Override
    protected ExecResult doExec(RuntimeContext ctx, String command, int timeoutSeconds) throws Exception {
        log.info("[sandbox-open] doExec invoked: userKey={}, ctx={}", userKey, ctx != null ? ctx.getUserId() : null);
        // ④ 会话水合协调器：userKey 一次解析 + 四步幂等注入的唯一 doExec 侧入口
        hydrateForCall(ctx);
        var execution = osbSandbox.commands().run(command);
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

    /**
     * 会话水合协调器（设计 ④，纯重构无行为变化）：四步幂等注入统一入口——middleware 主路径
     * （{@link SandboxUserKeyMiddleware#onAgent}）与 doExec 兜底路径共用。各步自带幂等标志
     * （runtimeInjected/uploadsInjected/userSkillsMaterialized/projectionRefreshed），协调器本身
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
        refreshProjectionIfStale();
        hydrateScopedSkillCache();
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
                    // 不设 uploadsInjected=true：上传可能尚未完成，下 turn 重试
                    log.debug("[sandbox-open] no pending uploads yet for user {}", userId);
                    return;
                }
                var injectedIds = new java.util.ArrayList<String>();
                // 已占用的工作区路径（防同名覆盖）：来自 file_asset 历史注入记录，
                // 沙箱容器可能在远端主机，无法像非沙箱路径那样 ls 本地目录
                var taken = new java.util.HashSet<>(fileAssetStore.listWorkspacePaths(userId, userId));
                for (var f : pending) {
                    var wsPath = uniqueWorkspacePath(taken, f.fileName());
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
                    // 注入成功后才回写 workspace_path（保证路径与沙箱内实际一致）
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
     * 唯一化工作区路径（uploads/{name}，同名追加 _1/_2，与 UploadWorkspaceInjector 同款语义）。
     * 冲突判定基于 taken 集合（file_asset 历史注入路径 + 本批次已分配），命中即从集合剔除后返回；
     * 上界 1000 兜底 UUID（防极端同名风暴无限循环）。
     */
    private static String uniqueWorkspacePath(java.util.Set<String> taken, String fileName) {
        var base = "uploads/" + fileName;
        if (!taken.contains(base)) {
            return base;
        }
        int dot = fileName.lastIndexOf('.');
        var stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        var ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i <= 1000; i++) {
            var candidate = "uploads/" + stem + "_" + i + ext;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        log.warn("[sandbox-open] too many conflicting names for {}, using UUID fallback", fileName);
        return "uploads/" + stem + "_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ext;
    }

    /**
     * 首次文件操作前注入 KV 运行时文件（MEMORY.md/memory/）。
     * 静态模板（AGENTS.md/skills/ 等）由框架投影在 hydrateWorkspace 时注入。
     */
    private void injectRuntimeFilesIfNeeded(RuntimeContext ctx) {
        if (workspaceReader == null || runtimeInjected.get()) {
            return;
        }
        var userId = resolveUserKey(ctx);
        if (userId == null) {
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
                int count = workspaceReader.materializeUserSkills(osbSandbox, uid);
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
     * 工作区投影新鲜度检查（幂等，每 call 一次）：比对宿主投影目录当前内容与
     * sandbox start 时记录的 hash，变化则把投影 tar 补水合进容器。
     *
     * <p>背景：技能目录（.skills-cache/{scopeKey}/oaf-package/...）由 SDK
     * HarnessSkillMiddleware 在每轮推理的 onSystemPrompt 阶段物化到<b>平台本地</b>工作区，
     * 而 harness 的投影 hydrate 只发生在 sandbox start 时（之后 hash 一致即跳过）——
     * stage 晚于 start 的目录永远到不了容器 /workspace，模型经
     * load_skill_through_path 拿到的 /workspace 路径在 shell 里 ls 不到（E-SKILL-4）。
     *
     * <p>逻辑对齐 harness 私有 applyWorkspaceProjectionIfChanged：build() 重扫宿主投影根
     * （含 .skills-cache），hash 相同跳过；不同则 doHydrateWorkspace 后回写
     * SandboxState.workspaceProjectionHash（空投影同样回写，与 start 路径一致）。
     * tar 解包只覆盖归档内文件，不删除容器内模型自产文件（outputs/ 等）。
     *
     * <p>调用点：doExec（首个命令执行前）。文件工具若完全绕过 exec 则不触发——
     * 技能消费路径（执行 scripts/*.sh）必然走 exec，已覆盖。
     * fail-soft：失败仅告警不阻塞命令，复位标志允许下次 exec 重试。
     */
    private void refreshProjectionIfStale() {
        if (state == null || !projectionRefreshed.compareAndSet(false, true)) {
            return;
        }
        try {
            var spec = state.getWorkspaceSpec();
            if (spec == null) {
                // 投影未启用（SANDBOX_PROJECTION_ENABLED=false）或 spec 未装配：无可刷新
                return;
            }
            // stat 指纹短路（SANDBOX_PROJECTION_FINGERPRINT_CACHE，默认 true）：指纹未变
            // → 宿主投影内容未变 → 直接与 state hash 比对跳过 build（省全量读+sha256+堆内 tar）
            if (projectionFingerprintCacheEnabled
                && ProjectionFingerprintCache.skipBuild(spec, state.getWorkspaceProjectionHash())) {
                log.debug("[sandbox-open] workspace projection unchanged (fingerprint hit), skipping refresh");
                return;
            }
            var payload = WorkspaceProjectionApplier.build(spec);
            if (payload == null) {
                return;
            }
            if (payload.hash().equals(state.getWorkspaceProjectionHash())) {
                log.debug("[sandbox-open] workspace projection unchanged, skipping refresh");
                ProjectionFingerprintCache.record(spec, payload.hash());
                return;
            }
            if (payload.fileCount() > 0) {
                try (var in = new java.io.ByteArrayInputStream(payload.tarBytes())) {
                    doHydrateWorkspace(in);
                }
                log.info("[sandbox-open] workspace projection refreshed: files={}, hash={}",
                    payload.fileCount(), payload.hash());
            }
            // 与 harness start 路径一致：空投影也回写 hash，避免每 call 重算后反复比对
            state.setWorkspaceProjectionHash(payload.hash());
            ProjectionFingerprintCache.record(spec, payload.hash());
        } catch (Exception e) {
            projectionRefreshed.set(false);
            log.warn("[sandbox-open] workspace projection refresh failed: {}", e.getMessage());
        }
    }

    /**
     * scoped 技能缓存投放（方案 1，每 call 一次）：
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
     * <p>fail-soft：失败仅告警并复位标志，下次 exec 重试。
     */
    private void hydrateScopedSkillCache() {
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
            Path scopeDir = Path.of(sourceRoot, SkillCacheScope.CACHE_DIR, scope);
            var payload = Files.isDirectory(scopeDir)
                ? buildScopedSkillCachePayload(spec, sourceRoot, scope)
                : null;
            String hash = payload != null ? payload.hash() : "";
            if (hash.equals(lastSkillCacheHash.get())) {
                // 当前 scope 内容未变：容器内已是最新（stop 复位的下一 call 会重查）
                return;
            }
            // 清容器内其它 scope 残留（本代容器可能服务过同用户的其它会话）
            pruneContainerSkillCache(scope);
            if (payload != null && payload.fileCount() > 0) {
                try (var in = new ByteArrayInputStream(payload.tarBytes())) {
                    doHydrateWorkspace(in);
                }
            }
            lastSkillCacheHash.set(hash);
            log.info("[sandbox-open] .skills-cache scoped to {} ({} files)",
                scope, payload != null ? payload.fileCount() : 0);
        } catch (Exception e) {
            skillCacheRefreshed.set(false);
            log.warn("[sandbox-open] scoped .skills-cache hydrate failed: {}", e.getMessage());
        }
    }

    /** 用当前 scope 构造投影 payload（includeRoots 只含 {@code .skills-cache/<scope>}）。 */
    private WorkspaceProjectionApplier.ProjectionPayload buildScopedSkillCachePayload(
            WorkspaceSpec baseSpec, String sourceRoot, String scope) throws Exception {
        var ws = new WorkspaceSpec();
        ws.setRoot(baseSpec != null ? baseSpec.getRoot() : getWorkspaceRoot());
        var entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot(sourceRoot);
        entry.setIncludeRoots(List.of(SkillCacheScope.CACHE_DIR + "/" + scope));
        Map<String, WorkspaceEntry> entries = new java.util.LinkedHashMap<>();
        entries.put("__scoped_skill_cache__", entry);
        ws.setEntries(entries);
        return WorkspaceProjectionApplier.build(ws);
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

    /** 删除容器 {@code /workspace/.skills-cache} 下非当前 scope 的目录（保留 keepScope）。 */
    private void pruneContainerSkillCache(String keepScope) {
        try {
            String root = getWorkspaceRoot() + "/" + SkillCacheScope.CACHE_DIR;
            String cmd = "if [ -d " + root + " ]; then find " + root
                + " -mindepth 1 -maxdepth 1 -type d ! -name '" + keepScope
                + "' -exec rm -rf {} +; fi";
            osbSandbox.commands().run(cmd);
        } catch (Exception e) {
            // 清理失败不阻塞（残留仅占空间，不影响当前 scope 执行）
            log.debug("[sandbox-open] prune .skills-cache failed: {}", e.getMessage());
        }
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
