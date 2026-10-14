package io.agentmanager.framework.sandbox.opensandbox;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
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
 */
public class OpenSandbox extends AbstractBaseSandbox {

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
    private volatile String userKey;
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
        super(state);
        this.state = state;
        this.osbSandbox = osbSandbox;
        this.options = options;
        this.workspaceReader = workspaceReader;
        this.workspaceSyncService = workspaceSyncService;
        this.filesystemSpec = filesystemSpec;
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
    }

    /** 绑定用户 key（SandboxUserKeyMiddleware 经 OpenSandboxClient 注入） */
    public void setUserKey(String userKey) {
        this.userKey = userKey;
        log.info("[sandbox-open] userKey bound: {}", userKey);
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
        if (workspaceSyncService != null && userKey != null) {
            try {
                workspaceSyncService.syncBack(userKey, osbSandbox);
            } catch (Exception e) {
                log.warn("Workspace sync back failed: {}", e.getMessage());
            }
        }
        super.stop();
    }

    @Override
    protected ExecResult doExec(RuntimeContext ctx, String command, int timeoutSeconds) throws Exception {
        log.info("[sandbox-open] doExec invoked: userKey={}, ctx={}", userKey, ctx != null ? ctx.getUserId() : null);
        if (ctx != null) {
            var key = resolveUserKey(ctx);
            if (key != null) {
                userKey = key;
            }
        }
        injectRuntimeFilesIfNeeded(ctx);
        // 上传文件注入兜底（幂等）：create/resume 注入可能因 userKey 未绑定而跳过，
        // 此处用实例 userKey 重试（execute/文件工具首次调用即触发）
        injectPendingUploads();
        // 会话开始物化 L4（幂等）：把 KV 的用户技能投影进容器 /workspace/skills，
        // 使管理面写入/从包内下发在沙箱档会话内生效（兜底路径，主路径见 SandboxUserKeyMiddleware）
        materializeUserSkills();
        // 工作区投影新鲜度检查（幂等）：补齐「stage 晚于 start」的技能目录（见方法注释）
        refreshProjectionIfStale();
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
                int count = workspaceReader.materializeUserSkills(osbSandbox, uid);
                userSkillsMaterialized.set(true);
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
            var payload = WorkspaceProjectionApplier.build(spec);
            if (payload == null) {
                return;
            }
            if (payload.hash().equals(state.getWorkspaceProjectionHash())) {
                log.debug("[sandbox-open] workspace projection unchanged, skipping refresh");
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
        } catch (Exception e) {
            projectionRefreshed.set(false);
            log.warn("[sandbox-open] workspace projection refresh failed: {}", e.getMessage());
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

    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        var bytes = archive.readAllBytes();
        var base64 = Base64.getEncoder().encodeToString(bytes);

        // 写入临时文件后解压到 workspace（分两步避免单条命令参数超限）
        osbSandbox.files().write(List.of(
            com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                .path("/tmp/workspace.tar.b64")
                .data(base64)
                .mode(644)
                .build()
        ));
        var execution = osbSandbox.commands().run(
            "base64 -d /tmp/workspace.tar.b64 | tar xf - -C " + getWorkspaceRoot()
                + " && rm /tmp/workspace.tar.b64");
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
