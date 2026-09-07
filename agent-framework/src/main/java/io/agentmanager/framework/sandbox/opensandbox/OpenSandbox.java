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
     * 解析 KV 隔离 key：优先 userId，为空时降级 sessionId。
     * 与框架 IsolationScope.USER → SESSION 降级语义一致（debug 页面等
     * Channel 路径 userId 为空，实测 state_data.user_id=""），
     * 保证注入/回写使用同一命名空间。
     */
    private String resolveUserKey(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
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
                    uploadsInjected.set(true);
                    return;
                }
                var injectedIds = new java.util.ArrayList<String>();
                for (var f : pending) {
                    var wsPath = "uploads/" + f.fileName();
                    try (var in = fileStorage.read(f.storageKey())) {
                        osbSandbox.files().write(List.of(
                            com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                                // execd files API 路径基准 = /workspace（实测：无前导斜杠 → 相对 /workspace；
                                // 带前导斜杠 → 容器绝对路径，会写错位）。上传文件统一在 /workspace/uploads/
                                .path(wsPath)
                                .data(Base64.getEncoder().encodeToString(in.readAllBytes()))
                                .mode(644)
                                .build()));
                    }
                    // 注入成功后才回写 workspace_path（保证路径与沙箱内实际一致）
                    fileAssetStore.updateWorkspacePath(f.id(), wsPath);
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
