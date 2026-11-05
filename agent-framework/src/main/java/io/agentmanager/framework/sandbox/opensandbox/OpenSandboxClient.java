package io.agentmanager.framework.sandbox.opensandbox;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * OpenSandbox 沙箱客户端：实现 AgentScope SandboxClient 契约。
 * create() 创建沙箱并注入 KV 运行时文件；resume() 按 sandboxId 恢复。
 *
 * <p>池化（sandbox-pool-design D1/D2，SANDBOX_POOL_ENABLED=true 时启用）：
 * <ul>
 *   <li>{@link #create} 走池借出（{@link SandboxPoolManager#borrow}：idle 直取/按需建/池空等待），
 *       借出后 SET 租约（续约①）再走既有 bind/reset/inject/start 链——SDK acquire 语义零改动；</li>
 *   <li>{@link #resume} connect 成功后续约（续约②，跨副本全局键——会话存续期保活）；</li>
 *   <li>{@link #delete} 路由进池（D13：kill + 认领 + 异步补位，防池容量静默缩水）。</li>
 * </ul>
 * 池管理器为 null（默认 SANDBOX_POOL_ENABLED=false）时全部走现状路径，行为逐分支一致。
 */
public class OpenSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenSandboxClient.class);

    private final OpenSandboxClientOptions options;
    private final WorkspaceReader workspaceReader;
    private final WorkspaceSyncService workspaceSyncService;
    private final OpenSandboxFilesystemSpec filesystemSpec;
    private final io.agentmanager.framework.service.FileAssetStore fileAssetStore;
    private final io.agentmanager.framework.service.storage.FileStorage fileStorage;
    private final com.alibaba.opensandbox.sandbox.config.ConnectionConfig connectionConfig;
    private final ObjectMapper objectMapper;
    /** 沙箱池管理器（null = 池关闭，现状路径；由 OpenSandboxFilesystemSpec.createClient 注入） */
    private volatile SandboxPoolManager sandboxPool;

    public OpenSandboxClient(OpenSandboxClientOptions options) {
        this(options, null, null, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader) {
        this(options, workspaceReader, null, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService) {
        this(options, workspaceReader, workspaceSyncService, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService,
                             OpenSandboxFilesystemSpec filesystemSpec) {
        this(options, workspaceReader, workspaceSyncService, filesystemSpec, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService,
                             OpenSandboxFilesystemSpec filesystemSpec,
                             io.agentmanager.framework.service.FileAssetStore fileAssetStore,
                             io.agentmanager.framework.service.storage.FileStorage fileStorage) {
        this.options = options;
        this.workspaceReader = workspaceReader;
        this.workspaceSyncService = workspaceSyncService;
        this.filesystemSpec = filesystemSpec;
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.objectMapper = new ObjectMapper();
        this.connectionConfig = com.alibaba.opensandbox.sandbox.config.ConnectionConfig.builder()
            .domain(options.getServerUrl())
            .apiKey(options.getApiKey())
            .protocol("http")
            // 经 Server 代理转发 execd/endpoint 请求（客户端无需直连沙箱容器）
            .useServerProxy(true)
            .build();
    }

    /** 池管理器注入（OpenSandboxFilesystemSpec.createClient 装配时调用；null = 池关闭） */
    public void setSandboxPoolManager(SandboxPoolManager poolManager) {
        this.sandboxPool = poolManager;
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          OpenSandboxClientOptions options) {
        // 池开启（D2 借出点）：借出预创建容器并绑定当次 spec——SDK acquire P4 语义不变
        if (sandboxPool != null) {
            return createFromPool(workspaceSpec, options);
        }
        // 1. 通过 OpenSandbox SDK 创建沙箱
        var builder = com.alibaba.opensandbox.sandbox.Sandbox.builder()
            .connectionConfig(connectionConfig)
            .image(options.getImage())
            .timeout(options.getTimeout())
            .resource(options.getResource())
            .env(options.getEnvironment());
        // D19 资源双档（sandbox-pool-design，独立于池开关生效）：Pod requests 透传——
        // 缺省时上游 server 把 resourceLimits 同时作 limits/requests（每容器全额保障的根因）；
        // null（未配置）保持现状不传
        if (options.getResourceRequests() != null && !options.getResourceRequests().isEmpty()) {
            builder.resourceRequests(options.getResourceRequests());
        }
        // entrypoint 仅在配置非空时设置，避免覆盖镜像默认启动命令
        if (options.getEntrypoint() != null && !options.getEntrypoint().isEmpty()) {
            builder.entrypoint(options.getEntrypoint());
        }
        var osbSandbox = builder.build();

        // 2. 构建状态
        var info = osbSandbox.getInfo();
        OpenSandboxState state = new OpenSandboxState();
        state.setSandboxId(info.getId());
        try {
            state.setSandboxEndpoint(osbSandbox.getEndpoint(44772).getEndpoint());
        } catch (Exception e) {
            // execd 端点获取失败不阻塞：SDK 命令/文件操作自行解析端点
            state.setSandboxEndpoint(null);
        }
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(options.getImage());
        state.setCreatedAt(System.currentTimeMillis());

        // 3. 包装为 AgentScope Sandbox 并启动
        //    KV 运行时文件（MEMORY.md/memory/）由 OpenSandbox 首次 exec 时延迟注入
        //    （create() 无 RuntimeContext 参数拿不到 userId，见 OpenSandbox 注释）
        //    userId 由 SandboxUserKeyMiddleware 注入（filesystemSpec.pendingUserKey）
        OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage);
        bindUserKey(sandbox);
        // 被动兜底（§6.2.4 双保险）：新容器启动时，该用户 injected 上传文件回滚 pending
        // → 本 turn 注入。防 delete 崩溃导致状态 stuck 在新容器漏注入。
        if (sandbox.getUserKey() != null && fileAssetStore != null) {
            fileAssetStore.resetInjectedToPending(sandbox.getUserKey());
        }
        // 上传文件注入：沙箱就绪后立即执行（沙箱文件操作走 execd 原生 API，不保证经 doExec）
        sandbox.injectPendingUploads();
        try {
            sandbox.start();
        } catch (Exception e) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR,
                "create", "Failed to start OpenSandbox: " + e.getMessage(), e);
        }

        return sandbox;
    }

    /** 从请求级 ThreadLocal 读取 userId 绑定到沙箱（middleware 注入） */
    private void bindUserKey(OpenSandbox sandbox) {
        if (filesystemSpec != null) {
            var key = filesystemSpec.takePendingUserKey();
            if (key != null && !key.isBlank()) {
                sandbox.setUserKey(key);
            }
            var scope = filesystemSpec.takePendingSkillScope();
            if (scope != null && !scope.isBlank()) {
                sandbox.setSkillCacheScope(scope);
            }
        }
    }

    /**
     * 池借出路径（sandbox-pool-design §5.2，SANDBOX_POOL_ENABLED=true）：
     * pool.borrow（idle 直取/按需建/池空等待）→ SET 租约（续约①，借出时已 SET）→ 注入当次
     * workspaceSpec（借出容器预创建时无 spec，start 投影 hydrate 依赖它）→ 照现状执行
     * bindUserKey/reset/inject/start 链——SDK acquire 四级优先级语义零改动。
     *
     * <p>借出身份（D17 P1）：sid 取 pendingSessionId（守卫钩子/middleware 注入的 turn_lease 键），
     * userKey <b>peek</b>（不清除——{@link #bindUserKey} 随后 take 消费并绑定实例，供 stop 回写）。
     * Redis 不可达 → fail-open（D8）：借出降级为直接建容器（账本外，Server TTL 兜底消亡），
     * 对话可用性不劣于现状。
     */
    private Sandbox createFromPool(WorkspaceSpec workspaceSpec, OpenSandboxClientOptions options) {
        String poolSid = filesystemSpec != null ? filesystemSpec.takePendingSessionId() : null;
        String poolUserKey = filesystemSpec != null ? filesystemSpec.peekPendingUserKey() : null;
        SandboxPoolManager.Borrowed borrowed;
        try {
            borrowed = sandboxPool.borrow(poolSid, poolUserKey);
        } catch (io.lettuce.core.RedisException e) {
            // D8 fail-open：Redis 故障期借出直接建容器（限流告警在 manager 内）
            borrowed = sandboxPool.borrowFailOpen(poolSid, poolUserKey);
        }
        var osbSandbox = borrowed.osbSandbox();

        // 构建状态：sandboxId = 借出容器 id；workspaceSpec = 当次调用传入（§5.2 步骤 4）
        OpenSandboxState state = new OpenSandboxState();
        state.setSandboxId(borrowed.sandboxId());
        try {
            state.setSandboxEndpoint(osbSandbox.getEndpoint(44772).getEndpoint());
        } catch (Exception e) {
            state.setSandboxEndpoint(null);
        }
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(options.getImage());
        state.setCreatedAt(System.currentTimeMillis());

        // 包装 + 照现状链执行（bind/reset/inject/start；借出容器未 start，
        // create 内 start 一次 + acquireForCall 再 start 一次依赖 running CAS 幂等）
        OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage);
        sandbox.setSandboxPoolManager(sandboxPool);
        bindUserKey(sandbox);
        if (sandbox.getUserKey() != null && fileAssetStore != null) {
            fileAssetStore.resetInjectedToPending(sandbox.getUserKey());
        }
        sandbox.injectPendingUploads();
        try {
            sandbox.start();
        } catch (Exception e) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR,
                "create", "Failed to start pooled OpenSandbox: " + e.getMessage(), e);
        }
        return sandbox;
    }

    @Override
    public Sandbox resume(SandboxState state) {
        OpenSandboxState osbState = (OpenSandboxState) state;

        // 通过 OpenSandbox SDK 连接已有沙箱；沙箱已销毁（404）时抛异常 →
        // SandboxManager.acquire() 捕获后降级 create（框架行为）。
        // 注意：用 connector().connect() 而非 resumer().resume()——
        // resume 端点仅对 Paused 沙箱有效，Running 沙箱会 409 Conflict。
        var osbSandbox = com.alibaba.opensandbox.sandbox.Sandbox.connector()
            .sandboxId(osbState.getSandboxId())
            .connectionConfig(connectionConfig)
            .skipHealthCheck(true)
            .connect();

        // 池化续约② + 账本校正（D2 D17 扩展）：connect 成功后校正——busy 且 sid==本会话仅续约；
        // 粘滞且 sid/owner==本会话原子复活回 busy；被继承给他人/已回收 → close 句柄后抛异常
        // 触发 SDK P3→P4 降级走借出决策树（不在 connect 前写账本，杜绝幻影 busy）。
        if (sandboxPool != null) {
            // 借出身份 peek（不清除——bindUserKey 随后 take 消费绑定实例）
            String sid = filesystemSpec != null ? filesystemSpec.peekPendingSessionId() : null;
            String userKey = filesystemSpec != null ? filesystemSpec.peekPendingUserKey() : null;
            if (!sandboxPool.reconcileResume(osbState.getSandboxId(), sid, userKey)) {
                try {
                    osbSandbox.close();   // SDK Sandbox AutoCloseable：降级前释放已 connect 句柄
                } catch (Exception ce) {
                    log.warn("[sandbox-pool] close on reconcile-downgrade failed: sandboxId={}: {}",
                        osbState.getSandboxId(), ce.getMessage());
                }
                throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "resume",
                    "sandbox pool reconcile failed for container " + osbState.getSandboxId()
                        + " (reclaimed or reassigned; downgrading to borrow)", null);
            }
            sandboxPool.renew(osbState.getSandboxId());
        }

        var sandbox = new OpenSandbox(osbState, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage);
        sandbox.setSandboxPoolManager(sandboxPool);
        bindUserKey(sandbox);
        // 上传文件注入：恢复会话后立即执行（同 create）
        sandbox.injectPendingUploads();
        return sandbox;
    }

    @Override
    public void delete(Sandbox sandbox) {
        OpenSandbox osb = (OpenSandbox) sandbox;
        // 主动回滚（§6.2.4 双保险 ①）：容器销毁前将该用户 injected 上传文件回滚 pending，
        // 新容器首 exec 重新注入（防容器 GC 后文件丢失）。
        // userKey 兜底：delete 可能在 acquire/middleware 时序外调用，
        // 实例 userKey 可能为 null（acquire 先于 middleware）→ 从 spec ThreadLocal 补充。
        String key = osb.getUserKey();
        if ((key == null || key.isBlank()) && filesystemSpec != null) {
            key = filesystemSpec.peekPendingUserKey();
        }
        if (key != null && !key.isBlank() && fileAssetStore != null) {
            fileAssetStore.resetInjectedToPending(key, key);
        }
        // 池开启（D13）：delete 路由进池——kill + Lua 认领移除 busy + 异步补位，
        // 防 SDK 内部/未来调用方直杀容器导致池容量静默缩水
        if (sandboxPool != null) {
            sandboxPool.release(osb.getOsbState().getSandboxId());
            return;
        }
        try {
            osb.getOsbSandbox().kill();
            osb.getOsbSandbox().close();
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.WORKSPACE_STOP_ERROR,
                "delete",
                "Failed to delete OpenSandbox: " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.CONFIGURATION_ERROR,
                "serializeState",
                "Failed to serialize sandbox state",
                e
            );
        }
    }

    @Override
    public SandboxState deserializeState(String serialized) {
        try {
            return objectMapper.readValue(serialized, OpenSandboxState.class);
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.CONFIGURATION_ERROR,
                "deserializeState",
                "Failed to deserialize sandbox state",
                e
            );
        }
    }
}
